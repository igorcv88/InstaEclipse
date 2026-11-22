package ps.reso.instaeclipse.mods.extras;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import org.luckypray.dexkit.DexKitBridge;

import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Rounded reel corners (HushGram gap "Rounded reel/story corners"): clips the reel's video
 * container to a rounded outline so the reel's corners are rounded, matching the Rounded-Posts
 * look in the feed.
 *
 * <p>Shares the reel container discovery with {@link ReelsBlurBarsHook} (the obfuscated
 * LithoClipsViewerVideoLayout FrameLayout). On each container's layout the host is clipped to a
 * rounded outline; the radius reuses {@link FeatureFlags#feedCornerRadius} (so one corner-size
 * control drives feed + reels), falling back to {@value #DEFAULT_DP}dp when the feed radius is 0,
 * so the toggle always does something. Fully gated by the flag and fail-open.
 */
public final class RoundedReelsHook {

    private static final String TAG = "(InstaEclipse | RoundedReels): ";
    private static final int DEFAULT_DP = 24;
    /** Tag storing the radius (px) currently applied to a host, so we don't re-set it each layout. */
    private static final int RADIUS_TAG = 0x7e1e0451;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> layout = ReelsBlurBarsHook.findLayoutClass(bridge, cl);
            if (layout == null) {
                ModuleLog.line(TAG + "❌ reel container not found");
                return;
            }
            View.OnLayoutChangeListener onLayout = (v, l, t, r, b, ol, ot, or, ob) -> apply(v);
            XposedBridge.hookAllConstructors(layout, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    if (param.thisObject instanceof FrameLayout host) host.addOnLayoutChangeListener(onLayout);
                }
            });
            FeatureStatusTracker.setHooked("roundReelCorners");
            ModuleLog.line(TAG + "✅ reel container " + layout.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static void apply(View host) {
        try {
            Object applied = host.getTag(RADIUS_TAG);
            if (!FeatureFlags.roundReelCorners) {
                if (applied == null) return;      // nothing to undo
                host.setTag(RADIUS_TAG, null);
                host.setClipToOutline(false);
                host.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                return;
            }
            int dp = FeatureFlags.feedCornerRadius > 0 ? FeatureFlags.feedCornerRadius : DEFAULT_DP;
            float radius = dp * host.getResources().getDisplayMetrics().density;
            if (applied instanceof Float f && f == radius && host.getClipToOutline()) return;
            host.setTag(RADIUS_TAG, radius);
            host.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            host.setClipToOutline(true);
            host.invalidateOutline();
        } catch (Throwable ignored) {}
    }
}
