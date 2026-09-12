package io.github.choumax.a1260xiaozhi;

/** Android-free reconnect state machine owned by the process-lifetime session service. */
public final class ReconnectPolicy {
    private static final long[] DELAYS_MS = {2_000, 5_000, 10_000, 20_000, 30_000, 60_000};

    public enum Action { NONE, SCHEDULE, CONNECT_NOW }

    public static final class Decision {
        public final Action action;
        public final long delayMillis;
        public final int attempt;
        private Decision(Action action, long delayMillis, int attempt) {
            this.action = action; this.delayMillis = delayMillis; this.attempt = attempt;
        }
    }

    private boolean networkAvailable, enabled, connected, waiting, awaitingNetwork, blockedForUserAction;
    private int attempts;

    public ReconnectPolicy(boolean networkAvailable) { this.networkAvailable = networkAvailable; }

    public Decision onServiceStart(boolean hasConfig) {
        enabled = hasConfig; connected = waiting = awaitingNetwork = blockedForUserAction = false; attempts = 0;
        if (!enabled) return none();
        if (!networkAvailable) { awaitingNetwork = true; return none(); }
        return connectNow();
    }

    public Decision onUserConnect() {
        enabled = true; connected = waiting = awaitingNetwork = blockedForUserAction = false; attempts = 0;
        if (!networkAvailable) { awaitingNetwork = true; return none(); }
        return connectNow();
    }

    public void onUserDisconnect() { disable(); }
    public void onServiceStop() { disable(); }

    public void onConnected() {
        if (!enabled) return;
        connected = true; waiting = awaitingNetwork = blockedForUserAction = false;
    }
    public void onConnectionStable() { if (enabled && connected) attempts = 0; }

    public Decision onTerminal(ConnectionDiagnostic.Recovery recovery) {
        connected = false;
        if (!enabled || waiting || blockedForUserAction) return none();
        if (recovery == ConnectionDiagnostic.Recovery.USER_ACTION) {
            blockedForUserAction = true; awaitingNetwork = false; return none();
        }
        if (recovery != ConnectionDiagnostic.Recovery.RETRY_BACKOFF
                && recovery != ConnectionDiagnostic.Recovery.ON_DEMAND) return none();
        if (!networkAvailable) { awaitingNetwork = true; return none(); }
        int index = Math.min(attempts, DELAYS_MS.length - 1);
        long delay = DELAYS_MS[index]; attempts = Math.min(attempts + 1, DELAYS_MS.length); waiting = true;
        return new Decision(Action.SCHEDULE, delay, attempts);
    }

    public Decision onRetryTimer() {
        if (!waiting) return none();
        waiting = false;
        if (!enabled || connected || blockedForUserAction) return none();
        if (!networkAvailable) { awaitingNetwork = true; return none(); }
        return connectNow();
    }

    public void onNetworkLost() {
        networkAvailable = false; connected = false;
        if (enabled && !blockedForUserAction) { waiting = false; awaitingNetwork = true; }
    }

    public Decision onNetworkAvailable() {
        boolean recovered = !networkAvailable;
        networkAvailable = true;
        if (!enabled || connected || waiting || blockedForUserAction) return none();
        if (!recovered || !awaitingNetwork) return none();
        attempts = 0; awaitingNetwork = false;
        return connectNow();
    }

    public boolean isEnabled() { return enabled; }
    public boolean isConnected() { return connected; }
    public boolean isBlockedForUserAction() { return blockedForUserAction; }
    public boolean isWaiting() { return waiting; }
    public int attempts() { return attempts; }

    private void disable() {
        enabled = connected = waiting = awaitingNetwork = blockedForUserAction = false; attempts = 0;
    }
    private Decision none() { return new Decision(Action.NONE, 0, attempts); }
    private Decision connectNow() { return new Decision(Action.CONNECT_NOW, 0, attempts); }
}
