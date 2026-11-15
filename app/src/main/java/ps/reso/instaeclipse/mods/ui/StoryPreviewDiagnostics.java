package ps.reso.instaeclipse.mods.ui;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Read-only probe of the story tray's already inflated adapter and holder types.
 * Used to identify a safe path for previews before opening the story viewer.
 * Does not attach listeners, load media, fetch endpoints or send seen events.
 */
public final class StoryPreviewDiagnostics {
    private static final String TAG = "(IE|StoryPreviewDiag) ";
    private static final long INTERVAL_MS = 10000L;
    private static final Map<View, Long> LAST =
            Collections.synchronizedMap(new WeakHashMap<>());

    private StoryPreviewDiagnostics() {}

    public static void inspect(ViewGroup tray) {
        if (tray == null) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (LAST) {
            Long prev = LAST.get(tray);
            if (prev != null && now - prev < INTERVAL_MS) return;
            LAST.put(tray, now);
        }
        try {
            ArrayDeque<View> views = new ArrayDeque<>();
            views.add(tray);
            int seen = 0;
            while (!views.isEmpty() && seen++ < 60) {
                View v = views.removeFirst();
                Object adapter = null;
                try {
                    Method m = v.getClass().getMethod("getAdapter");
                    if (m.getParameterCount() == 0) adapter = m.invoke(v);
                } catch (Throwable ignored) {}
                if (adapter != null) {
                    StringBuilder fields = new StringBuilder();
                    for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(adapter.getClass())) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        if (fields.length() != 0) fields.append(',');
                        fields.append(f.getType().getSimpleName());
                        if (fields.length() > 180) break;
                    }
                    ModuleLog.line(TAG + "tray=" + tray.getClass().getName()
                            + " list=" + v.getClass().getName()
                            + " adapter=" + adapter.getClass().getName()
                            + " fieldTypes=" + fields);
                    return;
                }
                if (v instanceof ViewGroup group) {
                    for (int i = 0; i < group.getChildCount(); i++) views.addLast(group.getChildAt(i));
                }
            }
            ModuleLog.line(TAG + "tray present; no accessible adapter (views=" + seen + ")");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "inspection failed: " + t.getClass().getSimpleName());
        }
    }
}
