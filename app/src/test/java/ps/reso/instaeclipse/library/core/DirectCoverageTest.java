package ps.reso.instaeclipse.library.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;
import static ps.reso.instaeclipse.library.core.LibraryFixture.*;

/**
 * Coverage model for paged Direct history (handoff §3.3). A fake paged source repeats, reorders,
 * drops and re-adds items; none of that may change a settled state or a stored copy.
 */
public final class DirectCoverageTest {
    private static final String ACCOUNT = "111";
    private static final String THREAD = "340282366841710300949128";

    private static DirectCoverage.Discovered item(String id, long ts) {
        return new DirectCoverage.Discovered(id, false, "image", false, ts);
    }

    private static DirectCoverage.Page page(boolean start, DirectCoverage.Discovered... items) {
        return new DirectCoverage.Page(Arrays.asList(items), start);
    }

    /** A thread of {@code size} items; ids are server ids, timestamps increase with the id. */
    private static List<DirectCoverage.Discovered> thread(int size) {
        List<DirectCoverage.Discovered> all = new ArrayList<>();
        for (int i = 1; i <= size; i++) all.add(item("3" + (1000 + i), 1_700_000_000_000L + i * 1000L));
        return all;
    }

    @Test public void repeatedReorderedAndBothDirectionPagesNeverChangeSettledStates() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            DirectCapture capture = new DirectCapture(library, coverage);
            List<DirectCoverage.Discovered> all = thread(12);
            // Newest page first, as Direct opens, then older, then the newest again, then a gap.
            int[][] pages = { {8, 12}, {4, 8}, {8, 12}, {0, 4}, {6, 10}, {4, 8} };
            Random shuffle = new Random(7);
            for (int[] bounds : pages) {
                List<DirectCoverage.Discovered> slice = new ArrayList<>(all.subList(bounds[0], bounds[1]));
                Collections.shuffle(slice, shuffle);
                coverage.discover(INSTAGRAM, ACCOUNT, THREAD, new DirectCoverage.Page(slice, bounds[0] == 0));
                for (DirectCoverage.Discovered seen : slice) {
                    int seed = Integer.parseInt(seen.messageKey);
                    capture.capture(INSTAGRAM, ACCOUNT, THREAD, seen, source(jpeg(seed)));
                }
            }
            DirectCoverage.Report report = coverage.report(INSTAGRAM, ACCOUNT, THREAD);
            DirectCoverage.ThreadReport t = report.threads.get(0);
            assertEquals(12, t.discovered);
            assertEquals(12, t.imported);
            assertEquals(0, t.alreadyPresent); // a repeat capture keeps IMPORTED, never downgrades
            assertEquals(12, library.listChat(INSTAGRAM, ACCOUNT, THREAD).size());
            assertTrue(t.reachedStart);
            assertTrue(t.complete());
            assertEquals(all.get(0).timestamp, t.oldestTimestamp);
            assertEquals(all.get(11).timestamp, t.newestTimestamp);
            assertEquals(6, t.pages);
        }
    }

    @Test public void absenceFromPagesSessionsOrUiNeverDeletesOrInvalidatesACopy() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            DirectCapture capture = new DirectCapture(library, coverage);
            DirectCoverage.Discovered gone = item("31001", 1);
            coverage.discover(INSTAGRAM, ACCOUNT, THREAD, page(false, gone, item("31002", 2)));
            capture.capture(INSTAGRAM, ACCOUNT, THREAD, gone, source(jpeg(31001)));
            // The message disappears: later pages, a new process and a different session never list it.
            for (int round = 0; round < 5; round++) {
                MediaLibrary restarted = fixture.open();
                new DirectCoverage(restarted).discover(INSTAGRAM, ACCOUNT, THREAD, page(round == 4, item("31002", 2)));
                restarted.reconcile(true);
                restarted.purgeExpiredTrash();
                fixture.clock.addAndGet(400L * 24 * 3600 * 1000);
            }
            MediaLibrary later = fixture.open();
            LibraryItem kept = later.lookup(INSTAGRAM, ACCOUNT, THREAD, "31001", false);
            assertNotNull(kept);
            assertArrayEquals(jpeg(31001), Files.readAllBytes(later.fileOf(kept).toPath()));
            assertEquals(DirectCoverage.State.IMPORTED,
                    new DirectCoverage(later).stateOf(INSTAGRAM, ACCOUNT, THREAD, "31001", false));
        }
    }

    @Test public void transientFailuresAreRetriedAndNeverCountAsProcessed() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            DirectCapture capture = new DirectCapture(library, coverage);
            DirectCoverage.Discovered flaky = item("32001", 5);
            coverage.discover(INSTAGRAM, ACCOUNT, THREAD, page(true, flaky));
            DirectCapture.Outcome first = capture.capture(INSTAGRAM, ACCOUNT, THREAD, flaky, () -> {
                throw new IOException("connection reset while the client streamed the file");
            });
            assertEquals(DirectCoverage.State.FAILED, first.state);
            DirectCoverage.Report report = coverage.report(INSTAGRAM, ACCOUNT, null);
            assertEquals(0, report.processed);
            assertEquals(1, report.failed);
            assertFalse(report.complete());
            assertEquals(1, coverage.retryable(INSTAGRAM, ACCOUNT, 10).size());

            DirectCapture.Outcome retry = capture.capture(INSTAGRAM, ACCOUNT, THREAD, flaky, source(jpeg(32001)));
            assertEquals(DirectCoverage.State.IMPORTED, retry.state);
            report = coverage.report(INSTAGRAM, ACCOUNT, null);
            assertEquals(1, report.processed);
            assertTrue(report.complete());
            assertEquals(0, coverage.retryable(INSTAGRAM, ACCOUNT, 10).size());
        }
    }

    @Test public void failuresStopBeingRetriedAfterTheLimitButStayVisible() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            DirectCapture capture = new DirectCapture(library, coverage);
            DirectCoverage.Discovered flaky = item("32002", 5);
            for (int i = 0; i < DirectCoverage.MAX_ATTEMPTS; i++) {
                capture.capture(INSTAGRAM, ACCOUNT, THREAD, flaky, () -> { throw new IOException("fail"); });
            }
            assertTrue(coverage.retryable(INSTAGRAM, ACCOUNT, 10).isEmpty());
            DirectCoverage.Report report = coverage.report(INSTAGRAM, ACCOUNT, null);
            assertEquals(1, report.failed);
            assertFalse(report.complete());
        }
    }

    @Test public void aThreadWhoseStartWasNotReachedIsNeverComplete() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            DirectCapture capture = new DirectCapture(library, coverage);
            DirectCoverage.Discovered only = item("33001", 9);
            coverage.discover(INSTAGRAM, ACCOUNT, THREAD, page(false, only));
            capture.capture(INSTAGRAM, ACCOUNT, THREAD, only, source(jpeg(33001)));
            DirectCoverage.ThreadReport t = coverage.report(INSTAGRAM, ACCOUNT, THREAD).threads.get(0);
            assertEquals(1, t.processed());
            assertFalse(t.complete());
            // Reaching the start once is kept; a later page that does not reach it says nothing.
            coverage.discover(INSTAGRAM, ACCOUNT, THREAD, page(true));
            coverage.discover(INSTAGRAM, ACCOUNT, THREAD, page(false, only));
            assertTrue(coverage.report(INSTAGRAM, ACCOUNT, THREAD).threads.get(0).complete());
            // A new, unprocessed item reopens it.
            coverage.discover(INSTAGRAM, ACCOUNT, THREAD, page(false, item("33002", 10)));
            assertFalse(coverage.report(INSTAGRAM, ACCOUNT, THREAD).threads.get(0).complete());
        }
    }

    @Test public void unavailableBytesAreReportedAndSettleLaterWhenTheClientHasThem() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            DirectCapture capture = new DirectCapture(library, coverage);
            DirectCoverage.Discovered later = new DirectCoverage.Discovered("34001", false, "unknown", true, 3);
            coverage.discover(INSTAGRAM, ACCOUNT, THREAD, page(true, later));
            assertEquals(DirectCoverage.State.UNAVAILABLE,
                    capture.capture(INSTAGRAM, ACCOUNT, THREAD, later, null).state);
            DirectCoverage.Report report = coverage.report(INSTAGRAM, ACCOUNT, null);
            assertEquals(1, report.unavailable);
            assertEquals(1, report.threads.get(0).viewOnce);
            assertFalse(report.complete());
            // The user opens it in Instagram; the client's own download is offered.
            DirectCapture.Outcome kept = capture.capture(INSTAGRAM, ACCOUNT, THREAD, later, source(jpeg(34001)));
            assertEquals(DirectCoverage.State.IMPORTED, kept.state);
            assertTrue(kept.item.viewOnce);
            assertEquals("captured", kept.item.origin);
            // Unavailable after imported never downgrades.
            assertEquals(DirectCoverage.State.IMPORTED, capture.capture(INSTAGRAM, ACCOUNT, THREAD, later, null).state);
        }
    }

    /** Incomplete bytes are never stored, but stay retryable: a cut transfer looks the same. */
    @Test public void incompleteBytesAreNeverStoredAndStayRetryable() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            DirectCapture capture = new DirectCapture(library, coverage);
            byte[] whole = jpeg(35001);
            byte[] truncated = Arrays.copyOf(whole, whole.length / 2);
            DirectCapture.Outcome refused = capture.capture(INSTAGRAM, ACCOUNT, THREAD, item("35001", 1), source(truncated));
            assertEquals(DirectCoverage.State.FAILED, refused.state);
            assertEquals("INCOMPLETE_MEDIA", refused.error);
            assertTrue(library.listChat(INSTAGRAM, ACCOUNT, THREAD).isEmpty());
            assertEquals(0, library.blobs().listBlobs().size());
        }
    }

    @Test public void twoDifferentCopiesForOneIdentityAreAmbiguousAndNeverPicked() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            DirectCapture capture = new DirectCapture(library, coverage);
            DirectCoverage.Discovered id = item("36001", 1);
            assertEquals(DirectCoverage.State.IMPORTED,
                    capture.capture(INSTAGRAM, ACCOUNT, THREAD, id, source(jpeg(1))).state);
            assertEquals(DirectCoverage.State.AMBIGUOUS,
                    capture.capture(INSTAGRAM, ACCOUNT, THREAD, id, source(jpeg(2))).state);
            assertNull(library.lookup(INSTAGRAM, ACCOUNT, THREAD, "36001", false));
            assertEquals(2, library.listChat(INSTAGRAM, ACCOUNT, THREAD).size());
            // A third offer of either copy keeps AMBIGUOUS.
            assertEquals(DirectCoverage.State.AMBIGUOUS,
                    capture.capture(INSTAGRAM, ACCOUNT, THREAD, id, source(jpeg(1))).state);
            assertEquals(1, coverage.report(INSTAGRAM, ACCOUNT, null).threads.get(0).ambiguous);
        }
    }

    @Test public void identitiesAreIsolatedByAccountThreadAndDirection() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            DirectCapture capture = new DirectCapture(library, coverage);
            DirectCoverage.Discovered received = item("37001", 1);
            DirectCoverage.Discovered sent = new DirectCoverage.Discovered("37001", true, "image", false, 1);
            capture.capture(INSTAGRAM, ACCOUNT, THREAD, received, source(jpeg(1)));
            capture.capture(INSTAGRAM, ACCOUNT, THREAD, sent, source(jpeg(2)));
            capture.capture(INSTAGRAM, "222", THREAD, received, source(jpeg(3)));
            capture.capture(INSTAGRAM, ACCOUNT, "999", received, source(jpeg(4)));
            assertArrayEquals(jpeg(1), read(library, ACCOUNT, THREAD, "37001", false));
            assertArrayEquals(jpeg(2), read(library, ACCOUNT, THREAD, "37001", true));
            assertArrayEquals(jpeg(3), read(library, "222", THREAD, "37001", false));
            assertArrayEquals(jpeg(4), read(library, ACCOUNT, "999", "37001", false));
            assertEquals(3, coverage.report(INSTAGRAM, ACCOUNT, null).discovered);
            assertEquals(1, coverage.report(INSTAGRAM, "222", null).discovered);
            assertTrue(coverage.report(INSTAGRAM, "333", null).threads.isEmpty());
        }
    }

    @Test public void stateSurvivesAFreshProcessAndOfflineReads() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            new DirectCapture(library, coverage).capture(INSTAGRAM, ACCOUNT, THREAD, item("38001", 1), source(jpeg(38001)));
            coverage.discover(INSTAGRAM, ACCOUNT, THREAD, page(false, item("38002", 2)));
            // A different OS process with the network unavailable reads the same state and bytes.
            String out = ChildProcess.finish(ChildProcess.start(ChildProcess.CoverageReader.class,
                    Collections.emptyList(), fixture.root.getAbsolutePath(), ACCOUNT, THREAD, "38001", "38002"), 120);
            assertTrue(out, out.contains("states IMPORTED,PENDING"));
            MediaLibrary restarted = fixture.open();
            assertArrayEquals(jpeg(38001), read(restarted, ACCOUNT, THREAD, "38001", false));
            assertEquals(1, new DirectCoverage(restarted).retryable(INSTAGRAM, ACCOUNT, 5).size());
        }
    }

    @Test public void viewOnceFlagSurvivesBackupAndRestore() throws Exception {
        try (LibraryFixture from = new LibraryFixture(); LibraryFixture to = new LibraryFixture()) {
            MediaLibrary source = from.open();
            new DirectCapture(source, new DirectCoverage(source)).capture(INSTAGRAM, ACCOUNT, THREAD,
                    new DirectCoverage.Discovered("39001", false, "image", true, 4), source(jpeg(39001)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            char[] password = "pw-for-test".toCharArray();
            new LibraryBackup(source).export(out, password, 1000, 4096);
            MediaLibrary target = to.open();
            new LibraryBackup(target).restore(new ByteArrayInputStream(out.toByteArray()), password, 1000);
            LibraryItem restored = target.lookup(INSTAGRAM, ACCOUNT, THREAD, "39001", false);
            assertTrue(restored.viewOnce);
            assertEquals("captured", restored.origin);
        }
    }

    @Test public void discoveredItemsWithoutBytesNeverCreateCatalogRows() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectCoverage coverage = new DirectCoverage(library);
            List<DirectCoverage.Discovered> all = thread(40);
            assertEquals(40, coverage.discover(INSTAGRAM, ACCOUNT, THREAD, new DirectCoverage.Page(all, false)));
            assertEquals(0, coverage.discover(INSTAGRAM, ACCOUNT, THREAD, new DirectCoverage.Page(all, false)));
            assertTrue(library.listAll(false).isEmpty());
            DirectCoverage.Report report = coverage.report(INSTAGRAM, ACCOUNT, null);
            assertEquals(40, report.pending);
            assertEquals(0, report.processed);
            assertFalse(report.complete());
        }
    }

    private static byte[] read(MediaLibrary library, String account, String thread, String key, boolean fromMe)
            throws IOException {
        LibraryItem item = library.lookup(INSTAGRAM, account, thread, key, fromMe);
        assertNotNull(item);
        return Files.readAllBytes(library.fileOf(item).toPath());
    }
}
