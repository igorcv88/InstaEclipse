package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Reel content filters (caption words, caption language, low views / likes, AI-labeled), applied
 * while the clips list response is parsed so no empty pages appear. Driven by {@link
 * HideReelsItemsHook}, which calls {@link #shouldHide(Object)} on each parsed Reels-tab media and
 * drops the ones that match (sets the parse result to null).
 *
 * <p>All anchors are the JSON keys the getters read on {@code com.instagram.feed.media.Media}
 * (never obfuscated names), confirmed on IG 451/452:
 * <ul>
 *   <li>{@code like_count} / {@code view_count} / {@code play_count} / {@code ig_play_count}
 *       (each a nullable {@code Integer} getter).</li>
 *   <li>{@code caption} (object getter; its text is the longest plausible String getter on the
 *       caption object).</li>
 *   <li>{@code ai_label_info} (object getter) then {@code should_read_attribution_label} (Boolean)
 *       and {@code attribution_label_text} (String) on the label object: the same test IG makes
 *       before it shows the AI label under a reel.</li>
 * </ul>
 *
 * <p>Fail-open throughout: a missing getter, a missing count, or any error keeps the reel. A reel
 * with no caption / no label / no count is never hidden.
 */
public final class ReelContentFilter {

    private static final String TAG = "(InstaEclipse | ReelContentFilter): ";
    private static final String MEDIA = "com.instagram.feed.media.Media";

    private static volatile Method likeGetter;    // like_count     -> Integer
    private static volatile Method viewGetter;    // view_count     -> Integer
    private static volatile Method playGetter;    // play_count     -> Integer
    private static volatile Method igPlayGetter;  // ig_play_count  -> Integer
    private static volatile Method captionGetter; // caption        -> caption object
    private static volatile Method aiLabelGetter; // ai_label_info  -> label object
    private static volatile List<Method> readsLabelGetters = Collections.emptyList();  // should_read_attribution_label -> Boolean
    private static volatile List<Method> labelTextGetters  = Collections.emptyList();  // attribution_label_text        -> String

    private static volatile boolean resolved;

    /** Longest-String getters per caption-object class, resolved once per class. */
    private static final ConcurrentHashMap<Class<?>, Method[]> CAPTION_STRINGS = new ConcurrentHashMap<>();

    /** Last word-list read and the words it split into, so a page doesn't split it again. */
    private static volatile String parsedWordsSource;
    private static volatile List<String> parsedWords = Collections.emptyList();

    private ReelContentFilter() {}

    /** Idempotent: resolves every Media getter once. */
    public static synchronized void install(DexKitBridge bridge, ClassLoader classLoader) {
        if (resolved) return;
        resolved = true;
        likeGetter   = resolveCountGetter(bridge, classLoader, "ReelLikeGetter", "like_count");
        viewGetter   = resolveCountGetter(bridge, classLoader, "ReelViewGetter", "view_count");
        playGetter   = resolveCountGetter(bridge, classLoader, "ReelPlayGetter", "play_count");
        igPlayGetter = resolveCountGetter(bridge, classLoader, "ReelIgPlayGetter", "ig_play_count");
        captionGetter = resolveObjectGetter(bridge, classLoader, "ReelCaptionGetter", "caption", MEDIA);
        aiLabelGetter = resolveObjectGetter(bridge, classLoader, "ReelAiLabelGetter", "ai_label_info", MEDIA);
        readsLabelGetters = resolveAnywhere(bridge, classLoader, "ReelAiReadsGetter",
                "should_read_attribution_label", "java.lang.Boolean");
        labelTextGetters = resolveAnywhere(bridge, classLoader, "ReelAiTextGetter",
                "attribution_label_text", "java.lang.String");
        ModuleLog.line(TAG + "resolved like=" + ok(likeGetter) + " view=" + ok(viewGetter)
                + " play=" + ok(playGetter) + " igPlay=" + ok(igPlayGetter)
                + " caption=" + ok(captionGetter) + " aiLabel=" + ok(aiLabelGetter)
                + " aiReads=" + readsLabelGetters.size() + " aiText=" + labelTextGetters.size());
    }

    private static String ok(Method m) { return m != null ? "✅" : "❌"; }

    /** Whether at least one reel content filter is switched on. */
    public static boolean anyOn() {
        return FeatureFlags.hideAiReels
                || (FeatureFlags.hideReelsByCaption && !FeatureFlags.reelsBlockedWords.trim().isEmpty())
                || (FeatureFlags.hideReelsByLanguage && !FeatureFlags.reelsBlockedLanguages.trim().isEmpty())
                || (FeatureFlags.hideLowViewReels && (FeatureFlags.reelMinViews > 0 || FeatureFlags.reelMinLikes > 0));
    }

    /** True when at least one getter was found, so the feature can report itself hooked. */
    public static boolean isReady() {
        return likeGetter != null || viewGetter != null || playGetter != null
                || captionGetter != null || aiLabelGetter != null;
    }

    /** True for a reel that matches any enabled filter. Never throws; fails open (false). */
    public static boolean shouldHide(Object media) {
        if (media == null || !anyOn()) return false;
        try {
            if (FeatureFlags.hideAiReels && isAiLabeled(media)) return true;

            boolean byCaption = FeatureFlags.hideReelsByCaption && !FeatureFlags.reelsBlockedWords.trim().isEmpty();
            boolean byLanguage = FeatureFlags.hideReelsByLanguage && !FeatureFlags.reelsBlockedLanguages.trim().isEmpty();
            if (byCaption || byLanguage) {
                String caption = captionText(media);
                if (byCaption && captionHoldsBlocked(caption)) return true;
                if (byLanguage && ReelLanguageMatcher.matches(FeatureFlags.reelsBlockedLanguages, caption)) return true;
            }

            if (FeatureFlags.hideLowViewReels && belowThreshold(media)) return true;
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── AI label ────────────────────────────────────────────────────────────

    private static boolean isAiLabeled(Object media) {
        Object info = call(aiLabelGetter, media);
        if (info == null) return false;
        Object reads = callFirst(readsLabelGetters, info);
        if (!Boolean.TRUE.equals(reads)) return false;
        Object text = callFirst(labelTextGetters, info);
        return text instanceof String && !((String) text).isEmpty();
    }

    // ── Low views / likes ─────────────────────────────────────────────────────

    private static boolean belowThreshold(Object media) {
        int minViews = FeatureFlags.reelMinViews;
        int minLikes = FeatureFlags.reelMinLikes;
        if (minViews > 0) {
            Integer views = firstCount(playGetter, igPlayGetter, viewGetter, media);
            if (views != null && views < minViews) return true;
        }
        if (minLikes > 0) {
            Object likes = call(likeGetter, media);
            if (likes instanceof Integer && ((Integer) likes) < minLikes) return true;
        }
        return false;
    }

    private static Integer firstCount(Method a, Method b, Method c, Object media) {
        Object v = call(a, media);
        if (!(v instanceof Integer)) v = call(b, media);
        if (!(v instanceof Integer)) v = call(c, media);
        return v instanceof Integer ? (Integer) v : null;
    }

    // ── Caption words ─────────────────────────────────────────────────────────

    private static boolean captionHoldsBlocked(String caption) {
        if (caption == null || caption.isEmpty()) return false;
        String text = caption.toLowerCase(Locale.ROOT);
        for (String word : words(FeatureFlags.reelsBlockedWords)) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    /** The blocked words, lower case, split at commas and new lines; cached until the source changes. */
    static List<String> words(String stored) {
        if (stored == null || stored.trim().isEmpty()) return Collections.emptyList();
        if (Objects.equals(stored, parsedWordsSource)) return parsedWords;
        List<String> out = new ArrayList<>();
        for (String piece : stored.split("[,\\n\\r]")) {
            String w = piece.trim().toLowerCase(Locale.ROOT);
            if (!w.isEmpty() && !out.contains(w)) out.add(w);
        }
        List<String> fixed = Collections.unmodifiableList(out);
        parsedWords = fixed;
        parsedWordsSource = stored;
        return fixed;
    }

    /** The reel's caption text: the longest plausible String getter on the caption object, or null. */
    static String captionText(Object media) {
        Object caption = call(captionGetter, media);
        if (caption == null) return null;
        String best = null;
        for (Method m : captionStringGetters(caption.getClass())) {
            try {
                Object v = m.invoke(caption);
                if (v instanceof String) {
                    String s = (String) v;
                    if (s.length() >= 2 && (best == null || s.length() > best.length())) best = s;
                }
            } catch (Throwable ignored) {}
        }
        return best;
    }

    private static Method[] captionStringGetters(Class<?> c) {
        Method[] cached = CAPTION_STRINGS.get(c);
        if (cached != null) return cached;
        List<Method> out = new ArrayList<>();
        for (Method m : SafeReflect.publicMethods(c)) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != String.class) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            try { m.setAccessible(true); out.add(m); } catch (Throwable ignored) {}
        }
        Method[] arr = out.toArray(new Method[0]);
        CAPTION_STRINGS.put(c, arr);
        return arr;
    }

    // ── Getter resolution ──────────────────────────────────────────────────────

    private static Object call(Method getter, Object target) {
        if (getter == null || target == null) return null;
        try {
            if (!getter.getDeclaringClass().isInstance(target)) return null;
            return getter.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Invokes the first getter whose declaring class matches [target]. */
    private static Object callFirst(List<Method> getters, Object target) {
        if (target == null) return null;
        for (Method m : getters) {
            if (!m.getDeclaringClass().isInstance(target)) continue;
            try { return m.invoke(target); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Media's nullable-Integer count getter guarded by [jsonKey]. */
    private static Method resolveCountGetter(DexKitBridge bridge, ClassLoader cl, String cacheKey, String jsonKey) {
        try {
            if (DexKitCache.isCacheValid()) {
                Method cached = DexKitCache.loadMethod(cacheKey, cl);
                if (cached != null) return cached;
            }
            List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create().declaredClass(MEDIA).paramCount(0)
                            .usingEqStrings(List.of(jsonKey)).returnType("java.lang.Integer")));
            for (MethodData md : found) {
                try {
                    Method m = md.getMethodInstance(cl);
                    if (m.getParameterCount() != 0 || m.getReturnType() != Integer.class) continue;
                    m.setAccessible(true);
                    DexKitCache.saveMethod(cacheKey, m);
                    return m;
                } catch (Throwable ignored) {}
            }
            ModuleLog.line(TAG + "⚠️ " + jsonKey + " getter not found");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + jsonKey + ": " + t.getMessage());
        }
        return null;
    }

    /** An object (non-primitive, non-String, non-boolean) getter on [owner] guarded by [jsonKey]. */
    private static Method resolveObjectGetter(DexKitBridge bridge, ClassLoader cl, String cacheKey,
                                              String jsonKey, String owner) {
        try {
            if (DexKitCache.isCacheValid()) {
                Method cached = DexKitCache.loadMethod(cacheKey, cl);
                if (cached != null) return cached;
            }
            List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create().declaredClass(owner).paramCount(0).usingEqStrings(List.of(jsonKey))));
            for (MethodData md : found) {
                if (md.getName().equals("<clinit>")) continue;
                try {
                    Method m = md.getMethodInstance(cl);
                    if (m.getParameterCount() != 0 || !isModelReturn(m.getReturnType())) continue;
                    m.setAccessible(true);
                    DexKitCache.saveMethod(cacheKey, m);
                    return m;
                } catch (Throwable ignored) {}
            }
            ModuleLog.line(TAG + "⚠️ " + jsonKey + " getter not found");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + jsonKey + ": " + t.getMessage());
        }
        return null;
    }

    /**
     * Every 0-arg getter guarded by [jsonKey] returning [returnType], across whatever class(es)
     * declare it (the AI-label model is not stably named). The right one is picked at call time by
     * its declaring class.
     */
    private static List<Method> resolveAnywhere(DexKitBridge bridge, ClassLoader cl, String cacheKey,
                                                String jsonKey, String returnType) {
        try {
            if (DexKitCache.isCacheValid()) {
                List<Method> cached = DexKitCache.loadMethods(cacheKey, cl);
                if (cached != null && !cached.isEmpty()) return cached;
            }
            List<Method> out = new ArrayList<>();
            List<MethodData> found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(
                    MethodMatcher.create().paramCount(0).usingEqStrings(List.of(jsonKey)).returnType(returnType)));
            for (MethodData md : found) {
                try {
                    Method m = md.getMethodInstance(cl);
                    if (m.getParameterCount() != 0) continue;
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    m.setAccessible(true);
                    out.add(m);
                    if (out.size() >= 8) break;
                } catch (Throwable ignored) {}
            }
            if (!out.isEmpty()) DexKitCache.saveMethods(cacheKey, out);
            else ModuleLog.line(TAG + "⚠️ " + jsonKey + " getter not found");
            return out;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + jsonKey + ": " + t.getMessage());
            return Collections.emptyList();
        }
    }

    /** A model object return (not void, primitive, String, boxed primitive, or java collection). */
    private static boolean isModelReturn(Class<?> t) {
        if (t == void.class || t.isPrimitive()) return false;
        String n = t.getName();
        return !n.startsWith("java.");
    }
}
