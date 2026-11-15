package ps.reso.instaeclipse.mods.extras;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.SeekBar;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import ps.reso.instaeclipse.mods.media.PlaybackSpeedHook;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * In-feed video controls for the home feed, the feed counterparts of the reel controls in
 * {@link PlaybackSpeedHook} (hold-for-2x, scrubber) — this reuses that hook's tracked feed player
 * ({@link PlaybackSpeedHook#currentFeedPlayer()}) and its speed-apply path
 * ({@link PlaybackSpeedHook#applyLiveSpeed(float)}), and adds the gestures IG only gives reels:
 *
 * <ul>
 *   <li><b>Tap to pause/play</b> — a tap on the playing feed video toggles it, through the feed
 *   player's own play (the {@code (…, "start", …)} static, X.05fU.A03 on 452) and pause-with-reason
 *   (the {@code (String)} method, X.05fU.A0F("tapped")) methods.</li>
 *   <li><b>Hold for 2x</b> — a long press sets 2x on every live player (reusing the reel hold path),
 *   restored on release.</li>
 *   <li><b>Double-tap to skip</b> — a double tap on the left/right third seeks the underlying Groot
 *   player (X.01Om.A03, anchored on "seekTo: seekTimeMsWithPreview") by a chosen number of seconds.</li>
 *   <li><b>Seek bar</b> — a scrubber overlaid along the bottom of the feed video, fed by the player's
 *   progress callback ((int,int,boolean) on X.05fU, "FyN") and seeking through the same Groot seek.</li>
 * </ul>
 *
 * Each sub-control binds independently; a build missing one anchor loses only that control. All of the
 * seek/position work rides on anchors verified against IG 451/452 — see the field javadoc for fallbacks.
 */
public class FeedVideoControlsHook {

    private static final String TAG = "(InstaEclipse | FeedVideo): ";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Feed-player static play: {@code A03(player, "start", false, false, false)} on X.05fU. */
    private Method feedPlay;
    /** Feed-player pause-with-reason: {@code A0F(String)} on X.05fU; we pass "tapped". */
    private Method feedPause;
    /** Groot seek: static {@code A03(grootPlayer, int seekMs, boolean, boolean)} on X.01Om. */
    private Method grootSeek;
    /** The Groot player class (grootSeek's declaring class), for the reflective reach from a feed player. */
    private Class<?> grootClass;

    // Latest progress the feed player reported, and which player reported it.
    private static volatile WeakReference<Object> progressPlayer = new WeakReference<>(null);
    private static volatile int posMs;
    private static volatile int durMs;
    private static volatile boolean playing;
    private static volatile long progressAt;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        Class<?> feedCls = findFeedPlayerClass(bridge, cl);
        if (feedCls != null) {
            resolveFeedPlayPause(feedCls);
            hookProgress(feedCls);
        }
        resolveGrootSeek(bridge, cl);
        installTouch();
        installSeekBarTicker();
    }

    // ---- discovery ----------------------------------------------------------

    private Class<?> findFeedPlayerClass(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("FeedVideo_progress", cl) : null;
            if (cached != null) return cached.getDeclaringClass();
            for (ClassData cd : DexKitQueryCache.findClass(bridge, FindClass.create().matcher(ClassMatcher.create()
                    .usingStrings("IgFeedVideoPlayer: Persistent Visible Cover Image")))) {
                return cd.getInstance(cl);
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ feed player class: " + t);
        }
        return null;
    }

    /** Play is the only static {@code (self, String, boolean, boolean, boolean)}; pause the only instance {@code (String)}. */
    private void resolveFeedPlayPause(Class<?> feedCls) {
        for (Method m : SafeReflect.methods(feedCls)) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean stat = Modifier.isStatic(m.getModifiers());
            if (feedPlay == null && stat && p.length == 5 && p[0] == feedCls && p[1] == String.class
                    && p[2] == boolean.class && p[3] == boolean.class && p[4] == boolean.class) {
                m.setAccessible(true);
                feedPlay = m;
            } else if (feedPause == null && !stat && p.length == 1 && p[0] == String.class) {
                m.setAccessible(true);
                feedPause = m;
            }
        }
        if (feedPlay != null && feedPause != null) {
            FeatureStatusTracker.setHooked("feedVideoTapPause");
            ModuleLog.line(TAG + "✅ tap pause: play " + feedPlay.getName() + ", pause " + feedPause.getName());
        } else {
            ModuleLog.line(TAG + "⚠️ tap pause: play=" + feedPlay + " pause=" + feedPause);
        }
    }

    /** Hooks the feed player's (int,int,boolean) progress callback to track position/duration. */
    private void hookProgress(Class<?> feedCls) {
        Method found = null;
        for (Method m : SafeReflect.methods(feedCls)) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == void.class && p.length == 3
                    && p[0] == int.class && p[1] == int.class && p[2] == boolean.class) {
                found = m;
                break;
            }
        }
        if (found == null) {
            ModuleLog.line(TAG + "⚠️ progress callback not found");
            return;
        }
        DexKitCache.saveMethod("FeedVideo_progress", found);
        XposedBridge.hookMethod(found, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                try {
                    progressPlayer = new WeakReference<>(param.thisObject);
                    posMs = (int) param.args[0];
                    durMs = (int) param.args[1];
                    playing = Boolean.TRUE.equals(param.args[2]);
                    progressAt = SystemClock.uptimeMillis();
                } catch (Throwable ignored) {
                }
            }
        });
        ModuleLog.line(TAG + "✅ progress " + feedCls.getName() + "." + found.getName());
    }

    private void resolveGrootSeek(DexKitBridge bridge, ClassLoader cl) {
        try {
            Method m = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("FeedVideo_grootSeek", cl) : null;
            if (m == null) {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings("seekTo: seekTimeMsWithPreview: %d, jumpSeek: %s").returnType("void")))) {
                    Method c = md.getMethodInstance(cl);
                    Class<?>[] p = c.getParameterTypes();
                    if (Modifier.isStatic(c.getModifiers()) && p.length == 4 && p[1] == int.class
                            && p[2] == boolean.class && p[3] == boolean.class) {
                        m = c;
                        break;
                    }
                }
                if (m != null) DexKitCache.saveMethod("FeedVideo_grootSeek", m);
            }
            if (m == null) {
                ModuleLog.line(TAG + "⚠️ groot seek not found (seek/skip disabled)");
                return;
            }
            m.setAccessible(true);
            grootSeek = m;
            grootClass = m.getParameterTypes()[0];
            FeatureStatusTracker.setHooked("feedVideoDoubleTapSkip");
            FeatureStatusTracker.setHooked("feedVideoSeekBar");
            ModuleLog.line(TAG + "✅ groot seek " + grootClass.getName() + "." + m.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ groot seek: " + t);
        }
    }

    // ---- player actions -----------------------------------------------------

    private boolean play(Object feedPlayer) {
        if (feedPlay == null || feedPlayer == null) return false;
        try { feedPlay.invoke(null, feedPlayer, "start", false, false, false); return true; }
        catch (Throwable t) { ModuleLog.line(TAG + "play: " + t); return false; }
    }

    private boolean pause(Object feedPlayer) {
        if (feedPause == null || feedPlayer == null) return false;
        try { feedPause.invoke(feedPlayer, "tapped"); return true; }
        catch (Throwable t) { ModuleLog.line(TAG + "pause: " + t); return false; }
    }

    /** Seeks the Groot player reachable from the feed player to {@code ms}. */
    private boolean seek(Object feedPlayer, int ms) {
        if (grootSeek == null) return false;
        Object groot = reachGroot(feedPlayer, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (groot == null) return false;
        try { grootSeek.invoke(null, groot, Math.max(0, ms), false, false); return true; }
        catch (Throwable t) { ModuleLog.line(TAG + "seek: " + t); return false; }
    }

    private Object reachGroot(Object o, int depth, Set<Object> seen) {
        if (o == null || depth > 4 || grootClass == null || !seen.add(o)) return null;
        if (grootClass.isInstance(o)) return o;
        String n = o.getClass().getName();
        if (depth > 0 && !n.startsWith("X.") && !n.startsWith("com.instagram.")) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object r = reachGroot(f.get(o), depth + 1, seen);
                    if (r != null) return r;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** Current playback position, extrapolated a little while the video is playing. */
    private static int currentPos() {
        int p = posMs;
        if (playing) {
            long since = SystemClock.uptimeMillis() - progressAt;
            if (since > 0 && since < 1500) p += (int) since;
        }
        return durMs > 0 ? Math.max(0, Math.min(durMs, p)) : p;
    }

    /** True while a feed video is actively playing and no reel is on screen. */
    private boolean feedActionable(Activity a) {
        if (PlaybackSpeedHook.currentFeedPlayer() == null) return false;
        if (SystemClock.uptimeMillis() - progressAt > 1500) return false;
        try { if (PlaybackSpeedHook.reelShowing(a)) return false; } catch (Throwable ignored) {}
        return true;
    }

    // ---- touch: tap / double-tap / hold ------------------------------------

    private boolean injecting;
    private boolean streamDead;      // a scroll took this gesture: ignore the rest
    private boolean holdActive;      // a 2x hold is running
    private float downX, downY;
    private long downAt;
    private long lastTapAt;
    private float lastTapX, lastTapY;
    private WeakReference<Activity> touchActivity = new WeakReference<>(null);

    private final Runnable holdStart = () -> {
        Activity a = touchActivity.get();
        if (a == null || streamDead || holdActive || !FeatureFlags.feedVideoHold2x) return;
        holdActive = true;
        cancelDownstream(a);          // stop IG's own long-press (e.g. opening the reel)
        boolean ok = PlaybackSpeedHook.applyLiveSpeed(2f);
        ModuleLog.line(TAG + "feed hold: 2x applied=" + ok);
    };

    private void installTouch() {
        try {
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (injecting || !(param.args[0] instanceof MotionEvent ev)) return;
                    if (!anyTouchControl()) return;
                    if (onTouch((Activity) param.thisObject, ev)) param.setResult(true);
                }
            });
            ModuleLog.line(TAG + "✅ feed gesture layer");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ gesture layer: " + t);
        }
    }

    private static boolean anyTouchControl() {
        return FeatureFlags.feedVideoTapPause || FeatureFlags.feedVideoHold2x || FeatureFlags.feedVideoDoubleTapSkip;
    }

    /** @return true to swallow the event. */
    private boolean onTouch(Activity a, MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                streamDead = holdActive = false;
                touchActivity = new WeakReference<>(a);
                downX = ev.getRawX();
                downY = ev.getRawY();
                downAt = SystemClock.uptimeMillis();
                if (!inVideoRegion(a, downX, downY) || !feedActionable(a)) {
                    streamDead = true;
                    return false;
                }
                if (FeatureFlags.feedVideoHold2x) {
                    MAIN.postDelayed(holdStart, ViewConfiguration.getLongPressTimeout());
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (streamDead) return holdActive;
                int slop = ViewConfiguration.get(a).getScaledTouchSlop();
                if (!holdActive && Math.hypot(ev.getRawX() - downX, ev.getRawY() - downY) > slop) {
                    MAIN.removeCallbacks(holdStart);     // it's a scroll
                    streamDead = true;
                }
                return holdActive;
            }
            case MotionEvent.ACTION_UP -> {
                MAIN.removeCallbacks(holdStart);
                if (holdActive) {
                    holdActive = false;
                    PlaybackSpeedHook.applyLiveSpeed(PlaybackSpeedHook.activeSpeed());
                    return true;
                }
                if (streamDead) return false;
                boolean quick = SystemClock.uptimeMillis() - downAt <= ViewConfiguration.getTapTimeout() + 120;
                if (!quick) return false;
                return onTap(a, ev.getRawX(), ev.getRawY());
            }
            case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                MAIN.removeCallbacks(holdStart);
                boolean was = holdActive;
                if (holdActive) {
                    holdActive = false;
                    PlaybackSpeedHook.applyLiveSpeed(PlaybackSpeedHook.activeSpeed());
                }
                streamDead = true;
                return was;
            }
            default -> {
                return holdActive;
            }
        }
    }

    /** A quick tap: a second one within the double-tap window skips; otherwise a lone tap pauses. */
    private boolean onTap(Activity a, float x, float y) {
        long now = SystemClock.uptimeMillis();
        boolean isSecond = now - lastTapAt <= ViewConfiguration.getDoubleTapTimeout()
                && Math.hypot(x - lastTapX, y - lastTapY) < dp(a, 64);
        lastTapAt = now;
        lastTapX = x;
        lastTapY = y;

        if (isSecond && FeatureFlags.feedVideoDoubleTapSkip) {
            lastTapAt = 0;                // consume the pair
            int side = thirdOfScreen(a, x);
            if (side != 0) {
                Object fp = PlaybackSpeedHook.currentFeedPlayer();
                int target = currentPos() + side * FeatureFlags.feedVideoSkipSeconds * 1000;
                boolean ok = fp != null && seek(fp, target);
                ModuleLog.line(TAG + "double-tap skip " + (side < 0 ? "-" : "+")
                        + FeatureFlags.feedVideoSkipSeconds + "s ok=" + ok);
                if (ok) { cancelDownstream(a); return true; }
            }
            return false;
        }

        if (FeatureFlags.feedVideoTapPause) {
            // Defer the toggle so a second tap (skip) can pre-empt it; still swallow the tap now so
            // IG doesn't open the reel underneath.
            MAIN.postDelayed(() -> {
                if (lastTapAt == 0) return;         // a double tap took it
                Object fp = PlaybackSpeedHook.currentFeedPlayer();
                if (fp == null) return;
                boolean wasPlaying = playing;
                boolean ok = wasPlaying ? pause(fp) : play(fp);
                ModuleLog.line(TAG + "tap " + (wasPlaying ? "pause" : "play") + " ok=" + ok);
            }, FeatureFlags.feedVideoDoubleTapSkip ? ViewConfiguration.getDoubleTapTimeout() + 20 : 0);
            cancelDownstream(a);
            return true;
        }
        return false;
    }

    /** Sends IG an ACTION_CANCEL so a gesture we took doesn't also fire IG's own handler. */
    private void cancelDownstream(Activity a) {
        if (a.getWindow() == null) return;
        long t = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, downX, downY, 0);
        injecting = true;
        try { a.dispatchTouchEvent(cancel); }
        catch (Throwable ignored) { }
        finally { injecting = false; cancel.recycle(); }
    }

    private static boolean inVideoRegion(Activity a, float x, float y) {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        return y > dm.heightPixels * 0.12f && y < dm.heightPixels * 0.88f;
    }

    /** -1 left third, 1 right third, 0 middle. */
    private static int thirdOfScreen(Activity a, float x) {
        float w = a.getResources().getDisplayMetrics().widthPixels;
        if (x < w / 3f) return -1;
        if (x > w * 2f / 3f) return 1;
        return 0;
    }

    private static int dp(View v, int value) {
        return Math.round(value * v.getResources().getDisplayMetrics().density);
    }

    private static int dp(Activity a, int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }

    // ---- seek bar overlay ---------------------------------------------------

    private static final int BAR_DP = 28;
    private WeakReference<SeekBar> barRef = new WeakReference<>(null);
    private WeakReference<Object> barPlayer = new WeakReference<>(null);
    private boolean barDragging;
    private boolean resumeAfterDrag;

    /** A 250ms ticker keeps the scrubber attached to the playing feed video and in sync. */
    private void installSeekBarTicker() {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try { tickBar(); } catch (Throwable ignored) { }
                MAIN.postDelayed(this, 250);
            }
        }, 500);
    }

    private void tickBar() {
        SeekBar bar = barRef.get();
        if (!FeatureFlags.feedVideoSeekBar || grootSeek == null) {
            if (bar != null) detachBar(bar);
            return;
        }
        Object fp = PlaybackSpeedHook.currentFeedPlayer();
        boolean fresh = SystemClock.uptimeMillis() - progressAt < 1500 && durMs > 0;
        View cover = fp != null && fresh ? findVideoView(fp) : null;
        FrameLayout frame = cover != null && cover.getParent() instanceof FrameLayout fl ? fl : null;
        if (frame == null || !frame.isShown()) {
            if (bar != null) detachBar(bar);
            return;
        }
        if (bar == null || bar.getParent() != frame || barPlayer.get() != fp) {
            if (bar != null) detachBar(bar);
            bar = new SeekBar(frame.getContext());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(frame, BAR_DP), android.view.Gravity.BOTTOM);
            final Object player = fp;
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar sb) {
                    barDragging = true;
                    resumeAfterDrag = playing && pause(player);
                }
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) { }
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    barDragging = false;
                    seek(player, sb.getProgress());
                    if (resumeAfterDrag) play(player);
                    resumeAfterDrag = false;
                }
            });
            frame.addView(bar, frame.indexOfChild(cover) + 1, lp);
            barRef = new WeakReference<>(bar);
            barPlayer = new WeakReference<>(fp);
            ModuleLog.line(TAG + "✅ feed seek bar attached");
        }
        if (!barDragging) {
            if (bar.getMax() != durMs) bar.setMax(durMs);
            bar.setProgress(currentPos());
        }
    }

    private void detachBar(SeekBar bar) {
        barDragging = false;
        resumeAfterDrag = false;
        if (bar.getParent() instanceof ViewGroup g) g.removeView(bar);
        barRef = new WeakReference<>(null);
        barPlayer = new WeakReference<>(null);
    }

    /** The feed video's cover/surface view reachable from the feed player (for the scrubber to sit over). */
    private View findVideoView(Object feedPlayer) {
        return findView(feedPlayer, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private View findView(Object o, int depth, Set<Object> seen) {
        if (o == null || depth > 3 || !seen.add(o)) return null;
        if (o instanceof View v) {
            return v.getParent() instanceof FrameLayout && v.isShown() ? v : null;
        }
        String n = o.getClass().getName();
        if (depth > 0 && !n.startsWith("X.") && !n.startsWith("com.instagram.")) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    View r = findView(f.get(o), depth + 1, seen);
                    if (r != null) return r;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
