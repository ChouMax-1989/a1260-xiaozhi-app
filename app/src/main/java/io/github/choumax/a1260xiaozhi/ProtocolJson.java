package io.github.choumax.a1260xiaozhi;

import org.json.JSONException;
import org.json.JSONObject;

/** JSON builders centralize protocol constants and avoid concatenating user-derived values. */
public final class ProtocolJson {
    public static final int MAX_TEXT_CHARS = 4000;
    private ProtocolJson() { }
    public static String hello() {
        try {
            JSONObject audio = new JSONObject();
            audio.put("format", "opus"); audio.put("sample_rate", 16000);
            audio.put("channels", 1); audio.put("frame_duration", 20);
            JSONObject root = new JSONObject();
            root.put("type", "hello"); root.put("version", 1); root.put("transport", "websocket");
            JSONObject features = new JSONObject(); features.put("mcp", true); root.put("features", features);
            root.put("audio_params", audio);
            return root.toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    public static String listen(String sessionId, String state) { return listen(sessionId, state, "manual"); }
    public static String listen(String sessionId, String state, String mode) { return control(sessionId, "listen", state, mode); }
    public static String abort(String sessionId) { return control(sessionId, "abort", null, null); }
    public static String listeningMode(boolean fullDuplex, boolean continuous) { return fullDuplex ? "realtime" : continuous ? "auto" : "manual"; }
    /** Public listen/detect/text shape; arbitrary text interpretation still requires server validation. */
    public static String text(String sessionId, String text) {
        if (text == null || text.trim().isEmpty() || text.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException("Text must contain 1 to 4000 characters.");
        try {
            JSONObject root = new JSONObject(); root.put("session_id", sessionId); root.put("type", "listen");
            root.put("state", "detect"); root.put("text", text); return root.toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String control(String sessionId, String type, String state, String mode) {
        try {
            JSONObject root = new JSONObject(); root.put("session_id", sessionId); root.put("type", type);
            if (state != null) root.put("state", state); if (mode != null) root.put("mode", mode);
            return root.toString();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    public static ServerMessage parse(String text) throws JSONException {
        JSONObject value = new JSONObject(text);
        String type = value.optString("type", "");
        String session = value.optString("session_id", "");
        String message = value.optString("text", "");
        if ("tts".equals(type) && "sentence_start".equals(value.optString("state"))) message = value.optString("text", "");
        if ("llm".equals(type)) message = value.optString("emotion", "");
        if ("alert".equals(type)) message = value.optString("message", value.optString("text", "Server alert"));
        return new ServerMessage(type, value.optString("state", ""), session, message, value);
    }
    public static final class ServerMessage {
        public final String type, state, sessionId, text; public final JSONObject raw;
        ServerMessage(String type, String state, String sessionId, String text, JSONObject raw) {
            this.type = type; this.state = state; this.sessionId = sessionId; this.text = text; this.raw = raw;
        }
    }
}
