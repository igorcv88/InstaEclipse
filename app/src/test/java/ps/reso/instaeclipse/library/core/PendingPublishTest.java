package ps.reso.instaeclipse.library.core;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public final class PendingPublishTest {
    private static final class FakeStore implements PendingPublish.Store<String> {
        final List<String> events = new ArrayList<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        int publishResult = 1;
        boolean failWrite;
        boolean failClose;
        boolean failInsert;
        boolean failUpdate;

        @Override
        public String createPending() {
            events.add("create");
            return failInsert ? null : "entry";
        }

        @Override
        public OutputStream open(String entry) {
            events.add("open");
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    if (failWrite) throw new IOException("disk full");
                    written.write(b);
                }

                @Override
                public void close() throws IOException {
                    events.add("close");
                    if (failClose) throw new IOException("commit failed on close");
                }
            };
        }

        @Override
        public int publish(String entry) {
            events.add("publish");
            if (failUpdate) throw new IllegalStateException("update error");
            return publishResult;
        }

        @Override
        public void delete(String entry) {
            events.add("delete");
        }
    }

    @Test
    public void exportPublishesOnlyAfterTheStreamIsClosed() throws Exception {
        FakeStore store = new FakeStore();
        byte[] photo = LibraryFixture.jpeg(9);
        assertEquals("entry", PendingPublish.save(store, new ByteArrayInputStream(photo)));
        assertEquals(Arrays.asList("create", "open", "close", "publish"), store.events);
        assertArrayEquals(photo, store.written.toByteArray());
    }

    @Test
    public void exportFailsAndCleansUpWhenPublishWriteOrCloseFails() throws Exception {
        for (String mode : new String[] { "publish0", "write", "close" }) {
            FakeStore store = new FakeStore();
            if (mode.equals("publish0")) store.publishResult = 0;
            if (mode.equals("write")) store.failWrite = true;
            if (mode.equals("close")) store.failClose = true;
            try {
                PendingPublish.save(store, new ByteArrayInputStream(LibraryFixture.jpeg(10)));
                fail(mode + " must fail");
            } catch (IOException expected) {
                // reported as a failure, never as success
            }
            assertEquals(mode, "delete", store.events.get(store.events.size() - 1));
            if (!mode.equals("publish0")) assertFalse(mode + " never publishes", store.events.contains("publish"));
        }
    }

    @Test public void rejectedInsertDoesNotTryToOpenOrDelete() {
        FakeStore store = new FakeStore();
        store.failInsert = true;
        assertThrows(IOException.class, () -> PendingPublish.save(store, new ByteArrayInputStream(new byte[]{1})));
        assertEquals(java.util.Collections.singletonList("create"), store.events);
    }

    @Test public void thrownUpdateCleansOnlyThePendingEntryAfterClose() {
        FakeStore store = new FakeStore();
        store.failUpdate = true;
        assertThrows(IllegalStateException.class,
                () -> PendingPublish.save(store, new ByteArrayInputStream(new byte[]{1})));
        assertEquals(Arrays.asList("create", "open", "close", "publish", "delete"), store.events);
    }
}
