package ps.reso.instaeclipse.library.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static ps.reso.instaeclipse.library.core.LibraryFixture.*;

/** Regressions for the accepted findings of the independent review of PR #6/#7 (2026-11-27). */
public final class DirectReviewFixesTest {
    private static final String ME = "1001";
    private static final String PEER = "2002";
    private static final String THREAD = "340282366841710300949128000000002";

    private static String cdn(String name) {
        return "https://scontent-gru1-1.cdninstagram.com/v/" + name + ".jpg?oe=1";
    }

    private static DirectConsolidation.Offer offer(String id, String url) {
        return new DirectConsolidation.Offer(ME, THREAD, new DirectCoverage.Discovered(id, false, "image", false, 1), url);
    }

    private static final class Cdn implements DirectConsolidation.Fetcher {
        final Map<String, byte[]> objects = new HashMap<>();
        final List<String> requested = new ArrayList<>();
        int failNext;

        @Override public InputStream open(String url) throws IOException {
            requested.add(url);
            if (failNext > 0) {
                failNext--;
                throw new IOException("reset");
            }
            byte[] bytes = objects.get(url);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }

    @Test public void aCutTransferStaysRetryableInsteadOfBeingRefusedForGood() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectConsolidation consolidation = new DirectConsolidation(library);
            Cdn cdn = new Cdn();
            byte[] whole = jpeg(51001);
            cdn.objects.put(cdn("cut"), Arrays.copyOf(whole, whole.length / 2));
            consolidation.offer(INSTAGRAM, ME, THREAD, List.of(offer("51001", cdn("cut"))), true);
            DirectConsolidation.RunReport first = consolidation.run(INSTAGRAM, cdn, 10, () -> false);
            assertEquals(1, first.failed);
            assertEquals(DirectCoverage.State.FAILED,
                    consolidation.coverage().stateOf(INSTAGRAM, ME, THREAD, "51001", false));
            cdn.objects.put(cdn("cut"), whole);
            assertEquals(1, consolidation.run(INSTAGRAM, cdn, 10, () -> false).imported);
            assertNotNull(library.lookup(INSTAGRAM, ME, THREAD, "51001", false));
        }
    }

    @Test public void aPauseMidTransferRecordsNothingAndCostsNoAttempt() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectConsolidation consolidation = new DirectConsolidation(library);
            byte[] video = mp4(52001, 400_000);
            AtomicBoolean paused = new AtomicBoolean();
            AtomicInteger reads = new AtomicInteger();
            DirectConsolidation.Fetcher slow = url -> new ByteArrayInputStream(video) {
                @Override public synchronized int read(byte[] b, int off, int len) {
                    if (reads.incrementAndGet() == 3) paused.set(true); // the user taps Pause mid-file
                    return super.read(b, off, Math.min(len, 4096));
                }
            };
            consolidation.offer(INSTAGRAM, ME, THREAD, List.of(new DirectConsolidation.Offer(ME, THREAD,
                    new DirectCoverage.Discovered("52001", false, "video", false, 1), cdn("v"))), true);
            DirectConsolidation.RunReport report = consolidation.run(INSTAGRAM, slow, 10, paused::get);
            assertTrue(report.cancelled);
            assertEquals(0, report.attempted);
            assertTrue("stopped well before the end of the file", reads.get() < 10);
            assertEquals(DirectCoverage.State.PENDING,
                    consolidation.coverage().stateOf(INSTAGRAM, ME, THREAD, "52001", false));
            assertEquals(0L, Sql.Rows.getLong(library.sql().query("SELECT attempts FROM direct_items").get(0), "attempts"));
            assertTrue(library.blobs().listBlobs().isEmpty());
            File staging = new File(fixture.libraryDir(), "staging");
            File[] left = staging.listFiles(f -> f.isFile());
            assertTrue(left == null || left.length == 0);
            paused.set(false);
            assertEquals(1, consolidation.run(INSTAGRAM, url -> new ByteArrayInputStream(video), 10, paused::get).imported);
        }
    }

    @Test public void thePauseSurvivesAFreshProcessUntilAnExplicitContinue() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            new DirectConsolidation(fixture.open()).setPaused(true);
            DirectConsolidation restarted = new DirectConsolidation(fixture.open());
            assertTrue(restarted.isPaused());
            restarted.setPaused(false);
            assertFalse(new DirectConsolidation(fixture.open()).isPaused());
        }
    }

    @Test public void anExplicitContinueRetriesItemsPastTheAttemptLimit() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectConsolidation consolidation = new DirectConsolidation(library);
            Cdn cdn = new Cdn();
            cdn.objects.put(cdn("r"), jpeg(53001));
            consolidation.offer(INSTAGRAM, ME, THREAD, List.of(offer("53001", cdn("r"))), true);
            cdn.failNext = DirectCoverage.MAX_ATTEMPTS;
            for (int i = 0; i < DirectCoverage.MAX_ATTEMPTS; i++) consolidation.run(INSTAGRAM, cdn, 10, () -> false);
            assertEquals(0, consolidation.run(INSTAGRAM, cdn, 10, () -> false).attempted);
            assertEquals(1, consolidation.resetFailures(INSTAGRAM));
            assertEquals(1, consolidation.run(INSTAGRAM, cdn, 10, () -> false).imported);
        }
    }

    @Test public void coverageReportsCurrentAvailabilityAndALostCopyIsRepairedFromTheSameMessage() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectConsolidation consolidation = new DirectConsolidation(library);
            Cdn cdn = new Cdn();
            cdn.objects.put(cdn("lost"), jpeg(54001));
            cdn.objects.put(cdn("binned"), jpeg(54002));
            consolidation.offer(INSTAGRAM, ME, THREAD, List.of(offer("54001", cdn("lost")), offer("54002", cdn("binned"))), true);
            assertEquals(2, consolidation.run(INSTAGRAM, cdn, 10, () -> false).imported);

            LibraryItem lost = library.lookup(INSTAGRAM, ME, THREAD, "54001", false);
            LibraryItem binned = library.lookup(INSTAGRAM, ME, THREAD, "54002", false);
            assertTrue(library.fileOf(lost).delete());
            library.reconcile(false);
            library.trash(binned.uuid);
            DirectCoverage.ThreadReport t = consolidation.coverage().report(INSTAGRAM, ME, THREAD).threads.get(0);
            assertEquals(0, t.keptAvailable);
            assertEquals(1, t.keptDamaged);
            assertEquals(1, t.keptTrashed);
            assertFalse("a lost copy is not complete coverage", t.complete());

            // Nothing is fetched again until a later scan offers a fresh URL.
            int before = cdn.requested.size();
            consolidation.run(INSTAGRAM, cdn, 10, () -> false);
            assertEquals(before, cdn.requested.size());
            fixture.clock.addAndGet(60_000);
            consolidation.offer(INSTAGRAM, ME, THREAD, List.of(offer("54001", cdn("lost")), offer("54002", cdn("binned"))), true);
            consolidation.run(INSTAGRAM, cdn, 10, () -> false);
            assertEquals("only the lost copy is fetched; the trashed one is the user's decision",
                    before + 1, cdn.requested.size());
            LibraryItem repaired = library.lookup(INSTAGRAM, ME, THREAD, "54001", false);
            assertEquals(lost.uuid, repaired.uuid);
            assertTrue(repaired.isAvailable());
            t = consolidation.coverage().report(INSTAGRAM, ME, THREAD).threads.get(0);
            assertEquals(1, t.keptAvailable);
            assertEquals(0, t.keptDamaged);
            assertTrue(t.complete());
        }
    }

    @Test public void aRepairWithAnExpiredUrlIsNotRetriedUntilTheNextScan() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            DirectConsolidation consolidation = new DirectConsolidation(library);
            Cdn cdn = new Cdn();
            cdn.objects.put(cdn("x"), jpeg(55001));
            consolidation.offer(INSTAGRAM, ME, THREAD, List.of(offer("55001", cdn("x"))), true);
            consolidation.run(INSTAGRAM, cdn, 10, () -> false);
            assertTrue(library.fileOf(library.lookup(INSTAGRAM, ME, THREAD, "55001", false)).delete());
            library.reconcile(false);
            cdn.objects.clear(); // the URL expired
            fixture.clock.addAndGet(60_000);
            consolidation.offer(INSTAGRAM, ME, THREAD, List.of(offer("55001", cdn("x"))), true);
            fixture.clock.addAndGet(60_000);
            for (int i = 0; i < 3; i++) consolidation.run(INSTAGRAM, cdn, 10, () -> false);
            assertEquals(2, cdn.requested.size());
            assertEquals(DirectCoverage.State.IMPORTED,
                    consolidation.coverage().stateOf(INSTAGRAM, ME, THREAD, "55001", false));
        }
    }

    @Test public void largeCarouselPagesAreSplitIntoAcceptedBatches() throws Exception {
        List<DirectStoreRow> rows = new ArrayList<>();
        for (int r = 0; r < 50; r++) {
            JSONArray slides = new JSONArray();
            for (int i = 0; i < 20; i++) {
                slides.put(new JSONObject().put("media_type", 1).put("image_versions2", new JSONObject()
                        .put("candidates", new JSONArray().put(new JSONObject().put("url", cdn(r + "_" + i))
                                .put("width", 10).put("height", 10)))));
            }
            String id = "560" + r;
            rows.add(DirectStoreRow.parse(ME, THREAD, id, "media", 1, new JSONObject().put("item_id", id)
                    .put("item_type", "media").put("user_id", PEER)
                    .put("media", new JSONObject().put("media_type", 8).put("carousel_media", slides)).toString()));
        }
        List<List<DirectStoreRow>> batches = DirectStoreRow.batches(rows, DirectConsolidation.MAX_OFFER_PARTS);
        int total = 0;
        for (List<DirectStoreRow> batch : batches) {
            List<String> keys = new ArrayList<>(), kinds = new ArrayList<>(), urls = new ArrayList<>(), flags = new ArrayList<>();
            List<Long> ts = new ArrayList<>();
            for (DirectStoreRow row : batch) for (DirectStoreRow.Part part : row.parts) {
                keys.add(part.identity.messageKey); kinds.add(part.identity.mediaKind);
                urls.add(part.best.url); flags.add("00"); ts.add(1L);
            }
            long[] stamps = ts.stream().mapToLong(Long::longValue).toArray();
            assertNotNull("every batch must pass the module's decoder",
                    DirectConsolidation.decode(ME, THREAD, keys, kinds, urls, flags, stamps));
            total += keys.size();
        }
        assertEquals(1000, total);
        assertEquals(5, batches.size());
    }
}
