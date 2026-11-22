package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Incoming-call blocker. Instagram turns an incoming call into its ring (and full-screen screen) in
 * one method. On IG 451 that is
 * {@code LX/0kLJ->A01(Landroid/content/Context;LX/0qlq;Lcom/instagram/common/session/UserSession;Lcom/instagram/rtc/signaling/models/RtcConnectionEntity;Z)V}.
 * The obfuscated class ({@code 0kLJ}) changes per version, so the method is anchored by DexKit:
 * the one method that (a) uses the string {@code "rtc_call_activity_intent_action_incoming_call"},
 * (b) takes a {@code com.instagram.rtc.signaling.models.RtcConnectionEntity} parameter, and
 * (c) returns void. That resolves to exactly one method on 451/452.
 *
 * <p>In the hook, {@link CallBlocker#drop(Object)} inspects the {@code RtcConnectionEntity} arg's
 * {@code toString()} and decides by mode + list; when it says to block, {@code setResult(null)} skips
 * the ring (the method is void). Fails open on any error — the call rings.
 */
public final class CallBlockerHook {

    private static final String TAG = "(IE|CallBlocker) ";
    private static final String CACHE_KEY = "CallBlocker_ring";
    private static final String CACHE_KEY_ARG = "CallBlocker_ring_arg";

    /** The action an incoming call opens its screen with; only its ring loads it. */
    private static final String INCOMING_CALL_ACTION = "rtc_call_activity_intent_action_incoming_call";
    /** The call object Instagram rings with (a class it keeps by name, stable across versions). */
    private static final String CALL_ENTITY = "com.instagram.rtc.signaling.models.RtcConnectionEntity";

    private CallBlockerHook() {}

    public static void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method ring = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, cl) : null;
            int argIndex = parseInt(DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_KEY_ARG) : null);

            if (ring == null || argIndex < 0) {
                MethodData found = resolve(bridge, cl);
                if (found == null) {
                    ModuleLog.line(TAG + "❌ incoming-call ring method not found");
                    if (FeatureFlags.callBlockMode != 0) FeatureStatusTracker.setBroken("CallBlocker");
                    return;
                }
                ring = found.getMethodInstance(cl);
                argIndex = entityArgIndex(ring);
                if (argIndex < 0) {
                    ModuleLog.line(TAG + "❌ no RtcConnectionEntity parameter on " + found.getClassName() + "." + found.getName());
                    if (FeatureFlags.callBlockMode != 0) FeatureStatusTracker.setBroken("CallBlocker");
                    return;
                }
                DexKitCache.saveMethod(CACHE_KEY, ring);
                DexKitCache.saveString(CACHE_KEY_ARG, String.valueOf(argIndex));
            }

            final int call = argIndex;
            XposedBridge.hookMethod(ring, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    try {
                        // drop() handles both the global mode and the per-chat list, so don't bail on
                        // mode==0 here (that would skip per-chat blocks while the global mode is off).
                        if (param.args == null || call >= param.args.length) return;
                        // drop() handles both the global mode and the per-chat list, so don't bail on
                        // mode==0 here (that would skip per-chat blocks while the global mode is off).
                        if (CallBlocker.drop(param.args[call])) {
                            param.setResult(null); // void: the call never rings
                            ModuleLog.line(TAG + "blocked an incoming call");
                        }
                    } catch (Throwable ignored) {
                        // fail open: let the call ring
                    }
                }
            });
            FeatureStatusTracker.setHooked("CallBlocker");
            ModuleLog.line(TAG + "✅ " + ring.getDeclaringClass().getName() + "." + ring.getName() + " (arg " + call + ")");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
            if (FeatureFlags.callBlockMode != 0) FeatureStatusTracker.setBroken("CallBlocker");
        }
    }

    /**
     * The one void method using the incoming-call string and taking an RtcConnectionEntity. Each
     * candidate is resolved to a java.lang.reflect.Method and filtered by reflection (return void +
     * an RtcConnectionEntity parameter) — DexKit's own type-string forms vary by version and aren't
     * reliable to compare against.
     */
    private static MethodData resolve(DexKitBridge bridge, ClassLoader cl) {
        List<MethodData> candidates = DexKitQueryCache.findMethod(bridge, FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(INCOMING_CALL_ACTION)));
        MethodData match = null;
        for (MethodData m : candidates) {
            Method jm;
            try {
                jm = m.getMethodInstance(cl);
            } catch (Throwable t) {
                continue;
            }
            if (jm.getReturnType() != void.class) continue;
            if (entityArgIndex(jm) < 0) continue;
            if (match != null) {
                ModuleLog.line(TAG + "⚠️ more than one candidate; using first: " + match.getClassName() + "." + match.getName());
                break;
            }
            match = m;
        }
        return match;
    }

    private static int parseInt(String s) {
        if (s == null) return -1;
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    /** Index of the first parameter assignable to RtcConnectionEntity, or -1. */
    private static int entityArgIndex(Method ring) {
        Class<?>[] params = ring.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (params[i].getName().contains("RtcConnectionEntity")) return i;
        }
        return -1;
    }
}
