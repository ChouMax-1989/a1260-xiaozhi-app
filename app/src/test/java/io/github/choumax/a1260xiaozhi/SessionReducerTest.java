package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SessionReducerTest {
    @Test public void serverReplyWhileListeningMustAcceptBinaryAudio() {
        assertEquals(SessionState.SPEAKING, SessionReducer.next(SessionState.LISTENING, SessionReducer.Event.TTS_START));
    }
    @Test public void textTurnWaitsForReplyWithoutStartingMicrophone() {
        assertEquals(SessionState.WAITING_REPLY, SessionReducer.next(SessionState.READY, SessionReducer.Event.SEND_TEXT));
        assertEquals(SessionState.HELLO_WAIT, SessionReducer.next(SessionState.HELLO_WAIT, SessionReducer.Event.SEND_TEXT));
        org.junit.Assert.assertFalse(SessionReducer.resumeMicrophoneAfterDrain(true, true, false));
        org.junit.Assert.assertTrue(SessionReducer.resumeMicrophoneAfterDrain(true, false, false));
        org.junit.Assert.assertFalse(SessionReducer.resumeMicrophoneAfterDrain(true, false, true));
        org.junit.Assert.assertFalse(SessionReducer.resumeMicrophoneAfterDrain(false, false, false));
    }
    @Test public void stopCannotInventAReadyConnection() {
        assertEquals(SessionState.ERROR, SessionReducer.next(SessionState.ERROR, SessionReducer.Event.STOP));
        assertEquals(SessionState.HELLO_WAIT, SessionReducer.next(SessionState.HELLO_WAIT, SessionReducer.Event.STOP));
    }
    @Test public void happyPathIsSerialized() {
        SessionState state = SessionState.DISCONNECTED;
        state = SessionReducer.next(state, SessionReducer.Event.CONNECT);
        state = SessionReducer.next(state, SessionReducer.Event.SOCKET_OPEN);
        state = SessionReducer.next(state, SessionReducer.Event.HELLO_OK);
        state = SessionReducer.next(state, SessionReducer.Event.START_LISTEN);
        state = SessionReducer.next(state, SessionReducer.Event.STOP_LISTEN);
        state = SessionReducer.next(state, SessionReducer.Event.TTS_START);
        state = SessionReducer.next(state, SessionReducer.Event.TTS_STOP);
        assertEquals(SessionState.DRAINING, state);
    }
    @Test public void ignoresImpossibleTransitionsAndFailsTimeout() {
        assertEquals(SessionState.DISCONNECTED, SessionReducer.next(SessionState.DISCONNECTED, SessionReducer.Event.START_LISTEN));
        assertEquals(SessionState.ERROR, SessionReducer.next(SessionState.HELLO_WAIT, SessionReducer.Event.HELLO_TIMEOUT));
    }
    @Test public void realtimeCaptureDoesNotReplaceSpeakingState() {
        assertEquals(SessionState.SPEAKING, SessionReducer.next(SessionState.SPEAKING, SessionReducer.Event.START_LISTEN));
        assertEquals(SessionState.SPEAKING, SessionReducer.next(SessionState.SPEAKING, SessionReducer.Event.STOP_LISTEN));
    }
}
