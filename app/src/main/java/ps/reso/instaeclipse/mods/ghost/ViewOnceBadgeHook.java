package ps.reso.instaeclipse.mods.ghost;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Restores IG's native view-once/twice corner icon that permanentViewMode ("Show View-Once/Twice
 * as Normal Media") hides by rewriting view_mode -> "permanent".
 *
 * Render path (447): render-props object X/0E4S carries the view_mode String; composable X/0YKc.A00
 * (RavenCornerIcon) draws once vs twice from it. permanentViewMode rewrites view_mode to "permanent"
 * at parse, so by props-build time it's "permanent" and no icon draws.
 *
 * GhostPermanentViewHook stashes the original "once"/"replayable" (keyed by media id) before the
 * rewrite. Hook the props ctor (anchored on the ctor inlining BOTH "once" and "replayable" — 0E4S);
 * when mode is "permanent" and an id-like String field matches the stash, put the original mode back.
 *
 * Gated on permanentViewMode (icon only missing when it's active).
 * Carries PROBE logging to confirm field layout / id-matching on-device.
 */
public class ViewOnceBadgeHook {

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook hook = buildHook();

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("ViewOnceBadge", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                ModuleLog.line("(IE|VOBadge) ✅ hooked (cached): "
                        + cached.getDeclaringClass().getName());
                FeatureStatusTracker.setHooked("PermanentViewMode");
                return;
            }
        }

        try {
            // props ctor inlines BOTH "once" and "replayable" for its "isRaven" flag; RavenCornerIcon
            // (0YKc.A00) references both too, so require <init>.
            List<MethodData> methods = DexKitQueryCache.findMethod(bridge, FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .name("<init>")
                            .usingStrings("once", "replayable")));
            if (methods.isEmpty()) {
                ModuleLog.line("(IE|VOBadge) ❌ view-once props ctor not found");
                return;
            }
            // PROBE: hook every match (ctor or method). Body is gated on permanentViewMode and only
            // logs/restores, so hooking several is harmless and shows which class carries view_mode.
            int hooked = 0;
            for (MethodData md : methods) {
                try {
                    Member member = md.isConstructor()
                            ? md.getConstructorInstance(classLoader)
                            : md.getMethodInstance(classLoader);
                    XposedBridge.hookMethod((java.lang.reflect.Member) member, hook);
                    ModuleLog.line("(IE|VOBadge) ✅ hooked " + md.getClassName()
                            + (md.isConstructor() ? ".<init>" : "." + md.getName()));
                    hooked++;
                } catch (Throwable e) {
                    ModuleLog.line("(IE|VOBadge) ⚠️ skip " + md.getClassName() + ": " + e);
                }
            }
            if (hooked == 0) {
                ModuleLog.line("(IE|VOBadge) ❌ props ctor found but none reflectable");
                return;
            }
            FeatureStatusTracker.setHooked("PermanentViewMode");
        } catch (Throwable t) {
            ModuleLog.line("(IE|VOBadge) ❌ " + t);
        }
    }

    private XC_MethodHook buildHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.permanentViewMode) return;
                Object[] args = param.args;
                if (args == null) return;
                try {
                    // model's view_mode was rewritten to "permanent"; map it back to the ephemeral
                    // value here (render props only) so IG's ctor draws the native 1-vs-2 badge.
                    // Original once/twice type comes from ORIGINAL_BY_KEY via an id/timestamp long
                    // shared with the model.
                    int modeIdx = -1;
                    for (int i = 0; i < args.length; i++) {
                        if (GhostPermanentViewHook.PERMANENT.equals(args[i])) { modeIdx = i; break; }
                    }
                    if (modeIdx < 0) return; // not a rewritten view-once bubble

                    java.util.List<Long> candidates = new java.util.ArrayList<>();
                    collectLongs(args, candidates, 0,
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
                    String original = null;
                    for (Long k : candidates) {
                        String hit = GhostPermanentViewHook.ORIGINAL_BY_KEY.get(k);
                        if (hit != null) { original = hit; break; }
                    }
                    args[modeIdx] = (original != null) ? original : "once";
                    ModuleLog.line("(IE|VOBadge|PROBE) resolved=" + args[modeIdx]
                            + " match=" + (original != null) + " candLongs=" + candidates);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|VOBadge) ❌ hook body: " + t);
                }
            }
        };
    }

    /** Collect id/timestamp longs from the ctor args graph (shallow) as join-key candidates. */
    private static void collectLongs(Object obj, java.util.List<Long> out, int depth, java.util.Set<Object> seen) {
        if (obj == null || depth > 4 || out.size() > 60 || !seen.add(obj)) return;
        if (obj instanceof Object[]) {
            for (Object e : (Object[]) obj) collectLongs(e, out, depth + 1, seen);
            return;
        }
        String cn = obj.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Class<?> t = f.getType();
                try {
                    if (t == long.class) {
                        long v = f.getLong(obj);
                        if (v != 0 && v != Long.MAX_VALUE && !out.contains(v)) out.add(v);
                    } else if (t == String.class) {
                        // media ids often arrive as numeric strings (e.g. ExtendedImageUrl.A07)
                        String s = (String) f.get(obj);
                        if (s != null && s.length() >= 8 && s.length() <= 22 && s.chars().allMatch(Character::isDigit)) {
                            try { long v = Long.parseLong(s); if (!out.contains(v)) out.add(v); } catch (NumberFormatException ignored) {}
                        }
                    } else if (!t.isPrimitive()) {
                        collectLongs(f.get(obj), out, depth + 1, seen);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }
}
