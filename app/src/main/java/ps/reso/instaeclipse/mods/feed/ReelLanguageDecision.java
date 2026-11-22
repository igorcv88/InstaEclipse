package ps.reso.instaeclipse.mods.feed;

import java.util.Set;

/** Script detection only narrows candidates; the detected language must match explicitly. */
final class ReelLanguageDecision {
    private ReelLanguageDecision() {}

    static boolean shouldHide(Set<String> blocked, boolean scriptCandidate, String detectedLanguage) {
        return scriptCandidate && detectedLanguage != null
                && blocked != null && blocked.contains(detectedLanguage);
    }
}
