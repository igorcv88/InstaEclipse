package ps.reso.instaeclipse.mods.feed;

import android.content.Context;
import android.icu.lang.UScript;
import android.icu.util.ULocale;
import android.os.Build;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a reel caption is written in one of the languages the user listed to hide
 * ("Hide reels by language"). Two layers, each fails open:
 * <ul>
 *   <li><b>Script</b> (every Android): a caption written mostly in a script that belongs to a
 *       listed non-Latin language (Arabic, Devanagari, Han, Cyrillic, Thai, ...) is hidden. Works
 *       without any classifier and never mistakes one non-Latin language for another.</li>
 *   <li><b>Language</b> (API 29+): when a listed language uses the Latin script (Spanish, French,
 *       ...), Android's own text classifier names the caption's language; it hides only when it is
 *       sure ({@link #MIN_CONFIDENCE}). A missing classifier, a low score or any error keeps the
 *       reel.</li>
 * </ul>
 *
 * <p>A caption with fewer than {@link #MIN_LETTERS} letters (after hashtags, mentions, links,
 * emoji, digits and punctuation are dropped) can't be told and stays.
 */
public final class ReelLanguageMatcher {

    static final int MIN_LETTERS = 12;
    static final float MIN_CONFIDENCE = 0.6f;
    private static final int MAX_TEXT = 600;

    private static final Pattern LINKS = Pattern.compile("(?i)(?:https?://|www\\.)\\S+");
    private static final Pattern TAGS = Pattern.compile("[#\\x{FF03}@\\x{FF20}][\\p{L}\\p{N}\\p{M}_.]+");

    private ReelLanguageMatcher() {}

    /** One reading of the setting: the codes to hide and their non-Latin scripts. */
    private static final class Rule {
        final Set<String> codes;
        final Set<Integer> nonLatinScripts;
        final boolean hasLatin;

        Rule(Set<String> codes, Set<Integer> nonLatinScripts, boolean hasLatin) {
            this.codes = codes;
            this.nonLatinScripts = nonLatinScripts;
            this.hasLatin = hasLatin;
        }

        boolean active() {
            return !codes.isEmpty();
        }
    }

    private static volatile String parsedSource;
    private static volatile Rule parsedRule = new Rule(Collections.emptySet(), Collections.emptySet(), false);

    /** True when [caption] is in one of the languages listed in [stored]. Fails open (false). */
    public static boolean matches(String stored, String caption) {
        if (caption == null || caption.isEmpty()) return false;
        try {
            Rule rule = ruleFor(stored);
            if (!rule.active()) return false;
            String text = clean(caption);
            if (letters(text) < MIN_LETTERS) return false;
            if (mostlyInScripts(text, rule.nonLatinScripts)) return true;
            if (rule.hasLatin && Build.VERSION.SDK_INT >= 29) {
                String sample = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
                String language = classify(sample);
                if (language != null && rule.codes.contains(language)) return true;
            }
            return false;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static Rule ruleFor(String stored) {
        if (Objects.equals(stored, parsedSource)) return parsedRule;
        Rule rule = parse(stored);
        parsedRule = rule;
        parsedSource = stored;
        return rule;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private static Rule parse(String stored) {
        Set<String> codes = new HashSet<>();
        if (stored != null) {
            for (String piece : stored.split("[,\\n\\r]")) {
                String trimmed = piece.trim();
                if (trimmed.isEmpty()) continue;
                String code = codeOf(trimmed);
                if (code != null) codes.add(code);
            }
        }
        if (codes.isEmpty()) return new Rule(Collections.emptySet(), Collections.emptySet(), false);
        Set<Integer> nonLatin = new HashSet<>();
        boolean hasLatin = false;
        for (String code : codes) {
            Set<Integer> scripts = scriptsOf(code);
            if (scripts.contains(UScript.LATIN)) hasLatin = true;
            for (int s : scripts) {
                if (s != UScript.LATIN && s != UScript.COMMON && s != UScript.INHERITED && s != UScript.UNKNOWN) {
                    nonLatin.add(s);
                }
            }
        }
        return new Rule(Collections.unmodifiableSet(codes), Collections.unmodifiableSet(nonLatin), hasLatin);
    }

    /** The language code for a typed piece (ISO code, English name or native name), or null. */
    static String codeOf(String piece) {
        String lower = piece.toLowerCase(Locale.ROOT);
        String base = lower.split("[-_]", 2)[0];
        if (base.matches("[a-z]{2,3}") && isoCodes().contains(normalize(base))) return normalize(base);
        String byName = nameTable().get(lower);
        return byName != null ? byName : null;
    }

    private static String normalize(String code) {
        if (code == null) return "";
        String lower = code.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            case "nb":
            case "nn": return "no";
            default: return lower;
        }
    }

    private static volatile Set<String> iso;

    private static Set<String> isoCodes() {
        Set<String> known = iso;
        if (known == null) {
            known = new HashSet<>();
            for (String code : Locale.getISOLanguages()) known.add(normalize(code));
            iso = known;
        }
        return known;
    }

    private static volatile Map<String, String> names;

    private static Map<String, String> nameTable() {
        Map<String, String> table = names;
        if (table != null) return table;
        table = new HashMap<>();
        for (String raw : Locale.getISOLanguages()) {
            String code = normalize(raw);
            Locale locale = Locale.forLanguageTag(raw);
            put(table, locale.getDisplayLanguage(Locale.ENGLISH), code);
            put(table, locale.getDisplayLanguage(locale), code);
        }
        names = table;
        return table;
    }

    private static void put(Map<String, String> table, String name, String code) {
        if (name == null || name.isEmpty()) return;
        String key = name.toLowerCase(Locale.ROOT);
        if (!table.containsKey(key)) table.put(key, code);
    }

    private static Set<Integer> scriptsOf(String code) {
        Set<Integer> found = new HashSet<>();
        try {
            String script = ULocale.addLikelySubtags(new ULocale(code)).getScript();
            if (script == null || script.isEmpty()) return found;
            int[] ids = UScript.getCode(script);
            if (ids != null) for (int id : ids) found.add(id);
            switch (script) {
                case "Jpan":
                    found.add(UScript.HAN);
                    found.add(UScript.HIRAGANA);
                    found.add(UScript.KATAKANA);
                    break;
                case "Kore":
                    found.add(UScript.HANGUL);
                    found.add(UScript.HAN);
                    break;
                case "Hans":
                case "Hant":
                    found.add(UScript.HAN);
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {}
        return found;
    }

    // ── Cleaning and scripts ────────────────────────────────────────────────────

    static String clean(String caption) {
        String text = LINKS.matcher(caption).replaceAll(" ");
        text = TAGS.matcher(text).replaceAll(" ");
        StringBuilder out = new StringBuilder(text.length());
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            at += Character.charCount(point);
            if (Character.isLetter(point)) out.appendCodePoint(point);
            else out.append(' ');
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    private static int letters(String text) {
        int count = 0;
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            at += Character.charCount(point);
            if (Character.isLetter(point)) count++;
        }
        return count;
    }

    /** Whether most of [text]'s script-bearing letters are in one of [scripts]. */
    private static boolean mostlyInScripts(String text, Set<Integer> scripts) {
        if (scripts.isEmpty()) return false;
        int total = 0;
        int inside = 0;
        for (int at = 0; at < text.length(); ) {
            int point = text.codePointAt(at);
            at += Character.charCount(point);
            if (!Character.isLetter(point)) continue;
            int script = UScript.getScript(point);
            if (script == UScript.COMMON || script == UScript.INHERITED || script == UScript.UNKNOWN) continue;
            total++;
            if (scripts.contains(script)) inside++;
        }
        return total > 0 && inside * 2 > total;
    }

    // ── Classifier ──────────────────────────────────────────────────────────────

    private static String classify(String text) {
        try {
            Context context = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
            if (context == null) return null;
            TextClassificationManager manager = context.getSystemService(TextClassificationManager.class);
            if (manager == null) return null;
            TextClassifier classifier = manager.getTextClassifier();
            TextLanguage result = classifier.detectLanguage(new TextLanguage.Request.Builder(text).build());
            String best = null;
            float top = 0f;
            for (int i = 0; i < result.getLocaleHypothesisCount(); i++) {
                ULocale locale = result.getLocale(i);
                float score = result.getConfidenceScore(locale);
                if (score > top) {
                    top = score;
                    best = locale.getLanguage();
                }
            }
            if (best == null || top < MIN_CONFIDENCE) return null;
            return normalize(best);
        } catch (Throwable failure) {
            return null;
        }
    }
}
