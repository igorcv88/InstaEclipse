package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Feed Declutter (#feed): per-element toggles that hide individual chrome on home-feed posts by
 * their Instagram resource ids, one flag per element. Nothing is touched while a flag is off.
 *
 * The modern feed is drawn by Litho, so there are no XML row layouts to anchor on, but Instagram
 * still assigns these resource ids to the inflated views, so we hide by id in the live view tree
 * (the same approach {@link ProfileDeclutterHook} takes for profile headers). A layout listener on
 * each activity's window re-hides them whenever Instagram rebuilds or recycles a row. Because a feed
 * is a list, every match is hidden (not just the first), and the Follow / Save / Like matches are
 * scoped to their own feed-row container so the same ids elsewhere (reels, profile, stories) are
 * left alone.
 *
 * Resource names (resolved at runtime so id-number churn between builds doesn't matter) — all
 * verified present in res/values/public.xml on IG 451 AND 452:
 *  - Follow/Subscribe → inline_follow_button, follow_in_header, subscribe_button,
 *                       subscribe_shortcut_button, subscribe_shortcut_stub
 *                       (scoped inside row_feed_profile_header)
 *  - Save            → row_feed_button_save (scoped inside row_feed_view_group_buttons)
 *  - Like            → row_feed_button_like, row_feed_like_count (same container)
 *  - Activity bubbles→ row_feed_collaborative_profile_facepile_stub, feed_su_end_card_facepile_view,
 *                       additional_text_facepiles
 *  - Audio icon      → fixed_media_header_audio_icon_stub, mk_header_audio_icon_view_stub,
 *                       audio_icon, audio_icon_view_stub
 *  - Made with Edits → clips_attribution_label, clips_attribution
 *  - Captions        → media_caption_component, clips_caption_component
 *
 * Fails open: anything thrown while resolving or hiding is swallowed, so the feed never crashes.
 */
public class FeedDeclutterHook {
    private static final String TAG = "(IE|FeedDeclutter) ";
    private static final Set<View> watched = Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean resolved;

    // Scoped containers.
    private static int idFeedHeader;      // row_feed_profile_header
    private static int idButtonsRow;      // row_feed_view_group_buttons

    // Follow / Subscribe (inside the header).
    private static int idInlineFollow, idHeaderFollow, idSubscribe, idSubscribeShortcut, idSubscribeShortcutStub;
    // Save / Like (inside the buttons row). Modern builds render save_button/like_button/like_count;
    // the row_feed_button_* ids are legacy and kept as fallbacks for older clients.
    private static int idSaveButton, idLikeButton, idLikeCount;
    private static int idSaveButtonNew, idSaveCount, idLikeButtonNew;
    // Activity bubbles.
    private static int idCollabFacepileStub, idSuEndCardFacepile, idAdditionalFacepiles;
    // Audio icon.
    private static int idFixedAudioStub, idMkHeaderAudioStub, idAudioIcon, idAudioIconStub;
    // Made with Edits.
    private static int idClipsAttributionLabel, idClipsAttribution;
    // Captions.
    private static int idMediaCaption, idClipsCaption;

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
            FeatureStatusTracker.setHooked("HideFeedFollowButton");
            FeatureStatusTracker.setHooked("HideFeedSaveButton");
            FeatureStatusTracker.setHooked("HideFeedLikeButton");
            FeatureStatusTracker.setHooked("HideFeedActivityBubbles");
            FeatureStatusTracker.setHooked("HideFeedAudioIcon");
            FeatureStatusTracker.setHooked("HideFeedMadeWithEdits");
            FeatureStatusTracker.setHooked("HideFeedCaptions");
            ModuleLog.line(TAG + "✅ installed");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static boolean anyOn() {
        return FeatureFlags.hideFeedFollowButton || FeatureFlags.hideFeedSaveButton
                || FeatureFlags.hideFeedLikeButton || FeatureFlags.hideFeedActivityBubbles
                || FeatureFlags.hideFeedAudioIcon || FeatureFlags.hideFeedMadeWithEdits
                || FeatureFlags.hideFeedCaptions;
    }

    @SuppressLint("DiscouragedApi")
    private static void resolve(View decor) {
        if (resolved) return;
        try {
            String pkg = decor.getContext().getPackageName();
            Resources r = decor.getResources();
            idFeedHeader = r.getIdentifier("row_feed_profile_header", "id", pkg);
            idButtonsRow = r.getIdentifier("row_feed_view_group_buttons", "id", pkg);

            idInlineFollow = r.getIdentifier("inline_follow_button", "id", pkg);
            idHeaderFollow = r.getIdentifier("follow_in_header", "id", pkg);
            idSubscribe = r.getIdentifier("subscribe_button", "id", pkg);
            idSubscribeShortcut = r.getIdentifier("subscribe_shortcut_button", "id", pkg);
            idSubscribeShortcutStub = r.getIdentifier("subscribe_shortcut_stub", "id", pkg);

            idSaveButton = r.getIdentifier("row_feed_button_save", "id", pkg);
            idLikeButton = r.getIdentifier("row_feed_button_like", "id", pkg);
            idLikeCount = r.getIdentifier("row_feed_like_count", "id", pkg);
            idSaveButtonNew = r.getIdentifier("save_button", "id", pkg);
            idSaveCount = r.getIdentifier("save_count", "id", pkg);
            idLikeButtonNew = r.getIdentifier("like_button", "id", pkg);

            idCollabFacepileStub = r.getIdentifier("row_feed_collaborative_profile_facepile_stub", "id", pkg);
            idSuEndCardFacepile = r.getIdentifier("feed_su_end_card_facepile_view", "id", pkg);
            idAdditionalFacepiles = r.getIdentifier("additional_text_facepiles", "id", pkg);

            idFixedAudioStub = r.getIdentifier("fixed_media_header_audio_icon_stub", "id", pkg);
            idMkHeaderAudioStub = r.getIdentifier("mk_header_audio_icon_view_stub", "id", pkg);
            idAudioIcon = r.getIdentifier("audio_icon", "id", pkg);
            idAudioIconStub = r.getIdentifier("audio_icon_view_stub", "id", pkg);

            idClipsAttributionLabel = r.getIdentifier("clips_attribution_label", "id", pkg);
            idClipsAttribution = r.getIdentifier("clips_attribution", "id", pkg);

            idMediaCaption = r.getIdentifier("media_caption_component", "id", pkg);
            idClipsCaption = r.getIdentifier("clips_caption_component", "id", pkg);
            resolved = true;
        } catch (Throwable ignored) {}
    }

    private static void scan(View decor) {
        try {
            if (!anyOn()) return;
            resolve(decor);

            if (FeatureFlags.hideFeedFollowButton) {
                // Prefer scoping to the feed row header; fall back to whole-tree if that container
                // isn't in this build's live view tree (modern feed may not expose it).
                if (!hideWithin(decor, idFeedHeader, idInlineFollow, idHeaderFollow, idSubscribe,
                        idSubscribeShortcut, idSubscribeShortcutStub)) {
                    hideAll(decor, idInlineFollow, idHeaderFollow, idSubscribe, idSubscribeShortcut, idSubscribeShortcutStub);
                }
            }
            if (FeatureFlags.hideFeedSaveButton) {
                // save_button/save_count are feed-specific real views; hide them anywhere in the feed.
                hideAll(decor, idSaveButton, idSaveButtonNew, idSaveCount);
            }
            if (FeatureFlags.hideFeedLikeButton) {
                hideAll(decor, idLikeButton, idLikeButtonNew, idLikeCount);
            }
            if (FeatureFlags.hideFeedActivityBubbles) {
                hideAll(decor, idCollabFacepileStub, idSuEndCardFacepile, idAdditionalFacepiles);
            }
            if (FeatureFlags.hideFeedAudioIcon) {
                hideAll(decor, idFixedAudioStub, idMkHeaderAudioStub, idAudioIcon, idAudioIconStub);
            }
            if (FeatureFlags.hideFeedMadeWithEdits) {
                hideAll(decor, idClipsAttributionLabel, idClipsAttribution);
            }
            if (FeatureFlags.hideFeedCaptions) {
                hideAll(decor, idMediaCaption, idClipsCaption);
            }
        } catch (Throwable ignored) {}
    }

    /** Hides every view in the whole tree whose id is any of {@code ids}. */
    private static void hideAll(View root, int... ids) {
        for (int id : ids) {
            if (id == 0) continue;
            List<View> found = new ArrayList<>();
            collectById(root, id, found);
            for (View v : found) gone(v);
        }
    }

    /**
     * Hides every descendant matching any of {@code targetIds}, but only inside containers whose id
     * is {@code containerId}. A feed is a list, so there are many such containers; each is searched.
     */
    private static boolean hideWithin(View root, int containerId, int... targetIds) {
        if (containerId == 0) return false;
        List<View> containers = new ArrayList<>();
        collectById(root, containerId, containers);
        if (containers.isEmpty()) return false;
        for (View container : containers) {
            for (int id : targetIds) {
                if (id == 0) continue;
                List<View> found = new ArrayList<>();
                collectById(container, id, found);
                for (View v : found) gone(v);
            }
        }
        return true;
    }


    private static void collectById(View v, int id, List<View> out) {
        if (v.getId() == id) out.add(v);
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) collectById(g.getChildAt(i), id, out);
        }
    }

    private static void gone(View v) {
        if (v != null && v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
    }
}
