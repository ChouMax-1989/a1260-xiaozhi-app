package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class ReconnectPolicyTest {
    @Test public void normalWebSocketCloseReconnectsAndReturnsToConnected() {
        ReconnectPolicy policy = new ReconnectPolicy(true);
        assertEquals(ReconnectPolicy.Action.CONNECT_NOW, policy.onServiceStart(true).action);
        policy.onConnected();

        ReconnectPolicy.Decision retry = policy.onTerminal(ConnectionDiagnostic.Recovery.ON_DEMAND);
        assertEquals(ReconnectPolicy.Action.SCHEDULE, retry.action);
        assertEquals(2_000, retry.delayMillis);
        assertEquals(1, retry.attempt);
        assertEquals(ReconnectPolicy.Action.CONNECT_NOW, policy.onRetryTimer().action);

        policy.onConnected(); policy.onConnectionStable();
        assertTrue(policy.isConnected());
        assertEquals(0, policy.attempts());
    }

    @Test public void serverRecoveryAfterSeveralMinutesStillReconnectsWithoutUserAction() {
        ReconnectPolicy policy = new ReconnectPolicy(true);
        policy.onServiceStart(true);
        long[] expected = {2_000, 5_000, 10_000, 20_000, 30_000, 60_000, 60_000, 60_000};
        for (int i = 0; i < expected.length; i++) {
            ReconnectPolicy.Decision retry = policy.onTerminal(ConnectionDiagnostic.Recovery.RETRY_BACKOFF);
            assertEquals(ReconnectPolicy.Action.SCHEDULE, retry.action);
            assertEquals(expected[i], retry.delayMillis);
            assertEquals(ReconnectPolicy.Action.CONNECT_NOW, policy.onRetryTimer().action);
        }
        policy.onConnected(); policy.onConnectionStable();
        assertTrue(policy.isConnected());
        assertEquals(0, policy.attempts());
    }

    @Test public void immediatelyClosingServerEscalatesInsteadOfLoopingEveryTwoSeconds() {
        ReconnectPolicy policy = new ReconnectPolicy(true);
        policy.onServiceStart(true); policy.onConnected();
        assertEquals(2_000, policy.onTerminal(ConnectionDiagnostic.Recovery.ON_DEMAND).delayMillis);
        policy.onRetryTimer(); policy.onConnected();
        assertEquals(5_000, policy.onTerminal(ConnectionDiagnostic.Recovery.ON_DEMAND).delayMillis);
    }

    @Test public void offlineFailureWaitsAndNetworkRecoveryConnectsImmediately() {
        ReconnectPolicy policy = new ReconnectPolicy(true);
        policy.onServiceStart(true); policy.onConnected(); policy.onNetworkLost();
        assertEquals(ReconnectPolicy.Action.NONE,
                policy.onTerminal(ConnectionDiagnostic.Recovery.RETRY_BACKOFF).action);
        assertEquals(ReconnectPolicy.Action.CONNECT_NOW, policy.onNetworkAvailable().action);
    }

    @Test public void duplicateTerminalEventsCannotQueueDuplicateRetries() {
        ReconnectPolicy policy = new ReconnectPolicy(true);
        policy.onServiceStart(true); policy.onConnected();
        assertEquals(ReconnectPolicy.Action.SCHEDULE,
                policy.onTerminal(ConnectionDiagnostic.Recovery.RETRY_BACKOFF).action);
        assertEquals(ReconnectPolicy.Action.NONE,
                policy.onTerminal(ConnectionDiagnostic.Recovery.RETRY_BACKOFF).action);
        assertEquals(1, policy.attempts());
    }

    @Test public void userDisconnectAndServiceStopNeverReconnect() {
        ReconnectPolicy policy = new ReconnectPolicy(true);
        policy.onServiceStart(true); policy.onConnected(); policy.onUserDisconnect();
        assertEquals(ReconnectPolicy.Action.NONE,
                policy.onTerminal(ConnectionDiagnostic.Recovery.ON_DEMAND).action);
        assertEquals(ReconnectPolicy.Action.NONE, policy.onNetworkAvailable().action);
        assertFalse(policy.isEnabled());

        policy.onUserConnect(); policy.onServiceStop();
        assertEquals(ReconnectPolicy.Action.NONE,
                policy.onTerminal(ConnectionDiagnostic.Recovery.RETRY_BACKOFF).action);
    }

    @Test public void authenticationFailureWaitsForExplicitUserAction() {
        ReconnectPolicy policy = new ReconnectPolicy(true);
        policy.onServiceStart(true);
        assertEquals(ReconnectPolicy.Action.NONE,
                policy.onTerminal(ConnectionDiagnostic.Recovery.USER_ACTION).action);
        policy.onNetworkLost();
        assertEquals(ReconnectPolicy.Action.NONE, policy.onNetworkAvailable().action);
        assertTrue(policy.isBlockedForUserAction());
        assertEquals(ReconnectPolicy.Action.CONNECT_NOW, policy.onUserConnect().action);
    }
}
