package ps.reso.instaeclipse.mods.ui;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;

/**
 * Per-adapter sort preference. Resolves the persisted mode before the first viewer-list
 * transformation, and never overwrites an in-memory choice when UI injection arrives late.
 */
final class StoryViewerSortModeCache {
    private final Map<Object, Integer> modes = new WeakHashMap<>();

    synchronized int resolve(Object adapter, IntSupplier persisted) {
        Integer current = modes.get(adapter);
        if (current != null) return current;
        if (persisted == null) return StoryViewerListOrder.INSTAGRAM;
        int mode = StoryViewerListOrder.validatedMode(persisted.getAsInt());
        modes.put(adapter, mode);
        return mode;
    }

    synchronized int selected(Object adapter) {
        Integer current = modes.get(adapter);
        return current != null ? current : StoryViewerListOrder.INSTAGRAM;
    }

    synchronized void select(Object adapter, int mode) {
        modes.put(adapter, StoryViewerListOrder.validatedMode(mode));
    }
}
