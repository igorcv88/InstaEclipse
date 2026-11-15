package ps.reso.instaeclipse.utils.core;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.io.File;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Auto-clears Instagram's cache on background if it's over a user-set size (GitHub feature
 * request).
 *
 * Runs when the last visible activity stops, so nothing on screen references the deleted files
 * (mid-session clearing caused broken-image placeholders); IG rebuilds the cache on return.
 *
 * IG keeps almost nothing in getExternalCacheDir(); the bulk of its image/video cache lives
 * under getCacheDir() (Fresco, exoplayer) and in some builds under subdirs of filesDir(). So the
 * standard cache dirs and any cache-like subdir of filesDir (name contains "cache") are measured
 * and cleared. Size check uses the OS cache figure (what Android Settings shows), cache IG
 * safely regenerates.
 */
public class CacheAutoClear {

    /**
     * Data subdirs (under the app data dir, not getCacheDir()) that are pure telemetry/analytics and
     * safe to wipe — IG regenerates them and nothing functional is lost. app_analytics alone is
     * routinely the single biggest consumer (hundreds of MB), and the OS counts it as "data", not
     * "cache", so it's invisible to the cache figure; we size and clear it explicitly.
     */
    private static final String[] JUNK_DATA_DIRS = {
            "app_analytics", "app_errorreporting", "app_qpl", "app_call_stats_v2",
            "app_file_poolreports", "app_file_poolcollector", "insta_crash_log", "crash_log"};

    private static volatile boolean installed = false;
    private static int startedActivities = 0;           // >0 = app in foreground
    private static long lastClear = 0;                  // throttle rapid background/foreground flips

    /**
     * Registers a background listener once per process. Called from the module's main activity
     * setup; safe to call repeatedly. Replaces the old clear-at-startup behaviour.
     */
    public static void maybeClear(Context ctx) {
        if (installed || ctx == null) return;
        Context appCtx = ctx.getApplicationContext();
        if (!(appCtx instanceof Application)) return;
        installed = true;
        ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("AutoClearCache");
        final Application app = (Application) appCtx;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { startedActivities++; }
            @Override public void onActivityStopped(Activity a) {
                startedActivities--;
                if (startedActivities <= 0) {
                    startedActivities = 0;
                    onAppBackgrounded(app);
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
        ModuleLog.line("(IE|CacheAutoClear) background listener installed");
    }

    private static void onAppBackgrounded(Context app) {
        if (!FeatureFlags.autoClearCache) return;
        long now = System.currentTimeMillis();
        if (now - lastClear < 30_000) return;   // avoid repeat clears on quick app-switches
        lastClear = now;
        new Thread(() -> {
            try {
                int limitMb = FeatureFlags.autoClearCacheSizeMb > 0 ? FeatureFlags.autoClearCacheSizeMb : 100;
                long limit = (long) limitMb * 1024 * 1024;

                // OS cache figure (matches Android Settings, counts all volumes); fall back to
                // summing the cache dirs when StorageStatsManager is unavailable.
                File[] targets = cacheTargets(app);
                long osCache = osCacheBytes(app);
                long dirTotal = 0;
                for (File t : targets) dirTotal += dirSize(t);
                // Include the junk-data dirs (app_analytics etc.) the OS cache figure leaves out.
                long measured = Math.max(osCache, dirTotal);

                ModuleLog.line("(IE|CacheAutoClear) backgrounded — cache=" + (measured / (1024 * 1024))
                        + "MB (limit " + limitMb + "MB)");

                if (measured >= limit) {
                    long freed = 0;
                    for (File t : targets) { freed += dirSize(t); clearContents(t); }
                    ModuleLog.line("(IE|CacheAutoClear) ✅ cleared ~" + (freed / (1024 * 1024)) + "MB");
                }
            } catch (Throwable t) {
                ModuleLog.line("(IE|CacheAutoClear) ❌ " + t);
            }
        }, "ie-cacheclear").start();
    }

    /**
     * Dirs treated as cache: internal + external cache dirs, code_cache, and any first-level
     * filesDir subdir whose name looks cache-like (some IG builds put Fresco/media cache under
     * files/ rather than cache/).
     */
    private static File[] cacheTargets(Context app) {
        java.util.LinkedHashSet<File> out = new java.util.LinkedHashSet<>();
        addIfDir(out, app.getCacheDir());
        addIfDir(out, app.getExternalCacheDir());
        try { addIfDir(out, app.getCodeCacheDir()); } catch (Throwable ignored) {}
        try {
            File files = app.getFilesDir();
            File[] kids = files != null ? files.listFiles() : null;
            if (kids != null) {
                for (File k : kids) {
                    if (k.isDirectory() && k.getName().toLowerCase().contains("cache")) addIfDir(out, k);
                }
            }
        } catch (Throwable ignored) {}
        // Telemetry/analytics data dirs (app_analytics is the big one) under the app data dir.
        File dataDir = dataDir(app);
        if (dataDir != null) {
            for (String name : JUNK_DATA_DIRS) addIfDir(out, new File(dataDir, name));
        }
        return out.toArray(new File[0]);
    }

    /** The app's /data/data/<pkg> dir (parent of getCacheDir), for reaching data-side junk dirs. */
    private static File dataDir(Context app) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && app.getDataDir() != null) {
                return app.getDataDir();
            }
        } catch (Throwable ignored) {}
        File cache = app.getCacheDir();
        return cache != null ? cache.getParentFile() : null;
    }

    /**
     * Clear Instagram's media cache + telemetry/analytics junk now (manual "Clear now"), off the UI
     * thread; {@code onDone} runs on the main thread when finished (e.g. to restart Instagram).
     */
    public static void clearNow(Context ctx, Runnable onDone) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                long freed = 0;
                for (File t : cacheTargets(app)) { freed += dirSize(t); clearContents(t); }
                ModuleLog.line("(IE|CacheAutoClear) ✅ manual clear freed ~" + (freed / (1024 * 1024)) + "MB");
            } catch (Throwable t) {
                ModuleLog.line("(IE|CacheAutoClear) ❌ manual clear: " + t);
            } finally {
                if (onDone != null) {
                    try {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(onDone);
                    } catch (Throwable ignored) {}
                }
            }
        }, "ie-cacheclear-now").start();
    }

    private static void addIfDir(java.util.Set<File> set, File d) {
        if (d != null && d.isDirectory()) set.add(d);
    }

    /** OS-attributed cache bytes for this app's own uid (no permission needed); 0 if unavailable. */
    private static long osCacheBytes(Context app) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return 0;
            android.app.usage.StorageStatsManager ssm =
                    (android.app.usage.StorageStatsManager) app.getSystemService(Context.STORAGE_STATS_SERVICE);
            if (ssm == null) return 0;
            android.app.usage.StorageStats st = ssm.queryStatsForUid(
                    android.os.storage.StorageManager.UUID_DEFAULT, android.os.Process.myUid());
            return st.getCacheBytes();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long dirSize(File d) {
        if (d == null || !d.exists()) return 0;
        long s = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) s += f.isDirectory() ? dirSize(f) : f.length();
        return s;
    }

    private static void clearContents(File d) {
        if (d == null || !d.isDirectory()) return;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) deleteRecursive(f);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
