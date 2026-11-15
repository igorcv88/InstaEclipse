package ps.reso.instaeclipse.mods.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/**
 * Decides whether an incoming Instagram call rings (incoming-call blocker).
 *
 * <p>Instagram turns an incoming call into its ring in one method (see {@link CallBlockerHook}),
 * which this is asked first with the call object. A yes skips the ring, so the phone stays quiet and
 * nothing is sent to Instagram: the caller hears a call nobody picks up.
 *
 * <p>The call object is one of Instagram's own data classes; only its {@code toString()} is read,
 * which looks like {@code RtcCallConnectionEntity(state=Incoming, callerId=..., callerName=...,
 * displayUri=...)}. Only a ringing call to you counts; everything else (ended calls, live invites,
 * calls moved from another device) rings as usual.
 *
 * <p>Four modes: 0 everyone (off), 1 nobody, 2 only the list, 3 everyone except the list. The list
 * holds caller names (matched case-insensitively) or numeric user ids, one per line. Fails open:
 * anything unexpected lets the call ring, and a ringing call whose caller can't be read also rings,
 * so a list can never silence someone you meant to hear.
 */
public final class CallBlocker {

    public static final int EVERYONE   = 0; // off
    public static final int NOBODY     = 1;
    public static final int ONLY_LIST  = 2;
    public static final int EXCEPT_LIST = 3;

    /** How a ringing call's text starts; the class name that says it's a call at all. */
    private static final String RINGING = "RtcCallConnectionEntity(state=Incoming,";

    /** Matches the start of the next ", field=" pair in the data-class toString. */
    private static final java.util.regex.Pattern NEXT_FIELD = java.util.regex.Pattern.compile(", [A-Za-z0-9]+=");

    private CallBlocker() {}

    private static boolean usesList(int mode) {
        return mode == ONLY_LIST || mode == EXCEPT_LIST;
    }

    /** Asked first with each call Instagram is about to ring. True means don't ring. Never throws. */
    public static boolean drop(Object call) {
        try {
            if (call == null) return false;
            String text = String.valueOf(call);
            if (!text.startsWith(RINGING)) return false;
            // Per-chat block list (toggled from the thread menu) always applies, even when the
            // global mode is Everyone (off).
            List<String> perChat = accounts(FeatureFlags.callBlockChats);
            if (!perChat.isEmpty() && matchesCaller(text, perChat)) return true;
            int mode = FeatureFlags.callBlockMode;
            if (mode == EVERYONE) return false;
            return drops(mode, text, usesList(mode) ? accounts(FeatureFlags.callBlockAccounts) : Collections.emptyList());
        } catch (Throwable failure) {
            return false; // fail open: let the call ring
        }
    }

    /** Whether this ringing call's caller (name or id) is in {@code list}. */
    static boolean matchesCaller(String text, List<String> list) {
        String name = field(text, "callerName");
        String id = field(text, "callerId");
        return (name != null && list.contains(name.toLowerCase(Locale.ROOT)))
                || (id != null && list.contains(id.toLowerCase(Locale.ROOT)));
    }

    // ---- Per-chat block list (thread menu) ---------------------------------------------------

    /** Whether this chat (by its header title) is on the per-chat call block list. */
    public static boolean isChatBlocked(String title) {
        String n = normalize(title);
        return n != null && accounts(FeatureFlags.callBlockChats).contains(n);
    }

    /** Returns the per-chat block list with {@code title} added or removed (display form kept). */
    public static String toggleChat(String title, boolean blocked) {
        String n = normalize(title);
        if (n == null) return FeatureFlags.callBlockChats;
        List<String> kept = new ArrayList<>();
        for (String piece : FeatureFlags.callBlockChats.split("[\\n\\r]")) {
            String t = piece.trim();
            if (!t.isEmpty() && !n.equals(normalize(t))) kept.add(t);
        }
        if (blocked) kept.add(title.trim());
        return String.join("\n", kept);
    }

    /** Whether a ringing call with this text stays quiet. A caller the list can't be checked against rings. */
    static boolean drops(int mode, String text, List<String> list) {
        if (mode == NOBODY) return true;
        String name = field(text, "callerName");
        String id = field(text, "callerId");
        if (name == null && id == null) return false; // caller unreadable → let it ring
        boolean listed = (name != null && list.contains(name.toLowerCase(Locale.ROOT)))
                || (id != null && list.contains(id.toLowerCase(Locale.ROOT)));
        return mode == ONLY_LIST ? !listed : listed;
    }

    /**
     * Value of {@code <key>=} in the call's data-class toString, read up to the next ", field="
     * boundary (values like callerName can be followed by other fields such as callTarget, so a fixed
     * end-marker is wrong). Null when absent, empty, or literally "null".
     */
    static String field(String text, String key) {
        String marker = ", " + key + "=";
        int from = text.indexOf(marker);
        if (from < 0) return null;
        from += marker.length();
        java.util.regex.Matcher m = NEXT_FIELD.matcher(text);
        int to = m.find(from) ? m.start() : text.length();
        String value = text.substring(from, Math.max(from, to)).trim();
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    /** Usernames/ids from the stored list: one per line (commas work too), trimmed of a leading @, lower-cased, deduped. */
    static List<String> accounts(String stored) {
        if (stored == null || stored.trim().isEmpty()) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (String piece : stored.split("[,\\n\\r]")) {
            String name = normalize(piece);
            if (name != null && !names.contains(name)) names.add(name);
        }
        return names;
    }

    private static String normalize(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        while (trimmed.startsWith("@")) trimmed = trimmed.substring(1).trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
