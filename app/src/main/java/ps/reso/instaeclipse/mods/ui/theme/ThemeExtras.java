package ps.reso.instaeclipse.mods.ui.theme;

import android.content.Context;
import android.os.Build;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/**
 * Advanced Theme Extras: small, optional layers that sit on top of the custom-theme engine
 * ({@link IgThemeEngine} / {@link IgColorRemapEngine}) rather than forking it.
 *
 * <ul>
 *   <li><b>Material You accent</b> ({@link FeatureFlags#themeDynamicAccent}) — the active palette's
 *       accent / button / link slots are replaced with the Android system (wallpaper) accent, read
 *       from {@code android.R.color.system_accent1_*} on API 31+.</li>
 *   <li><b>OLED surfaces</b> ({@link FeatureFlags#themeOledSurfaces}) — the active palette's base
 *       surfaces (background, surface, status / navigation bars) are forced to true black so sheets,
 *       dialogs and menus render pure-black while the theme keeps its accent and text colors.</li>
 *   <li><b>Accent-only recolor</b> ({@link FeatureFlags#themeAccentOnly}) — a cut-down engine mode
 *       (see {@link IgThemeEngine#isAccentOnlyMode()}) that recolors just Instagram's blue accent
 *       and leaves every other color stock. The accent source is this class's {@link #accentColor()}
 *       (a picked color, or the Material You accent when that layer is also on).</li>
 * </ul>
 *
 * All layers default off, so with the switches untouched {@link #applyLayers} returns its input
 * unchanged and the existing custom-theme behaviour is identical.
 */
public final class ThemeExtras {

    public static final int OLED_BLACK = 0xFF000000;
    private static final int OLED_DIVIDER = 0xFF161616; // faint separator so lines stay visible on true black

    // {fill, linkOnDark} read from the system wallpaper palette; null until first read / unsupported.
    private static volatile int[] systemAccent;

    private ThemeExtras() {}

    public static boolean materialYouAvailable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** Drop the cached system accent so a wallpaper change is picked up on the next resolve. */
    public static void invalidate() {
        systemAccent = null;
    }

    /** System accent as {fill (accent1_500), link-on-dark (accent1_200)}, both opaque, or null. */
    static int[] systemAccent() {
        if (!materialYouAvailable()) return null;
        int[] cached = systemAccent;
        if (cached != null) return cached;
        final Context c = context();
        if (c == null) return null;
        final int[] out = new int[2];
        try {
            // Read under the engine's bypass so our own Context.getColor hook doesn't intercept these
            // system colors (and can't recurse back into getActivePalette()).
            IgColorRemapEngine.withBypass(() -> {
                out[0] = 0xFF000000 | c.getColor(android.R.color.system_accent1_500);
                out[1] = 0xFF000000 | c.getColor(android.R.color.system_accent1_200);
            });
        } catch (Throwable t) {
            return null;
        }
        systemAccent = out;
        return out;
    }

    private static Context context() {
        try {
            Context c = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
            if (c == null) c = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The accent color for the accent-only recolor: the Material You accent when that layer is on
     *  and available, else the user-picked {@link FeatureFlags#themeAccentColor}. Always opaque. */
    public static int accentColor() {
        if (FeatureFlags.themeDynamicAccent) {
            int[] s = systemAccent();
            if (s != null) return s[0];
        }
        int c = FeatureFlags.themeAccentColor;
        return (c & 0xFF000000) == 0 ? (0xFF000000 | c) : c;
    }

    /**
     * Apply the Material-You-accent and OLED-surface layers to a freshly-resolved (copied) palette,
     * in place, and return it. A no-op when both switches are off.
     */
    public static IgThemePalette applyLayers(IgThemePalette p) {
        if (p == null) return null;
        if (FeatureFlags.themeDynamicAccent) {
            int[] s = systemAccent();
            if (s != null) {
                p.accent = s[0];
                p.button = s[0];
                p.link = s[1];
            }
        }
        if (FeatureFlags.themeOledSurfaces) {
            p.background = OLED_BLACK;
            p.surface = OLED_BLACK;
            p.statusBar = OLED_BLACK;
            p.navigation = OLED_BLACK;
            p.divider = OLED_DIVIDER;
            p.border = OLED_DIVIDER;
        }
        return p;
    }
}
