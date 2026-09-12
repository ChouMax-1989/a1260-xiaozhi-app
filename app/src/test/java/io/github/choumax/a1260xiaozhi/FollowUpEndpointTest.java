package io.github.choumax.a1260xiaozhi;
import org.junit.Test;
import static org.junit.Assert.*;
public class FollowUpEndpointTest {
    @Test public void silentFollowUpEndsAtHalfSecond() {
        SpeechEndpoint endpoint = new SpeechEndpoint(500);
        short[] silence = new short[320];
        for (int i=0;i<24;i++) assertFalse(endpoint.accept(silence));
        assertTrue(endpoint.accept(silence));
        assertFalse(endpoint.hasSpeech());
        assertEquals(SessionReducer.Event.STOP, SessionReducer.captureCompletedEvent(true, endpoint.hasSpeech()));
    }
    @Test public void configuredWaitIsUsed() {
        SpeechEndpoint endpoint = new SpeechEndpoint(2000);
        short[] silence = new short[320];
        for (int i=0;i<99;i++) assertFalse(endpoint.accept(silence));
        assertTrue(endpoint.accept(silence));
    }
    @Test public void speechAtDeadlineContinuesUntilOneSecondPause() {
        SpeechEndpoint endpoint = new SpeechEndpoint(500);
        short[] silence = new short[320], speech = new short[320];
        java.util.Arrays.fill(speech,(short)1000);
        for (int i=0;i<24;i++) assertFalse(endpoint.accept(silence));
        for (int i=0;i<20;i++) assertFalse(endpoint.accept(speech));
        assertTrue(endpoint.hasSpeech());
        for (int i=0;i<49;i++) assertFalse(endpoint.accept(silence));
        assertTrue(endpoint.accept(silence));
        assertEquals(SessionReducer.Event.STOP_LISTEN, SessionReducer.captureCompletedEvent(true, endpoint.hasSpeech()));
    }
    @Test public void initialWakeStillAllowsTenSeconds() {
        SpeechEndpoint endpoint = new SpeechEndpoint();
        short[] silence = new short[320];
        for(int i=0;i<499;i++) assertFalse(endpoint.accept(silence));
        assertTrue(endpoint.accept(silence));
    }
}
