package io.github.choumax.a1260xiaozhi;

/** Android-free wake/session gate used by the process-lifetime voice service. */
public final class WakeSessionPolicy {
    public enum WakeAction { IGNORE, START_NOW, CANCEL_REPLY_THEN_START, CONNECT_THEN_START }

    private WakeSessionPolicy() { }

    public static boolean shouldRunDetector(SessionState state, boolean enabled, boolean stopped,
                                            boolean pushToTalk, boolean wakeTurnPending) {
        if (!enabled || stopped || pushToTalk || wakeTurnPending) return false;
        return state == SessionState.READY || state == SessionState.WAITING_REPLY
                || state == SessionState.DISCONNECTED || state == SessionState.ERROR;
    }

    public static WakeAction actionForWake(SessionState state, boolean stopped, boolean pushToTalk,
                                           boolean wakeTurnPending, boolean cooldownElapsed) {
        if (stopped || pushToTalk || wakeTurnPending || !cooldownElapsed) return WakeAction.IGNORE;
        if (state == SessionState.READY) return WakeAction.START_NOW;
        if (state == SessionState.WAITING_REPLY) return WakeAction.CANCEL_REPLY_THEN_START;
        if (state == SessionState.DISCONNECTED || state == SessionState.ERROR) return WakeAction.CONNECT_THEN_START;
        return WakeAction.IGNORE;
    }
}
