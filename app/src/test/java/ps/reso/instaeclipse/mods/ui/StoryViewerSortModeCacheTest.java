package ps.reso.instaeclipse.mods.ui;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class StoryViewerSortModeCacheTest {
    @Test public void firstBuilderLoadsSavedSortBeforeUiIsCreated() {
        StoryViewerSortModeCache cache = new StoryViewerSortModeCache();
        Object adapter = new Object();
        AtomicInteger reads = new AtomicInteger();
        assertEquals(StoryViewerListOrder.NAME_ZA,
                cache.resolve(adapter, () -> { reads.incrementAndGet(); return 2; }));
        assertEquals(StoryViewerListOrder.NAME_ZA, cache.selected(adapter));
        assertEquals(StoryViewerListOrder.NAME_ZA, cache.resolve(adapter, () -> {
            reads.incrementAndGet();
            return 0;
        }));
        assertEquals(1, reads.get());
    }

    @Test public void lateUiLoadNeverRevertsAnExplicitUserSelection() {
        StoryViewerSortModeCache cache = new StoryViewerSortModeCache();
        Object adapter = new Object();
        cache.resolve(adapter, () -> 1);
        cache.select(adapter, StoryViewerListOrder.NAME_ZA);
        assertEquals(StoryViewerListOrder.NAME_ZA, cache.resolve(adapter, () -> 1));
    }

    @Test public void missingContextDoesNotCacheFallbackOrder() {
        StoryViewerSortModeCache cache = new StoryViewerSortModeCache();
        Object adapter = new Object();
        assertEquals(0, cache.resolve(adapter, null));
        assertEquals(1, cache.resolve(adapter, () -> 1));
    }

    @Test public void invalidPreferenceDefaultsToInstagram() {
        StoryViewerSortModeCache cache = new StoryViewerSortModeCache();
        assertEquals(0, cache.resolve(new Object(), () -> -10));
    }

    @Test public void eachAdapterHasItsOwnState() {
        StoryViewerSortModeCache cache = new StoryViewerSortModeCache();
        Object first = new Object(), second = new Object();
        cache.select(first, 1);
        assertEquals(1, cache.selected(first));
        assertEquals(2, cache.resolve(second, () -> 2));
    }
}
