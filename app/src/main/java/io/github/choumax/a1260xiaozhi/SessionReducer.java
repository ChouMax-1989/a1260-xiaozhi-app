package io.github.choumax.a1260xiaozhi;

/** A small, Android-free transition table used by the controller and unit tests. */
public final class SessionReducer {
    public enum Event { CONNECT, SOCKET_OPEN, HELLO_OK, START_LISTEN, STOP_LISTEN, SEND_TEXT, TTS_START, TTS_STOP,
        DRAINED, STOP, SOCKET_FAILURE, HELLO_TIMEOUT }

    private SessionReducer() { }

    public static boolean resumeMicrophoneAfterDrain(boolean voiceLoopArmed, boolean textTurn, boolean captureActive) {
        return voiceLoopArmed && !textTurn && !captureActive;
    }

    public static Event captureCompletedEvent(boolean wakeTriggered, boolean heardSpeech) {
        return wakeTriggered && !heardSpeech ? Event.STOP : Event.STOP_LISTEN;
    }

    public static SessionState next(SessionState state, Event event) {
        switch (event) {
            case CONNECT: return state == SessionState.DISCONNECTED || state == SessionState.ERROR
                    ? SessionState.CONNECTING : state;
            case SOCKET_OPEN: return state == SessionState.CONNECTING ? SessionState.HELLO_WAIT : state;
            case HELLO_OK: return state == SessionState.HELLO_WAIT ? SessionState.READY : state;
            case START_LISTEN: return state == SessionState.READY ? SessionState.LISTENING : state;
            case STOP_LISTEN: return state == SessionState.LISTENING ? SessionState.WAITING_REPLY : state;
            case SEND_TEXT: return state == SessionState.READY ? SessionState.WAITING_REPLY : state;
            case TTS_START: return (state == SessionState.READY || state == SessionState.WAITING_REPLY || state == SessionState.LISTENING)
                    ? SessionState.SPEAKING : state;
            case TTS_STOP: return state == SessionState.SPEAKING ? SessionState.DRAINING : state;
            case DRAINED: return state == SessionState.DRAINING ? SessionState.READY : state;
            case STOP: return state == SessionState.LISTENING || state == SessionState.WAITING_REPLY
                    || state == SessionState.SPEAKING || state == SessionState.DRAINING ? SessionState.READY : state;
            case SOCKET_FAILURE:
            case HELLO_TIMEOUT: return SessionState.ERROR;
            default: return state;
        }
    }
}
