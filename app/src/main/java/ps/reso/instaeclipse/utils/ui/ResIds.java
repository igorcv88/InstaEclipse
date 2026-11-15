package ps.reso.instaeclipse.utils.ui;

import android.annotation.SuppressLint;
import android.content.Context;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves IG resource ids by name once per process and caches the result (including 0 = not found),
 * so hot-path hooks compare ints instead of calling {@code Resources.getIdentifier} each time.
 * Only valid inside the hooked app (one Instagram package per process).
 */
public final class ResIds {

    private static final ConcurrentHashMap<String, Integer> IDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> DRAWABLES = new ConcurrentHashMap<>();

    private ResIds() {}

    /** {@code R.id.<name>} of the app owning {@code ctx}, or 0 if it doesn't exist. */
    public static int id(Context ctx, String name) {
        return resolve(IDS, ctx, name, "id");
    }

    /** {@code R.drawable.<name>} of the app owning {@code ctx}, or 0 if it doesn't exist. */
    public static int drawable(Context ctx, String name) {
        return resolve(DRAWABLES, ctx, name, "drawable");
    }

    @SuppressLint("DiscouragedApi")
    private static int resolve(ConcurrentHashMap<String, Integer> cache, Context ctx,
                               String name, String type) {
        Integer cached = cache.get(name);
        if (cached != null) return cached;
        if (ctx == null) return 0; // don't cache: a later call with a context can still resolve
        int id;
        try {
            id = ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
        } catch (Throwable t) {
            id = 0;
        }
        cache.put(name, id);
        return id;
    }
}
