package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WakeSessionPolicyTest {
    @Test public void waitingForReplyKeepsDetectorAvailableAndCancelsOldTurnOnHit() {
        assertTrue(WakeSessionPolicy.shouldRunDetector(SessionState.WAITING_REPLY, true, false, false, false));
        assertEquals(WakeSessionPolicy.WakeAction.CANCEL_REPLY_THEN_START,
                WakeSessionPolicy.actionForWake(SessionState.WAITING_REPLY, false, false, false, true));
    }

    @Test public void playbackAndCaptureStatesPauseWakeToAvoidEcho() {
        for (SessionState state : new SessionState[]{SessionState.LISTENING, SessionState.SPEAKING, SessionState.DRAINING}) {
            assertFalse(WakeSessionPolicy.shouldRunDetector(state, true, false, false, false));
            assertEquals(WakeSessionPolicy.WakeAction.IGNORE,
                    WakeSessionPolicy.actionForWake(state, false, false, false, true));
        }
    }

    @Test public void disconnectedWakeRequestsReconnectWhileReadyWakeStartsImmediately() {
        assertTrue(WakeSessionPolicy.shouldRunDetector(SessionState.ERROR, true, false, false, false));
        assertTrue(WakeSessionPolicy.shouldRunDetector(SessionState.DISCONNECTED, true, false, false, false));
        assertEquals(WakeSessionPolicy.WakeAction.CONNECT_THEN_START,
                WakeSessionPolicy.actionForWake(SessionState.ERROR, false, false, false, true));
        assertEquals(WakeSessionPolicy.WakeAction.START_NOW,
                WakeSessionPolicy.actionForWake(SessionState.READY, false, false, false, true));
    }

    @Test public void pendingTurnPushToTalkAndCooldownRejectDuplicateHits() {
        assertEquals(WakeSessionPolicy.WakeAction.IGNORE,
                WakeSessionPolicy.actionForWake(SessionState.READY, false, false, true, true));
        assertEquals(WakeSessionPolicy.WakeAction.IGNORE,
                WakeSessionPolicy.actionForWake(SessionState.READY, false, true, false, true));
        assertEquals(WakeSessionPolicy.WakeAction.IGNORE,
                WakeSessionPolicy.actionForWake(SessionState.READY, false, false, false, false));
    }
}
