package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;

import androidx.annotation.NonNull;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

/**
 * Keep playing in the background. When you leave Instagram (Home / Recents / switching apps, or the
 * screen turning off) while a feed, story or reel video plays, Instagram's fragment lifecycle pauses
 * its video player with the reason {@code "fragment_paused"}. This hook suppresses exactly that pause
 * while the app is being left, so the audio (and the video, in PiP) keeps playing.
 *
 * <p>Instagram's video player is obfuscated, but its pause/stop entry points still carry the literal
 * reason strings {@code "fragment_paused"} and {@code "single_tap_paused"}. We locate every method
 * that references {@code "fragment_paused"} and has a {@code (String)} or {@code (String, boolean)}
 * shape returning {@code void} — the player's {@code pause(reason)} and {@code stop(reason, …)}, plus
 * the separate story player's pause where a build uses one — and guard each at runtime: the call is
 * skipped only when the switch is on, the user is leaving the app, and the actual reason passed is
 * {@code "fragment_paused"}. A single-tap pause ({@code "single_tap_paused"}), an audio-focus pause,
 * or any in-app navigation pause is never touched, so normal behaviour is unchanged while you stay in
 * the app.
 *
 * <p>"Leaving" is detected from the activity's {@code onUserLeaveHint} (which fires before the pause,
 * unlike the lifecycle paused callback) and from the screen going off; it is cleared the moment any
 * Instagram activity resumes. This is best-effort background audio: there is no foreground service or
 * media notification, so Android may still freeze or evict Instagram after a while in the background.
 */
public class BackgroundPlayHook {

    private static final String TAG = "(InstaEclipse | BackgroundPlay): ";
    private static final String FEATURE = "KeepVideoPlayingBackground";
    private static final String REASON_FRAGMENT_PAUSED = "fragment_paused";

    /** True while the user is leaving the app, so a fragment-driven pause should be held. */
    private static volatile boolean leaving = false;

    /** Activity classes whose onUserLeaveHint we've already hooked (dedup). */
    private static final Set<Class<?>> leaveHintHooked = Collections.synchronizedSet(new HashSet<>());
    private static boolean lifecycleRegistered = false;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        registerLifecycle();

        int hooked = 0;
        try {
            List<MethodData> candidates = DexKitQueryCache.findMethod(bridge,
                    FindMethod.create().matcher(MethodMatcher.create().usingStrings(REASON_FRAGMENT_PAUSED)));
            for (MethodData md : candidates) {
                if (md.isConstructor() || !"void".equals(md.getReturnTypeName())) continue;
                List<String> params = md.getParamTypeNames();
                boolean pauseShape = params.size() == 1 && "java.lang.String".equals(params.get(0));
                boolean stopShape = params.size() == 2 && "java.lang.String".equals(params.get(0))
                        && "boolean".equals(params.get(1));
                if (!pauseShape && !stopShape) continue;
                try {
                    Method m = md.getMethodInstance(cl);
                    XposedBridge.hookMethod(m, new PauseGuard());
                    hooked++;
                    ModuleLog.line(TAG + "✅ player pause " + m.getDeclaringClass().getName() + "." + m.getName()
                            + "(" + params + ")");
                } catch (Throwable t) {
                    ModuleLog.line(TAG + "skip one candidate: " + t);
                }
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ player lookup failed: " + t);
        }

        if (hooked > 0) {
            FeatureStatusTracker.setHooked(FEATURE);
        } else if (FeatureFlags.keepVideoPlayingBackground) {
            FeatureStatusTracker.setBroken(FEATURE);
            ModuleLog.line(TAG + "❌ no player pause entry point found in this Instagram version");
        }
    }

    /** Skips a fragment-driven player pause while the user is leaving the app and the switch is on. */
    private static final class PauseGuard extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            try {
                if (!FeatureFlags.keepVideoPlayingBackground || !leaving) return;
                Object reason = param.args.length > 0 ? param.args[0] : null;
                if (REASON_FRAGMENT_PAUSED.equals(reason)) {
                    // Return without pausing: the video keeps playing in the background.
                    param.setResult(null);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void registerLifecycle() {
        if (lifecycleRegistered) return;
        try {
            Application app = AndroidAppHelper.currentApplication();
            if (app == null) {
                ModuleLog.line(TAG + "no application yet — background detection not armed");
                return;
            }
            app.registerActivityLifecycleCallbacks(new Lifecycle());
            lifecycleRegistered = true;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "lifecycle register failed: " + t);
        }
    }

    private static void hookLeaveHint(Activity activity) {
        Class<?> cls = activity.getClass();
        if (!leaveHintHooked.add(cls)) return;
        try {
            XposedHelpers.findAndHookMethod(cls, "onUserLeaveHint", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    // Home / Recents / app switch: fires before onPause, so it arms the guard in time.
                    leaving = true;
                }
            });
        } catch (Throwable ignored) {
            // Class doesn't declare/inherit a hookable onUserLeaveHint on this build.
        }
    }

    private static boolean screenOff(Context context) {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            return power != null && !power.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            leaving = false;
            hookLeaveHint(activity);
        }

        @Override
        public void onActivityPrePaused(@NonNull Activity activity) {
            // Runs before onPause (API 29+): catch the screen turning off, where there's no leave hint.
            if (screenOff(activity)) leaving = true;
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            // Pre-Q fallback: paused runs before the fragments pause there.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && screenOff(activity)) leaving = true;
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
