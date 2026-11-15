package ps.reso.instaeclipse.mods.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Opt-in, metadata-only probe to determine whether the viewer adapter receives per-user
 * time updates or just reordered rows. It does not perform extra requests or modify results.
 *
 * The pseudonym salt is regenerated on every process start. No user names, media IDs,
 * story URLs, tokens or response bodies are written to logs.
 */
public final class StoryViewerDiagnosticsHook {
    private static final String TAG = "(IE|ViewerDiag) ";
    private static final long MIN_INTERVAL_MS = 2500;
    private static final int MAX_ROWS = 150;
    private static final int MAX_DETAILS = 35;
    private static final byte[] SALT = new byte[24];
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Snapshot> SNAPSHOTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    static {
        new SecureRandom().nextBytes(SALT);
    }

    private static final class ViewerBatch {
        final int totalRows;
        final int inspected;
        final Map<String, String> entries;

        ViewerBatch(int totalRows, int inspected, Map<String, String> entries) {
            this.totalRows = totalRows;
            this.inspected = inspected;
            this.entries = entries;
        }
    }

    private static final class Snapshot {
        Map<String, String> entries = new HashMap<>();
        final StoryViewerSnapshotThrottle<ViewerBatch> throttle = new StoryViewerSnapshotThrottle<>(
                MIN_INTERVAL_MS, SystemClock::elapsedRealtime,
                (runnable, delay) -> MAIN.postDelayed(runnable, delay),
                batch -> emit(this, batch));
    }

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> adapter = null;
            Class<?> viewerRecord = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("search_result_rendered")))) {
                adapter = cl.loadClass(md.getClassName());
                break;
            }
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("XDTViewerFeedbackDict")))) {
                viewerRecord = cl.loadClass(md.getClassName());
                break;
            }
            if (adapter == null || viewerRecord == null) {
                ModuleLog.line(TAG + "adapter/record unresolved; probe inactive");
                return;
            }
            Method builder = null;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(adapter)) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 4 && p[0] == List.class
                        && p[1] == boolean.class && p[2] == boolean.class && p[3] == boolean.class) {
                    builder = m;
                }
            }
            if (builder == null) {
                ModuleLog.line(TAG + "builder unresolved; probe inactive");
                return;
            }
            final Class<?> recordClass = viewerRecord;
            builder.setAccessible(true);
            XposedBridge.hookMethod(builder, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.storyViewerDiagnostics || !(param.args[0] instanceof List<?> rows)) return;
                    inspect(param.thisObject, rows, recordClass);
                }
            });
            ModuleLog.line(TAG + "probe armed; enable 'Story viewer diagnostics' to capture metadata");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "probe installation failed: " + t.getClass().getSimpleName());
        }
    }

    private static void inspect(Object adapter, List<?> rows, Class<?> viewerRecord) {
        if (adapter == null) return;
        try {
            // Capture metadata now, before Instagram can recycle/mutate the row list.
            // The delayed job holds only salted IDs, positions and timestamp-shaped fields.
            ViewerBatch batch = capture(rows, viewerRecord);
            Snapshot snap;
            synchronized (SNAPSHOTS) {
                snap = SNAPSHOTS.get(adapter);
                if (snap == null) {
                    snap = new Snapshot();
                    SNAPSHOTS.put(adapter, snap);
                }
            }
            snap.throttle.offer(batch);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "inspect failed: " + t.getClass().getSimpleName());
        }
    }

    private static ViewerBatch capture(List<?> rows, Class<?> viewerRecord) throws Exception {
        Map<String, String> seen = new LinkedHashMap<>();
        int viewerIndex = 0;
        for (Object row : rows) {
            if (row == null || !viewerRecord.isInstance(row)) continue;
            if (viewerIndex >= MAX_ROWS) break;
            String username = username(row);
            if (username != null && !username.isEmpty()) {
                String id = pseudonym(username);
                seen.put(id, viewerIndex + ":" + candidateTimestamps(row));
            }
            viewerIndex++;
        }
        return new ViewerBatch(rows.size(), viewerIndex, seen);
    }

    private static void emit(Snapshot snap, ViewerBatch batch) {
        // Never leak delayed diagnostic observations after the user turns the feature off.
        if (!FeatureFlags.storyViewerDiagnostics) return;
        synchronized (snap) {
            List<String> details = new ArrayList<>();
            int changed = 0;
            for (Map.Entry<String, String> entry : batch.entries.entrySet()) {
                String id = entry.getKey();
                String current = entry.getValue();
                String old = snap.entries.get(id);
                if (old == null) {
                    changed++;
                    if (details.size() < MAX_DETAILS)
                        details.add("first id=" + id + " current=" + current);
                } else if (!old.equals(current)) {
                    changed++;
                    if (details.size() < MAX_DETAILS)
                        details.add("changed id=" + id + " prior=" + old + " current=" + current);
                }
            }
            snap.entries = batch.entries;
            ModuleLog.line(TAG + "snapshot rows=" + batch.totalRows + " inspected=" + batch.inspected
                    + " tracked=" + batch.entries.size() + " changes=" + changed
                    + " (timestamps are candidates, not proven view times)");
            for (String detail : details) ModuleLog.line(TAG + detail);
        }
    }

    private static String username(Object record) {
        try {
            for (Class<?> c = record.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers())
                            || !f.getType().getName().equals("com.instagram.user.model.User")) continue;
                    f.setAccessible(true);
                    return UserUtils.callUsernameGetter(f.get(record));
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String pseudonym(String username) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(SALT);
        byte[] bytes = digest.digest(username.toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(12);
        for (int i = 0; i < 6; i++) {
            int b = bytes[i] & 0xff;
            out.append(Character.forDigit(b >>> 4, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static String candidateTimestamps(Object record) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        try {
            for (Class<?> c = record.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> type = f.getType();
                    if (type != long.class && type != int.class
                            && type != Long.class && type != Integer.class) continue;
                    f.setAccessible(true);
                    Object n = f.get(record);
                    if (!(n instanceof Number)) continue;
                    long value = ((Number) n).longValue();
                    // 2020–2036, Unix seconds or milliseconds. Other counters are omitted.
                    boolean seconds = value >= 1577836800L && value < 2082758400L;
                    boolean millis = value >= 1577836800000L && value < 2082758400000L;
                    if (!seconds && !millis) continue;
                    if (out.length() > 0) out.append(',');
                    out.append(f.getName()).append('=').append(value);
                    if (++count == 6) return out.toString();
                }
            }
        } catch (Throwable ignored) {}
        return out.length() == 0 ? "none" : out.toString();
    }
}
