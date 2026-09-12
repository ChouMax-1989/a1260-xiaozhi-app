package io.github.choumax.a1260xiaozhi;

/** States are only changed by SessionController's control HandlerThread. */
public enum SessionState {
    DISCONNECTED, CONNECTING, HELLO_WAIT, READY, LISTENING, WAITING_REPLY, SPEAKING, DRAINING, ERROR
}
