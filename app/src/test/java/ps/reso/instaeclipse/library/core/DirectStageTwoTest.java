package ps.reso.instaeclipse.library.core;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;
import static ps.reso.instaeclipse.library.core.LibraryFixture.*;

public final class DirectStageTwoTest {
    private static ImportRequest selection(String account, String thread, String sender, String id) {
        return DirectMessageSelection.match(INSTAGRAM, account, thread, account, thread, sender,
                id, id, "MEDIA", "MEDIA");
    }

    @Test public void nativeSelectionRequiresTheSameAccountThreadServerIdAndConventionalKind() {
        assertTrue(selection("111", "222", "111", "333").fromMe);
        assertFalse(selection("111", "222", "444", "333").fromMe);
        assertNull(DirectMessageSelection.match(INSTAGRAM, "111", "222", "444", "222", "444",
                "333", "333", "MEDIA", "MEDIA"));
        assertNull(DirectMessageSelection.match(INSTAGRAM, "111", "222", "111", "555", "444",
                "333", "333", "MEDIA", "MEDIA"));
        assertNull(DirectMessageSelection.match(INSTAGRAM, "111", "222", "111", "222", "444",
                "333", "444", "MEDIA", "MEDIA"));
        assertNull(selection("111", "222", "444", null));
        assertNull(DirectMessageSelection.match(INSTAGRAM, "111", "222", "111", "222", "444",
                "333", "333", "EXPIRING_MEDIA", "EXPIRING_MEDIA"));
        assertNull(DirectMessageSelection.match(INSTAGRAM, "111", "222", "111", "222", "444",
                "333", "333", "MEDIA", "VISUAL_MEDIA"));
    }

    @Test public void repeatedPagesAndRebuiltMessageIdentifiersDoNotDuplicateCopies() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            MediaLibrary restarted = fixture.open();
            for (String[] page : new String[][] {{"22", "23"}, {"21", "22"}, {"23", "24"}, {"21", "22"}}) {
                for (String id : page) {
                    ImportRequest reconstructed = selection("111", "222", "444", new String(id));
                    byte[] bytes = jpeg(Integer.parseInt(id));
                    MediaLibrary.ImportResult result = library.importMedia(reconstructed, source(bytes));
                    assertEquals(result.item.uuid, restarted.lookup(INSTAGRAM, "111", "222", id, false).uuid);
                }
            }
            assertEquals(4, restarted.listChat(INSTAGRAM, "111", "222").size());
            assertNull(restarted.lookup(INSTAGRAM, "222", "111", "22", false));
            assertNull(restarted.lookup(INSTAGRAM, "111", "333", "22", false));
            // An identity that is absent from the newest page is still available from disk.
            LibraryItem absentFromPage = restarted.lookup(INSTAGRAM, "111", "222", "24", false);
            assertArrayEquals(jpeg(24), java.nio.file.Files.readAllBytes(restarted.fileOf(absentFromPage).toPath()));
        }
    }

    @Test public void interruptionAndReplayPreserveOnlyCommittedRowsAndReportUnknownLinks() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            ImportRequest scope = ImportRequest.forConversation(INSTAGRAM, "111", "222");
            MediaLibrary library = fixture.open();
            library.importMedia(scope, source(png(1)));
            assertThrows(IOException.class, () -> library.importMedia(scope, () -> {
                throw new IOException("interrupted before the next commit");
            }));
            MediaLibrary restarted = fixture.open();
            assertEquals(MediaLibrary.ImportStatus.ALREADY_PRESENT, restarted.importMedia(scope, source(png(1))).status);
            assertEquals(1, restarted.listChat(INSTAGRAM, "111", "222").size());
            BatchCoverage partial = BatchCoverage.measure(5, false, 9, 2, Arrays.asList(
                    "CREATED", "ALREADY_PRESENT", "INCOMPLETE_MEDIA"));
            assertFalse(partial.complete());
            assertEquals(2, partial.unknownMessage());
            assertEquals(1, partial.failed());
            BatchCoverage finished = BatchCoverage.measure(1, true, 1, 0, Arrays.asList("ALREADY_PRESENT"));
            assertTrue(finished.complete());
            assertEquals(0, finished.unknownMessage());
        }
    }

    @Test public void explicitAssociationPreservesPermanentCopiesAndHidesConflicts() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            ImportRequest scope = selection("111", "222", "444", "333");
            LibraryItem sameThread = library.importMedia(ImportRequest.forConversation(INSTAGRAM, "111", "222"),
                    source(jpeg(12))).item;
            LibraryItem otherAccount = library.importMedia(ImportRequest.forConversation(INSTAGRAM, "444", "222"),
                    source(jpeg(13))).item;
            assertFalse(library.associateMessage(otherAccount.uuid, scope));
            assertTrue(library.associateMessage(sameThread.uuid, scope));
            assertEquals(sameThread.uuid, library.lookup(INSTAGRAM, "111", "222", "333", false).uuid);
            LibraryItem unrelated = library.importMedia(ImportRequest.localFile(), source(jpeg(14))).item;
            assertTrue(library.associateMessage(unrelated.uuid, scope));
            assertNull(library.lookup(INSTAGRAM, "111", "222", "333", false));
            assertEquals(2, library.listChat(INSTAGRAM, "111", "222").size());
        }
    }
}
