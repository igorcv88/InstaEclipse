package ps.reso.instaeclipse.mods.ghost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ViewOnceReadPolicyTest {

    @Test public void visualItemSeenStillBlocked() {
        assertTrue(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/visual_threads/123/item_seen/"));
        assertTrue(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/visual_threads/123/visual_items/456/seen/"));
    }

    @Test public void viewOnceReplayStillBlocked() {
        assertTrue(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/visual_threads/123/item_replayed/"));
    }

    @Test public void replayOutsideDirectNeverBlocked() {
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/media/123/item_replayed/"));
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/other/direct_v2/threads/123/item_replayed/"));
        assertTrue(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/threads/123/item_replayed/"));
    }

    @Test public void ordinaryThreadSeenNeverBlocked() {
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/threads/123/item_seen/"));
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/threads/123/opened/"));
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/threads/123/seen/"));
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/threads/123/items/456/item_seen/"));
    }

    @Test public void unrelatedSeenEndpointsNeverBlocked() {
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/media/seen/"));
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/inbox/"));
        assertFalse(ViewOnceReadPolicy.blocksPath(null));
    }

    @Test public void nonVisualThreadWithSimilarSuffixIsNotVisualMedia() {
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/some_threads/123/item_seen/"));
        assertFalse(ViewOnceReadPolicy.blocksPath("/api/v1/direct_v2/threads/123/visual_items/4/seen/"));
    }
}
