package ps.reso.instaeclipse.mods.extras;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Fill the black bars around Reels with a blurred, darkened copy of the reel's cover (#273).
 *
 * <p>The reel's container (FrameLayout, obfuscated "LithoClipsViewerVideoLayout") holds the cover
 * (IgImageView via {@code getImagePlaceHolder()}) and video, both sized to the video's aspect ratio;
 * the empty remainder is the bar. A backdrop view is added behind IG's children (negative Z, so their
 * indexes stay put — IG removes the video by index on swap) and paints the cover with a RenderEffect
 * blur and dark scrim.
 *
 * <p>Laid out at 1/{@value #SCALE} size and scaled up, so the blur layer is tiny; redraws only when
 * the cover bitmap changes (reference check on pre-draw). RenderEffect needs Android 12; older skipped.
 *
 * <p>Anchors (tried in order): (1) a constructor whose purge marker contains the stable tail
 * "LithoClipsViewerVideoLayout_init" — the "android_purge_&lt;yy&gt;_q&lt;n&gt;_" quarter-stamp prefix
 * rotates between IG versions (26_q2 on both 452 and 455), so a Contains match survives the rotation;
 * (2) the exact legacy marker "android_purge_26_q2_LithoClipsViewerVideoLayout_init"; (3) the single
 * class declaring getImagePlaceHolder()-&gt;IgImageView AND getVideoPlaceHolder()-&gt;SimpleVideoLayout
 * (return types pinned so a rounded-corner sibling whose getVideoPlaceHolder() returns a TextureView
 * can't mispick). Applies to reels opened after the switch is on.
 */
public class ReelsBlurBarsHook {

    private static final String TAG = "(InstaEclipse | ReelsBlur): ";
    private static final String CACHE_KEY = "Extras_ReelsVideoLayoutClass";
    private static final int SCALE = 8;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ModuleLog.line(TAG + "needs Android 12+, skipped");
            return;
        }
        Class<?> layout = findLayoutClass(bridge, cl);
        if (layout == null) return;
        final Method placeholder;
        try {
            placeholder = layout.getMethod("getImagePlaceHolder");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ getImagePlaceHolder missing on " + layout.getName());
            return;
        }
        XposedBridge.hookAllConstructors(layout, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.reelsBlurBars || !(param.thisObject instanceof FrameLayout host)) return;
                try {
                    if (!(placeholder.invoke(host) instanceof ImageView cover)) return;
                    host.addView(new BackdropView(host.getContext(), cover));
                    FeatureStatusTracker.setHooked("reelsBlurBars");
                } catch (Throwable t) {
                    ModuleLog.line(TAG + "attach failed: " + t);
                }
            }
        });
        ModuleLog.line(TAG + "✅ video layout " + layout.getName());
    }

    /** Package-visible so {@link RoundedReelsHook} can reuse the same reel-container discovery. */
    static Class<?> findLayoutClass(DexKitBridge bridge, ClassLoader cl) {
        String name = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_KEY) : null;
        if ("missing".equals(name)) return null;
        if (name == null) {
            try {
                // (1) Current marker, quarter-stamp-agnostic: match the stable tail with Contains so
                //     a future "android_purge_26_q3/27_q1_..._init" rename still resolves. On 455 this
                //     tail occurs only in LX/0Cj0's <init> (the LithoClipsViewerVideoLayout container).
                name = constructorClassForMarker(bridge, "LithoClipsViewerVideoLayout_init", StringMatchType.Contains);
                // (2) Legacy exact marker (how it resolved on 452's LX/08PR), kept as an explicit anchor.
                if (name == null) {
                    name = constructorClassForMarker(bridge, "android_purge_26_q2_LithoClipsViewerVideoLayout_init", StringMatchType.Equals);
                }
                // (3) Signature fallback, tightened with return types so it can't mispick: the reel
                //     container exposes getImagePlaceHolder()->IgImageView and
                //     getVideoPlaceHolder()->SimpleVideoLayout. The return types drop the rounded-corner
                //     sibling whose getVideoPlaceHolder() returns a TextureView; if more than one class
                //     still matches we take none rather than guess.
                if (name == null) {
                    List<ClassData> found = DexKitQueryCache.findClass(bridge, FindClass.create().matcher(ClassMatcher.create()
                            .methods(MethodsMatcher.create()
                                    .add(MethodMatcher.create().name("getImagePlaceHolder")
                                            .returnType("com.instagram.common.ui.widget.imageview.IgImageView"))
                                    .add(MethodMatcher.create().name("getVideoPlaceHolder")
                                            .returnType("com.instagram.ui.simplevideolayout.SimpleVideoLayout")))));
                    if (found.size() == 1) name = found.get(0).getName();
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "lookup failed: " + t);
            }
            DexKitCache.saveString(CACHE_KEY, name == null ? "missing" : name);
        }
        if (name == null) {
            ModuleLog.line(TAG + "❌ video layout not found in this Instagram version");
            return null;
        }
        try {
            return cl.loadClass(name);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + name + ": " + t);
            return null;
        }
    }

    /** Class of the first constructor whose body references {@code marker} (matched by {@code type}); null if none. */
    private static String constructorClassForMarker(DexKitBridge bridge, String marker, StringMatchType type) {
        try {
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(java.util.List.of(marker), type, false)))) {
                if (md.isConstructor()) return md.getClassName();
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "marker lookup failed (" + marker + "): " + t);
        }
        return null;
    }

    /**
     * Draws the cover bitmap center-cropped, darkened and blurred. Measured at 1/SCALE of the host
     * and scaled up from its top-left corner, so it covers the whole host with a small layer.
     */
    static final class BackdropView extends View implements ViewTreeObserver.OnPreDrawListener {

        private final ImageView cover;
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final Rect dst = new Rect();
        private Bitmap shown;
        private Method igGetBitmap;
        private boolean igGetBitmapResolved;

        BackdropView(Context context, ImageView cover) {
            super(context);
            this.cover = cover;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            setLayoutParams(lp);
            setTranslationZ(-1f); // drawn before IG's children without changing their indexes
            setOutlineProvider(null);
            setPivotX(0f);
            setPivotY(0f);
            setScaleX(SCALE);
            setScaleY(SCALE);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP));
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension((w + SCALE - 1) / SCALE, (h + SCALE - 1) / SCALE);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                getViewTreeObserver().addOnPreDrawListener(this);
            } catch (Throwable ignored) {}
        }

        @Override
        protected void onDetachedFromWindow() {
            try {
                getViewTreeObserver().removeOnPreDrawListener(this);
            } catch (Throwable ignored) {}
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onPreDraw() {
            try {
                // Keep the last cover if IG drops it while the video plays.
                Bitmap b = FeatureFlags.reelsBlurBars ? coverBitmap() : null;
                if (FeatureFlags.reelsBlurBars ? (b != null && b != shown) : shown != null) {
                    shown = b;
                    invalidate();
                }
            } catch (Throwable ignored) {}
            return true;
        }

        private Bitmap coverBitmap() {
            Drawable d = cover.getDrawable();
            if (d == null) return null;
            if (d instanceof BitmapDrawable bd) {
                Bitmap b = bd.getBitmap();
                if (b != null && !b.isRecycled()) return b;
            }
            if (!igGetBitmapResolved) { // IgImageView.getBitmap(), for non-bitmap drawables
                igGetBitmapResolved = true;
                try {
                    Method m = cover.getClass().getMethod("getBitmap");
                    if (m.getReturnType() == Bitmap.class) igGetBitmap = m;
                } catch (Throwable ignored) {}
            }
            if (igGetBitmap != null) {
                try {
                    Object o = igGetBitmap.invoke(cover);
                    if (o instanceof Bitmap b && !b.isRecycled()) return b;
                } catch (Throwable ignored) {}
            }
            // custom drawable (no getBitmap): rasterize what it's showing into a blurrable bitmap, cached by drawable
            return rasterize(d);
        }

        private Drawable renderedFrom;
        private Bitmap renderedBmp;

        private Bitmap rasterize(Drawable d) {
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) { w = cover.getWidth(); h = cover.getHeight(); }
            if (w <= 0 || h <= 0) return null;
            int max = 720; // the layer is blurred and downscaled anyway
            if (w > max || h > max) {
                float s = Math.min(max / (float) w, max / (float) h);
                w = Math.max(1, Math.round(w * s));
                h = Math.max(1, Math.round(h * s));
            }
            if (d == renderedFrom && renderedBmp != null && !renderedBmp.isRecycled()
                    && renderedBmp.getWidth() == w && renderedBmp.getHeight() == h) return renderedBmp;
            try {
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                Rect old = d.copyBounds();
                d.setBounds(0, 0, w, h);
                d.draw(c);
                d.setBounds(old); // restore the live drawable's bounds
                renderedFrom = d;
                renderedBmp = bmp;
                return bmp;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Bitmap b = shown;
            int w = getWidth(), h = getHeight();
            if (b == null || b.isRecycled() || !FeatureFlags.reelsBlurBars || w <= 0 || h <= 0) return;
            try {
                int bw = b.getWidth(), bh = b.getHeight();
                if (bw <= 0 || bh <= 0) return;
                // Center-crop the bitmap into the view.
                if ((long) bw * h > (long) bh * w) {
                    int cw = (int) ((long) bh * w / h);
                    src.set((bw - cw) / 2, 0, (bw + cw) / 2, bh);
                } else {
                    int ch = (int) ((long) bw * h / w);
                    src.set(0, (bh - ch) / 2, bw, (bh + ch) / 2);
                }
                dst.set(0, 0, w, h);
                canvas.drawBitmap(b, src, dst, bitmapPaint);
                canvas.drawColor(0x8C000000); // darken so the bars stay calm behind the video
            } catch (Throwable ignored) {}
        }
    }
}
