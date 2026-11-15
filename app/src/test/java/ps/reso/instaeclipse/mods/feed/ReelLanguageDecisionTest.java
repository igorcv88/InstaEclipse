package ps.reso.instaeclipse.mods.feed;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class ReelLanguageDecisionTest {
    @Test public void russianFilterDoesNotHideUkrainian() {
        assertFalse(ReelLanguageDecision.shouldHide(Set.of("ru"), true, "uk"));
        assertFalse(ReelLanguageDecision.shouldHide(Set.of("ru"), true, "bg"));
        assertTrue(ReelLanguageDecision.shouldHide(Set.of("ru"), true, "ru"));
    }

    @Test public void hindiFilterDoesNotHideMarathi() {
        assertFalse(ReelLanguageDecision.shouldHide(Set.of("hi"), true, "mr"));
        assertTrue(ReelLanguageDecision.shouldHide(Set.of("hi"), true, "hi"));
    }

    @Test public void unknownClassifierAndIneligibleScriptFailOpen() {
        assertFalse(ReelLanguageDecision.shouldHide(Set.of("ar"), true, null));
        assertFalse(ReelLanguageDecision.shouldHide(Set.of("ar"), false, "ar"));
        assertFalse(ReelLanguageDecision.shouldHide(Set.of(), true, "ar"));
    }

    @Test public void latinLanguageMustMatchClassifier() {
        assertFalse(ReelLanguageDecision.shouldHide(Set.of("es"), true, "fr"));
        assertTrue(ReelLanguageDecision.shouldHide(Set.of("es"), true, "es"));
    }
}
