package ps.reso.instaeclipse.mods.media;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Picture-in-picture for reels. Instagram already has a floating window for reels — as you leave the
 * Reels viewer the reel shrinks into a PiP window and keeps playing — but it only wires it up when a
 * server experiment ({@code isPictureInPictureEnabledPersistentVersion}) and its own session switch
 * ({@code isClipsPipEnabled}) both say yes, and both are off for almost every account. Each of those
 * gates is a {@code boolean}-returning method located by its own "android_purge_…" marker string
 * (the class/method names are obfuscated and change per build, the markers don't). Forcing them to
 * return {@code true} while the switch is on unlocks Instagram's own PiP window, so it takes effect
 * the next time Reels or a reel opens.
 *
 * <p>The window still needs Android's own picture-in-picture support (Android 12+, and in practice
 * Android 15+ for Instagram's clips viewer) with PiP allowed for Instagram; nothing here can create
 * that where the OS/device doesn't offer it.
 */
public class VideoPipHook {

    private static final String TAG = "(InstaEclipse | VideoPiP): ";
    private static final String FEATURE = "VideoPictureInPicture";

    /** IG's own log markers for the two reel-PiP gate checks (stable across obfuscated builds). */
    private static final String[] MARKERS = {
            "android_purge_26_q2_ClipsViewerExperimentUtil_isPictureInPictureEnabledPersistentVersion",
            "android_purge_26_q2_ClipsViewerPipUtil_isClipsPipEnabled",
    };

    public void install(DexKitBridge bridge, ClassLoader cl) {
        int hooked = 0;
        for (String marker : MARKERS) {
            try {
                for (MethodData md : DexKitQueryCache.findMethod(bridge,
                        FindMethod.create().matcher(MethodMatcher.create().usingStrings(marker)))) {
                    if (md.isConstructor() || !"boolean".equals(md.getReturnTypeName())) continue;
                    Method gate = md.getMethodInstance(cl);
                    XposedBridge.hookMethod(gate, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (FeatureFlags.videoPictureInPicture) param.setResult(true);
                        }
                    });
                    hooked++;
                    ModuleLog.line(TAG + "✅ reel PiP gate " + gate.getDeclaringClass().getName() + "." + gate.getName());
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ gate lookup failed (" + marker + "): " + t);
            }
        }
        if (hooked > 0) {
            FeatureStatusTracker.setHooked(FEATURE);
        } else if (FeatureFlags.videoPictureInPicture) {
            FeatureStatusTracker.setBroken(FEATURE);
            ModuleLog.line(TAG + "❌ no reel PiP gate found in this Instagram version");
        }
    }
}
