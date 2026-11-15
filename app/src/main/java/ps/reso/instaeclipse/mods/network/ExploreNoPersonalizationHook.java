package ps.reso.instaeclipse.mods.network;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Explore without personalization: ask Instagram for the non-personalized Explore grid on every
 * fetch. Instagram otherwise only serves that grid where the EU's Digital Services Act requires it.
 *
 * <p>The topical_explore request (discover/topical_explore/) is assembled by one builder that adds
 * the form param {@code is_nonpersonalized_explore} from a {@code Boolean}. DexKit finds that
 * builder by the literal {@code is_nonpersonalized_explore}; the hook forces the param to
 * {@code TRUE} before the request is built, so it goes out as true.
 *
 * <p><b>Primary anchor (IG 452/455+).</b> The builder does not take the flag as a plain method
 * argument any more. On IG 455 the builder became an instance class (e.g. {@code LX/0QpG.A00()})
 * that stores each flag in an obfuscated {@code Boolean} field and writes every form param through
 * one generic {@code setter(String key, Boolean value)} ({@code A0J} on 455, {@code A0G} on 452).
 * We locate that setter as the single {@code (String, Boolean)void} method the anchor method
 * invokes, hook it, and force the value to {@code TRUE} whenever the key equals
 * {@code is_nonpersonalized_explore}. This needs no obfuscated field name and no argument index, so
 * it holds across both the 452 field/setter shape and the 455 one; the runtime key check scopes the
 * change to exactly the Explore param.
 *
 * <p><b>Fallback (IG 451/452).</b> The older static factory took the flag as its 2nd
 * {@code java.lang.Boolean} parameter (right after {@code enable_adaptive_grid}); DexKit finds it by
 * the same literal and the hook flips that 2nd Boolean argument to {@code TRUE}. Kept as a safety
 * net in case the setter cannot be resolved.
 *
 * <p>Fails open: with the switch off, nothing found, an unexpected shape, or anything thrown,
 * Instagram's own value stands. This is a request-param injection, not a URL drop, so it lives
 * outside {@link IGNetworkInterceptor} (which rewrites the resolved URI, not the POST body).
 */
public final class ExploreNoPersonalizationHook {

    private static final String TAG = "(IE|ExploreNoPersonalization) ";
    private static final String CACHE_KEY = "ExploreNoPersonalization_builder";
    private static final String SETTER_CACHE_KEY = "ExploreNoPersonalization_setter";
    private static final String ANCHOR = "is_nonpersonalized_explore";

    private ExploreNoPersonalizationHook() {}

    public static void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            // Primary: the generic (String key, Boolean value) form-param setter (IG 452/455+).
            if (installParamSetter(bridge, cl)) return;
            // Fallback: the older builder that took the flag as its 2nd Boolean argument (IG 451/452).
            if (installArgFlip(bridge, cl)) return;

            ModuleLog.line(TAG + "❌ explore request builder not found for \"" + ANCHOR + "\"");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    /**
     * NEW anchor: find the single {@code (String, Boolean)void} setter the explore builder invokes
     * and force the value to {@code TRUE} when the key is {@code is_nonpersonalized_explore}.
     * Returns true when the setter was found and hooked.
     */
    private static boolean installParamSetter(DexKitBridge bridge, ClassLoader cl) {
        Method setter = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(SETTER_CACHE_KEY, cl) : null;
        if (setter == null) {
            outer:
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(ANCHOR)))) {
                for (MethodData inv : md.getInvokes()) {
                    try {
                        if (!inv.isMethod()) continue;
                        Method h = inv.getMethodInstance(cl);
                        Class<?>[] p = h.getParameterTypes();
                        // The one form-param setter: void(String key, Boolean value).
                        if (h.getReturnType() == void.class && p.length == 2
                                && p[0] == String.class && p[1] == Boolean.class) {
                            setter = h;
                            break outer;
                        }
                    } catch (Throwable ignored) {
                        // An invoke we cannot resolve is simply not our setter.
                    }
                }
            }
            if (setter == null) return false;
            DexKitCache.saveMethod(SETTER_CACHE_KEY, setter);
        }
        final Method hooked = setter;
        XposedBridge.hookMethod(hooked, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                try {
                    if (!FeatureFlags.exploreNoPersonalization) return;
                    // Only touch the Explore flag; the same setter writes other params too.
                    if (param.args != null && param.args.length == 2 && ANCHOR.equals(param.args[0])) {
                        param.args[1] = Boolean.TRUE;
                    }
                } catch (Throwable ignored) {
                    // A malformed request must never crash IG's Explore load.
                }
            }
        });
        FeatureStatusTracker.setHooked("ExploreNoPersonalization");
        ModuleLog.line(TAG + "✅ " + hooked.getDeclaringClass().getName() + "." + hooked.getName()
                + " (param \"" + ANCHOR + "\")");
        return true;
    }

    /**
     * OLD anchor (unchanged): the builder itself carries at least two {@code Boolean} params
     * (adaptive-grid + nonpersonalized); flip the 2nd Boolean argument to {@code TRUE} before the
     * request is built. Returns true when a builder was found (and either hooked or diagnosed).
     */
    private static boolean installArgFlip(DexKitBridge bridge, ClassLoader cl) {
        Method found = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(CACHE_KEY, cl) : null;
        if (found == null) {
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(ANCHOR)))) {
                Method m;
                try {
                    m = md.getMethodInstance(cl);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                // The builder carries at least two Boolean params (adaptive-grid + nonpersonalized).
                if (booleanParamCount(m) >= 2) {
                    found = m;
                    break;
                }
            }
            if (found == null) return false;
            DexKitCache.saveMethod(CACHE_KEY, found);
        }
        final Method builder = found;
        // The index of the 2nd Boolean parameter: the nonpersonalized flag on IG 451/452.
        final int flagArg = secondBooleanIndex(builder);
        if (flagArg < 0) {
            ModuleLog.line(TAG + "❌ no 2nd Boolean param on " + builder.getName());
            return true;
        }
        XposedBridge.hookMethod(builder, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                try {
                    if (!FeatureFlags.exploreNoPersonalization) return;
                    if (param.args != null && flagArg < param.args.length) {
                        param.args[flagArg] = Boolean.TRUE;
                    }
                } catch (Throwable ignored) {
                    // A malformed request must never crash IG's Explore load.
                }
            }
        });
        FeatureStatusTracker.setHooked("ExploreNoPersonalization");
        ModuleLog.line(TAG + "✅ " + builder.getDeclaringClass().getName() + "." + builder.getName()
                + " (arg " + flagArg + ")");
        return true;
    }

    private static int booleanParamCount(Method m) {
        int n = 0;
        for (Class<?> p : m.getParameterTypes()) if (p == Boolean.class) n++;
        return n;
    }

    /** Index of the 2nd {@code java.lang.Boolean} parameter (static method: args align with params), or -1. */
    private static int secondBooleanIndex(Method m) {
        Class<?>[] types = m.getParameterTypes();
        int seen = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == Boolean.class && ++seen == 2) return i;
        }
        return -1;
    }
}
