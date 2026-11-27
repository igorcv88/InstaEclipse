package ps.reso.instaeclipse.library.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;
import static ps.reso.instaeclipse.library.core.LibraryFixture.*;

public final class DirectLibraryTest {
    @Test public void identicalBytesShareABlobWithoutMergingMessagesOrScopes() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            byte[] photo = jpeg(12);
            LibraryItem one = library.importMedia(request("thread-1", "first").build(), source(photo)).item;
            LibraryItem two = library.importMedia(request("thread-1", "second").build(), source(photo)).item;
            LibraryItem elsewhere = library.importMedia(request("thread-2", "first").build(), source(photo)).item;
            LibraryItem other = library.importMedia(request("thread-1", "first").account(OTHER).build(), source(photo)).item;
            assertNotEquals(one.uuid, two.uuid);
            assertEquals(one.sha256, two.sha256);
            assertEquals(photo.length, library.usedBytes());
            assertEquals(one.uuid, library.lookup(INSTAGRAM, ME, "thread-1", "first", false).uuid);
            assertEquals(elsewhere.uuid, library.lookup(INSTAGRAM, ME, "thread-2", "first", false).uuid);
            assertEquals(other.uuid, library.lookup(INSTAGRAM, OTHER, "thread-1", "first", false).uuid);
            assertNull(library.lookup(INSTAGRAM, OTHER, "thread-2", "first", false));
        }
    }

    @Test public void aCollisionHidesTheMessageLookupAndKeepsBothCopies() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            ImportRequest key = request("thread-1", "first").build();
            LibraryItem one = library.importMedia(key, source(jpeg(12))).item;
            LibraryItem two = library.importMedia(key, source(jpeg(13))).item;
            assertNotEquals(one.uuid, two.uuid);
            assertNull(library.lookup(INSTAGRAM, ME, "thread-1", "first", false));
            assertEquals(1, LibraryIntegrity.findAmbiguities(library).size());
            assertTrue(library.trash(two.uuid));
            assertEquals(one.uuid, library.lookup(INSTAGRAM, ME, "thread-1", "first", false).uuid);
            assertTrue(library.restoreFromTrash(two.uuid));
            assertNull(library.lookup(INSTAGRAM, ME, "thread-1", "first", false));
        }
    }

    @Test public void repeatAndConversationLinkNeverInventAMessageId() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            ImportRequest local = ImportRequest.localFile();
            byte[] photo = png(5);
            LibraryItem item = library.importMedia(local, source(photo)).item;
            assertNull(item.messageKey);
            assertTrue(item.threadKey.isEmpty());
            assertEquals(MediaLibrary.ImportStatus.ALREADY_PRESENT, library.importMedia(local, source(photo)).status);
            long before = library.generation();
            assertTrue(library.associate(item.uuid, INSTAGRAM, ME, "thread-1"));
            assertFalse(library.associate(item.uuid, INSTAGRAM, OTHER, "thread-2"));
            assertTrue(library.generation() > before);
            LibraryItem linked = library.listChat(INSTAGRAM, ME, "thread-1").get(0);
            assertNull(linked.messageKey);
            assertEquals(item.uuid, linked.uuid);
            assertTrue(library.listChat(INSTAGRAM, OTHER, "thread-1").isEmpty());
        }
    }

    @Test public void invalidAndTruncatedBytesCannotCreateCatalogRows() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            byte[] photo = png(0);
            library.importMedia(ImportRequest.localFile(), source(photo));
            for (byte[] invalid : new byte[][] { {}, "<html>png</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    Arrays.copyOf(photo, photo.length - 2) }) {
                assertThrows(LibraryException.class, () ->
                        library.importMedia(ImportRequest.localFile(), source(invalid)));
            }
            library.setDecodeProbe((file, mime) -> "cannot_decode");
            assertThrows(LibraryException.class, () ->
                    library.importMedia(ImportRequest.localFile(), source(jpeg(21))));
            assertEquals(1, library.listAll(false).size());
            assertEquals(1, library.blobs().listBlobs().size());
        }
    }

    @Test public void liveStagingSurvivesRecoveryAndCommitsAfterwards() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            byte[] photo = jpeg(17);
            try (BlobStore.StageSession session = library.blobs().beginStaging()) {
                BlobStore.Staged staged = library.blobs().stage(new ByteArrayInputStream(photo), 8192, session);
                assertEquals(0, fixture.open().reconcile(false).stagingRemoved);
                assertTrue(staged.file.isFile());
                staged.discard();
            }
        }
    }

    @Test public void aStreamThatFailsOnCloseLeavesNoStagingOrActiveItem() throws Exception {
        try (LibraryFixture fixture = new LibraryFixture()) {
            MediaLibrary library = fixture.open();
            assertThrows(IOException.class, () -> library.importMedia(ImportRequest.localFile(),
                    () -> new ByteArrayInputStream(jpeg(19)) {
                        @Override public void close() throws IOException { throw new IOException("close failed"); }
                    }));
            assertTrue(library.listAll(false).isEmpty());
            assertEquals(0, LibraryIntegrity.inspect(library, false).stagingFiles);
        }
    }
}
