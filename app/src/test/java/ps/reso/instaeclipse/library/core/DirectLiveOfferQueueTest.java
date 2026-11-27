package ps.reso.instaeclipse.library.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class DirectLiveOfferQueueTest {
    private static DirectStoreRow row(String account, String thread, String suffix) {
        return DirectStoreRow.parse(account, thread, "42", "media", 1000,
                """
                {"item_id":"42","item_type":"media","user_id":"100",
                 "media":{"media_type":1,"image_versions2":{"candidates":[
                   {"url":"https://fixture.cdninstagram.com/%s","width":2,"height":2}
                 ]}}}
                """.formatted(suffix));
    }

    @Test public void unacknowledgedRowsAreRetriedWithoutAnotherBind() {
        DirectLiveOfferQueue queue = new DirectLiveOfferQueue();
        DirectStoreRow first = row("100", "200", "old");
        DirectStoreRow nextPage = row("100", "200", "second");
        assertEquals(DirectLiveOfferQueue.Add.QUEUED, queue.add(first));
        List<DirectLiveOfferQueue.Entry> firstAttempt = queue.snapshot();
        assertEquals(0, queue.finish(firstAttempt, false));
        assertEquals(firstAttempt, queue.snapshot());
        assertEquals(DirectLiveOfferQueue.Add.DUPLICATE, queue.add(first));
        assertEquals(DirectLiveOfferQueue.Add.QUEUED, queue.add(nextPage));
        queue.finish(firstAttempt, true);
        assertSame(nextPage, queue.snapshot().get(0).row);
        queue.finish(queue.snapshot(), true);
        assertFalse(queue.hasPending());
        assertEquals(DirectLiveOfferQueue.Add.DUPLICATE, queue.add(nextPage));
    }

    @Test public void deliveryAndDeduplicationAreScopedByAccountAndThread() {
        DirectLiveOfferQueue queue = new DirectLiveOfferQueue();
        assertEquals(DirectLiveOfferQueue.Add.QUEUED, queue.add(row("100", "200", "same")));
        assertEquals(DirectLiveOfferQueue.Add.QUEUED, queue.add(row("100", "201", "same")));
        assertEquals(DirectLiveOfferQueue.Add.QUEUED, queue.add(row("101", "200", "same")));
        assertEquals(3, queue.snapshot().size());
    }

    @Test public void aPagePlaceholderDoesNotReplaceAQueuedSource() {
        DirectLiveOfferQueue queue = new DirectLiveOfferQueue();
        DirectStoreRow available = row("100", "200", "ready");
        DirectStoreRow placeholder = DirectStoreRow.parse("100", "200", "42", "media", 1000,
                "{\"item_id\":\"42\",\"item_type\":\"media\",\"user_id\":\"100\"}");
        assertEquals(DirectLiveOfferQueue.Add.QUEUED, queue.add(available));
        assertEquals(DirectLiveOfferQueue.Add.DUPLICATE, queue.add(placeholder));
        assertSame(available, queue.snapshot().get(0).row);
    }

    @Test public void exhaustedRowsCanBeRetriedByAFutureBind() {
        DirectLiveOfferQueue queue = new DirectLiveOfferQueue();
        DirectStoreRow row = row("100", "200", "unavailable");
        queue.add(row);
        List<DirectLiveOfferQueue.Entry> pending = queue.snapshot();
        for (int attempt = 0; attempt < 3; attempt++) assertEquals(0, queue.finish(pending, false));
        assertEquals(1, queue.finish(pending, false));
        assertFalse(queue.hasPending());
        assertEquals(DirectLiveOfferQueue.Add.QUEUED, queue.add(row));
    }
    @Test public void complementaryPartialCarouselsPreserveBothSources() {
        DirectLiveOfferQueue queue = new DirectLiveOfferQueue();
        String template = """
                {"item_id":"42","item_type":"media","user_id":"100",
                 "media":{"media_type":8,"carousel_media":[%s,%s]}}
                """;
        String image = """
                {"media_type":1,"image_versions2":{"candidates":[
                   {"url":"https://fixture.cdninstagram.com/%s","width":2,"height":2}
                 ]}}
                """;
        queue.add(DirectStoreRow.parse("100", "200", "42", "media", 1,
                template.formatted(image.formatted("left"), "{}")));
        queue.add(DirectStoreRow.parse("100", "200", "42", "media", 1,
                template.formatted("{}", image.formatted("right"))));
        List<DirectStoreRow.Part> parts = queue.snapshot().get(0).row.parts;
        assertEquals(2, parts.size());
        assertTrue(parts.get(0).fetchAllowed);
        assertTrue(parts.get(1).fetchAllowed);
    }

}
