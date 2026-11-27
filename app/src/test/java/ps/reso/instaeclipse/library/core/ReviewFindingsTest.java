package ps.reso.instaeclipse.library.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;

import static org.junit.Assert.*;
import static ps.reso.instaeclipse.library.core.LibraryFixture.*;

/** Regressions for the PR #6 review findings on restore quota and shallow integrity reports. */
public final class ReviewFindingsTest {
    private static void corrupt(MediaLibrary library, LibraryItem item, byte[] original) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(library.fileOf(item), "rw")) {
            file.seek(10);
            file.write(original[10] ^ 0x01);
        }
    }

    @Test public void restoringTheHealthyCopyOfACorruptBlobIsNotChargedTwice() throws Exception {
        try (LibraryFixture source = new LibraryFixture(); LibraryFixture target = new LibraryFixture()) {
            long payload = 40L * 1024 * 1024; // used 40 MiB; a double count (80 MiB) exceeds the 64 MiB quota
            MediaLibrary from = source.open();
            from.importMedia(request("q-thread", "Q1").build(), largeMp4(77, payload));
            ByteArrayOutputStream backup = new ByteArrayOutputStream();
            char[] password = "pw".toCharArray();
            new LibraryBackup(from).export(backup, password, 1000, 1 << 20);

            MediaLibrary to = target.open();
            LibraryItem item = to.importMedia(request("q-thread", "Q1").build(), largeMp4(77, payload)).item;
            try (RandomAccessFile file = new RandomAccessFile(to.fileOf(item), "rw")) {
                file.seek(item.sizeBytes / 2);
                int b = file.read();
                file.seek(item.sizeBytes / 2);
                file.write(b ^ 0x01);
            }
            assertEquals(1, to.reconcile(true).markedCorrupt);
            to.setQuotaBytes(MediaLibrary.MIN_QUOTA_BYTES);
            new LibraryBackup(to).restore(new ByteArrayInputStream(backup.toByteArray()), password, 1000);
            assertEquals(0, to.reconcile(true).markedCorrupt);
            assertTrue(to.lookup(INSTAGRAM, ME, "q-thread", "Q1", false).isAvailable());
        }
    }

    @Test public void shallowInspectionReportsBlobsAlreadyKnownToBeCorrupt() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            byte[] photo = jpeg(4343);
            MediaLibrary library = fixture.open();
            LibraryItem item = library.importMedia(request("i-thread", "I1").build(), source(photo)).item;
            corrupt(library, item, photo);
            assertEquals(1, library.reconcile(true).markedCorrupt);
            LibraryIntegrity.Report report = LibraryIntegrity.inspect(library, false);
            assertTrue(report.damaged.contains(item.uuid));
        }
    }
}
