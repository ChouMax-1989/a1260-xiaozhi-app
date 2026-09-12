package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ProtocolJsonTest {
    @Test public void textUsesDetectExtensionAndPreservesEscaping() throws Exception {
        org.json.JSONObject body = new org.json.JSONObject(ProtocolJson.text("session", "你好\n\"小智\""));
        assertEquals("listen", body.getString("type")); assertEquals("detect", body.getString("state"));
        assertEquals("你好\n\"小智\"", body.getString("text")); assertEquals("session", body.getString("session_id"));
        assertTrue(!body.has("mode"));
    }
    @Test public void continuousListeningUsesAutoNotManual() {
        assertEquals("auto", ProtocolJson.listeningMode(false, true));
        assertEquals("manual", ProtocolJson.listeningMode(false, false));
        assertEquals("realtime", ProtocolJson.listeningMode(true, true));
    }
    @Test public void helloDeclaresActualTwentyMillisecondMediaCodecFrame() {
        String hello = ProtocolJson.hello();
        assertTrue(hello.contains("\"format\":\"opus\""));
        assertTrue(hello.contains("\"frame_duration\":20"));
        assertTrue(hello.contains("\"sample_rate\":16000"));
    }
    @Test public void controlEscapesSessionWithoutStringConcatenation() throws Exception {
        ProtocolJson.ServerMessage message = ProtocolJson.parse("{\"type\":\"stt\",\"text\":\"hello\",\"session_id\":\"s1\"}");
        assertEquals("stt", message.type); assertEquals("hello", message.text); assertEquals("s1", message.sessionId);
        assertTrue(ProtocolJson.listen("a\\\"b", "start").contains("a\\\\\\\"b"));
    }
    @Test public void realtimeModeIsOnlyUsedWhenCallerExplicitlyRequestsIt() {
        assertTrue(ProtocolJson.listen("s", "start", "realtime").contains("\"mode\":\"realtime\""));
        assertTrue(ProtocolJson.listen("s", "start").contains("\"mode\":\"manual\""));
    }
}
