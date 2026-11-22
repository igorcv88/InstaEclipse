package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Profile Declutter (#profile): hides story-highlights trays and promo/banner rows from profile
 * headers by their Instagram resource ids, with one toggle per element.
 *
 * The profile header is built from classic Android views that keep their resource ids (the same
 * ones {@link ProfileFollowsYouHook} relies on), so we hide by id in the view tree rather than
 * patching bytecode. A layout listener on each activity's window re-hides them whenever Instagram
 * rebuilds the header, and every hide is behind its own flag so nothing is touched when off.
 *
 * Elements and their IG 451 anchors (resource names resolved at runtime, so they survive id-number
 * churn between builds):
 *  - highlights tray  → highlights_tray (fallback highlights_reel_tray_recycler_view)
 *  - profile song     → profile_header_music
 *  - bio links row    → profile_links_view, profile_links_inset_banner
 *  - interests/chips  → interests_row, afi_view_container, profile_header_business_category
 *  - cross-app banner → the parent row of profile_header_banner_item_icon (Threads / Facebook /
 *                       WhatsApp badge under the bio)
 *
 * This hides on every profile (yours and other people's); highlights still exist (Add to highlight
 * from a story is unaffected) and links/song still exist in the data, they are only not drawn.
 */
public class ProfileDeclutterHook {
    private static final String TAG = "(IE|ProfileDeclutter) ";
    private static final Set<View> watched = Collections.newSetFromMap(new WeakHashMap<>());

    // Resource ids, resolved once from the first profile/activity we see (0 = not found in this build).
    private static boolean resolved;
    private static int idHighlightsTray, idHighlightsRecycler;
    private static int idMusic;
    private static int idLinksView, idLinksInsetBanner;
    private static int idInterestsRow, idAfiContainer, idBusinessCategory;
    private static int idBannerItemIcon, idBannerRow, idProfileHeaderFixedList, idProfileHeaderContainer;

    public void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    if (!anyOn()) return;
                    View decor = ((Activity) param.thisObject).getWindow().getDecorView();
                    if (!watched.add(decor)) return;
                    decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> scan(decor));
                    scan(decor);
                }
            });
            FeatureStatusTracker.setHooked("HideProfileHighlights");
            FeatureStatusTracker.setHooked("HideProfileSong");
            FeatureStatusTracker.setHooked("HideProfileBioLinks");
            FeatureStatusTracker.setHooked("HideProfileInterests");
            FeatureStatusTracker.setHooked("HideProfileBanners");
            ModuleLog.line(TAG + "✅ installed");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static boolean anyOn() {
        return FeatureFlags.hideProfileHighlights || FeatureFlags.hideProfileSong
                || FeatureFlags.hideProfileBioLinks || FeatureFlags.hideProfileInterests
                || FeatureFlags.hideProfileBanners;
    }

    @SuppressLint("DiscouragedApi")
    private static void resolve(View decor) {
        if (resolved) return;
        try {
            String pkg = decor.getContext().getPackageName();
            android.content.res.Resources r = decor.getResources();
            idHighlightsTray = r.getIdentifier("highlights_tray", "id", pkg);
            idHighlightsRecycler = r.getIdentifier("highlights_reel_tray_recycler_view", "id", pkg);
            idMusic = r.getIdentifier("profile_header_music", "id", pkg);
            idLinksView = r.getIdentifier("profile_links_view", "id", pkg);
            idLinksInsetBanner = r.getIdentifier("profile_links_inset_banner", "id", pkg);
            idInterestsRow = r.getIdentifier("interests_row", "id", pkg);
            idAfiContainer = r.getIdentifier("afi_view_container", "id", pkg);
            idBusinessCategory = r.getIdentifier("profile_header_business_category", "id", pkg);
            idBannerItemIcon = r.getIdentifier("profile_header_banner_item_icon", "id", pkg);
            idBannerRow = r.getIdentifier("banner_row", "id", pkg);
            idProfileHeaderFixedList = r.getIdentifier("profile_header_fixed_list", "id", pkg);
            idProfileHeaderContainer = r.getIdentifier("profile_header_container", "id", pkg);
            resolved = true;
        } catch (Throwable ignored) {}
    }

    private static void scan(View decor) {
        try {
            if (!anyOn()) return;
            resolve(decor);
            if (FeatureFlags.hideProfileHighlights) {
                hideById(decor, idHighlightsTray);
                hideById(decor, idHighlightsRecycler);
            }
            if (FeatureFlags.hideProfileSong) {
                hideById(decor, idMusic);
            }
            if (FeatureFlags.hideProfileBioLinks) {
                hideById(decor, idLinksView);
                hideById(decor, idLinksInsetBanner);
            }
            if (FeatureFlags.hideProfileInterests) {
                hideById(decor, idInterestsRow);
                hideById(decor, idAfiContainer);
                hideById(decor, idBusinessCategory);
            }
            if (FeatureFlags.hideProfileBanners) {
                hideBanners(decor);
            }
        } catch (Throwable ignored) {}
    }

    private static void hideById(View root, int id) {
        if (id == 0) return;
        View v = root.findViewById(id);
        if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
    }

    /**
     * Hides every cross-app banner (Threads / Facebook / WhatsApp badge) in the header by collapsing
     * the parent row of each profile_header_banner_item_icon. Traversal is scoped to the profile
     * header subtree so it stays cheap, and each banner's own item container is hidden so a row with
     * several banners is cleared completely.
     */
    private static void hideBanners(View decor) {
        // Preferred: the whole cross-app banner row is a real view (banner_row) in modern builds.
        hideById(decor, idBannerRow);
        if (idBannerItemIcon == 0) return;
        View root = idProfileHeaderFixedList != 0 ? decor.findViewById(idProfileHeaderFixedList) : null;
        if (root == null && idProfileHeaderContainer != 0) root = decor.findViewById(idProfileHeaderContainer);
        if (root == null) root = decor;
        collectAndHide(root);
    }

    private static void collectAndHide(View v) {
        if (v.getId() == idBannerItemIcon) {
            View parent = (View) v.getParent();
            if (parent != null && parent.getVisibility() != View.GONE) parent.setVisibility(View.GONE);
            return;
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) collectAndHide(g.getChildAt(i));
        }
    }
}
