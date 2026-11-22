package ps.reso.instaeclipse.mods.ghost;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SeenDispatchPolicyTest {
    @Test public void queuedHiddenReceiptStaysHiddenAfterGhostModeTurnsOff() {
        assertFalse(SeenDispatchPolicy.shouldSend(false, false, false));
    }

    @Test public void queuedAllowedReceiptStaysAllowedAfterGhostModeTurnsOn() {
        assertTrue(SeenDispatchPolicy.shouldSend(true, true, false));
    }

    @Test public void unknownReceiptUsesCurrentGhostSetting() {
        assertFalse(SeenDispatchPolicy.shouldSend(null, true, false));
        assertTrue(SeenDispatchPolicy.shouldSend(null, false, false));
    }

    @Test public void unknownReceiptHonorsPerChatException() {
        assertTrue(SeenDispatchPolicy.shouldSend(null, true, true));
    }

    @Test public void explicitQueuedDecisionOverridesLaterWhitelistChanges() {
        assertFalse(SeenDispatchPolicy.shouldSend(false, true, true));
        assertTrue(SeenDispatchPolicy.shouldSend(true, true, false));
    }
}
