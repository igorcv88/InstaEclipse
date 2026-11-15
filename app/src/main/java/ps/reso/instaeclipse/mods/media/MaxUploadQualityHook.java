package ps.reso.instaeclipse.mods.media;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;

/**
 * Force "Upload at highest quality" always on.
 *
 * All upload paths gate on one helper reading the int pref "high_quality_media_upload" (1 = on).
 * Forcing its boolean result true is equivalent to the user enabling the switch, so IG's own
 * highest-quality path runs with no transcode changes. Matched by the pref string + (1-arg →
 * boolean) shape, not an obfuscated name, so it survives version bumps while the key is unchanged.
 */
public class MaxUploadQualityHook {

    private static final String TAG = "(IE|MaxUploadQuality) ";
    private static final String PREF_KEY = "high_quality_media_upload";

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            List<MethodData> results = DexKitQueryCache.findMethod(bridge, FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingEqStrings(List.of(PREF_KEY))
                            .returnType("boolean")
                            .paramCount(1)));

            int n = 0;
            for (MethodData md : results) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (!FeatureFlags.maxUploadQuality) return;
                            param.setResult(Boolean.TRUE);
                        }
                    });
                    n++;
                } catch (Throwable ignored) {}
            }

            if (n == 0) {
                ModuleLog.line(TAG + "❌ highest-quality-upload getter not found");
                if (FeatureFlags.maxUploadQuality) FeatureStatusTracker.setBroken("MaxUploadQuality");
                return;
            }

            FeatureStatusTracker.setHooked("MaxUploadQuality");
            ModuleLog.line(TAG + "✅ hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ install – " + t);
            if (FeatureFlags.maxUploadQuality) FeatureStatusTracker.setBroken("MaxUploadQuality");
        }
    }
}
