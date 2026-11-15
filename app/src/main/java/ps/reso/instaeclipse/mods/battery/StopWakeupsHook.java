package ps.reso.instaeclipse.mods.battery;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Intent;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Stop Background Wake-ups (battery): suppresses three periodic things Instagram schedules that wake
 * the phone while the app is in the background, each behind its own switch, all default OFF.
 *
 * <ul>
 *   <li><b>stopBackgroundHeartbeat</b> — IG's "warm heartbeat" alarm, an {@link AlarmManager} alarm
 *       re-armed every minute or two whose only job is to note the app is still alive
 *       ({@code com.instagram.warm_heartbeat}, delivered to {@code KeepWarmReceiver}).</li>
 *   <li><b>stopUploadAlarm</b> — the analytics upload alarm, an {@link AlarmManager} alarm that wakes
 *       the phone ~5 minutes on to flush queued usage events
 *       ({@code action_batch_upload}, delivered to {@code AnalyticsUploadAlarmReceiver}).</li>
 *   <li><b>stopBackgroundPrefetch</b> — IG's background prefetch {@code JobScheduler} job
 *       ({@code IgBgFetchSchedulerService}) that periodically downloads the feed ahead of time.</li>
 * </ul>
 *
 * <p>Mechanism (version-independent — no DexKit, no obfuscated class names): the two alarms are keyed
 * only off Instagram's own stable, unobfuscated Intent action strings. Every {@link PendingIntent}
 * IG builds for one of those actions is tagged at creation (hooking the framework
 * {@code PendingIntent.getBroadcast}); the matching {@code AlarmManager.set*} call is then skipped.
 * The prefetch job is dropped by inspecting the {@code JobInfo}'s service component name at
 * {@code JobScheduler.schedule/enqueue} time. Everything fails open: a hook that can't be placed
 * leaves Instagram's normal behaviour untouched.
 *
 * <p>Scope is deliberately narrow — only these periodic background wake-ups are suppressed. Realtime
 * messaging, push notifications, uploads the user starts, and foreground network all go through
 * different paths and are never touched.
 */
public final class StopWakeupsHook {

    // Instagram's own alarm actions. Unobfuscated and stable across IG versions (verified 451/452).
    private static final String ACTION_HEARTBEAT = "com.instagram.warm_heartbeat";
    private static final String ACTION_UPLOAD = "action_batch_upload";

    // PendingIntents tagged at creation by the action they carry. Weak so a replaced/GC'd intent is
    // never pinned; synchronized because getBroadcast and AlarmManager.set can run on different
    // threads. PendingIntent equality is by its system target token, so membership is reliable.
    private static final Set<PendingIntent> heartbeatPis =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Set<PendingIntent> uploadPis =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public void install() {
        tagPendingIntents();
        dropAlarms();
        dropPrefetchJobs();
    }

    /**
     * Tag every heartbeat / upload PendingIntent as it is created. Done unconditionally (not gated on
     * the flags) because IG caches these PendingIntents lazily: tagging always means toggling a switch
     * on later still catches an intent that was created earlier.
     */
    private void tagPendingIntents() {
        try {
            XposedHelpers.findAndHookMethod(PendingIntent.class, "getBroadcast",
                    android.content.Context.class, int.class, Intent.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (!(p.args[2] instanceof Intent) || !(p.getResult() instanceof PendingIntent))
                                    return;
                                String action = ((Intent) p.args[2]).getAction();
                                if (action == null) return;
                                if (ACTION_HEARTBEAT.equals(action)) {
                                    heartbeatPis.add((PendingIntent) p.getResult());
                                } else if (ACTION_UPLOAD.equals(action)) {
                                    uploadPis.add((PendingIntent) p.getResult());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            ModuleLog.line("(InstaEclipse | StopWakeups): ✅ tagging heartbeat/upload PendingIntents");
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | StopWakeups): ⚠️ could not hook PendingIntent.getBroadcast: " + t);
        }
    }

    /**
     * Skip the {@code AlarmManager.set*} call for a tagged heartbeat / upload PendingIntent while its
     * switch is on. Hooks every public {@code set*} overload that takes a PendingIntent, so no single
     * API-level-specific overload has to be named; the drop only ever fires for the two tagged
     * intents, so every other alarm Instagram (or the framework) sets is left alone.
     */
    private void dropAlarms() {
        final XC_MethodHook alarmHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (!FeatureFlags.stopBackgroundHeartbeat && !FeatureFlags.stopUploadAlarm) return;
                    PendingIntent pi = null;
                    for (Object a : p.args) {
                        if (a instanceof PendingIntent) {
                            pi = (PendingIntent) a;
                            break;
                        }
                    }
                    if (pi == null) return;
                    if (FeatureFlags.stopBackgroundHeartbeat && heartbeatPis.contains(pi)) {
                        p.setResult(null);
                    } else if (FeatureFlags.stopUploadAlarm && uploadPis.contains(pi)) {
                        p.setResult(null);
                    }
                } catch (Throwable ignored) {
                }
            }
        };

        int n = 0;
        for (Method m : AlarmManager.class.getDeclaredMethods()) {
            if (!m.getName().startsWith("set")) continue;
            Class<?>[] pt = m.getParameterTypes();
            // Only the PendingIntent-based overloads (not the OnAlarmListener ones, nor setImpl,
            // whose last parameter is not a PendingIntent).
            if (pt.length == 0 || pt[pt.length - 1] != PendingIntent.class) continue;
            try {
                XposedBridge.hookMethod(m, alarmHook);
                n++;
            } catch (Throwable ignored) {
            }
        }
        if (n > 0) {
            FeatureStatusTracker.setHooked("StopBackgroundHeartbeat");
            FeatureStatusTracker.setHooked("StopUploadAlarm");
            ModuleLog.line("(InstaEclipse | StopWakeups): ✅ AlarmManager set* hooks: " + n);
        } else {
            ModuleLog.line("(InstaEclipse | StopWakeups): ⚠️ no AlarmManager set* overloads hooked");
        }
    }

    /**
     * Drop Instagram's background prefetch job at schedule time. The JobInfo's service component is
     * {@code IgBgFetchSchedulerService} (stable, unobfuscated); when the switch is on the schedule
     * call returns {@code RESULT_SUCCESS} without actually queueing anything, so IG believes the job
     * was scheduled and does not retry. Other jobs are untouched.
     */
    private void dropPrefetchJobs() {
        final XC_MethodHook jobHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (!FeatureFlags.stopBackgroundPrefetch) return;
                    for (Object a : p.args) {
                        if (a instanceof JobInfo) {
                            ComponentName cn = ((JobInfo) a).getService();
                            if (cn != null) {
                                String c = cn.getClassName();
                                if (c != null && c.toLowerCase(Locale.ROOT).contains("bgfetch")) {
                                    p.setResult(JobScheduler.RESULT_SUCCESS);
                                }
                            }
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };

        Class<?> impl;
        try {
            impl = Class.forName("android.app.JobSchedulerImpl");
        } catch (Throwable t) {
            impl = JobScheduler.class; // last resort (abstract — hook may be inert); fail open
        }

        int n = 0;
        try {
            XposedHelpers.findAndHookMethod(impl, "schedule", JobInfo.class, jobHook);
            n++;
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(impl, "enqueue", JobInfo.class, JobWorkItem.class, jobHook);
            n++;
        } catch (Throwable ignored) {
        }
        if (n > 0) {
            FeatureStatusTracker.setHooked("StopBackgroundPrefetch");
            ModuleLog.line("(InstaEclipse | StopWakeups): ✅ JobScheduler hooks: " + n);
        } else {
            ModuleLog.line("(InstaEclipse | StopWakeups): ⚠️ could not hook JobScheduler.schedule");
        }
    }
}
