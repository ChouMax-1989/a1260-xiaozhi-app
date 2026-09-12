package io.github.choumax.a1260xiaozhi;

import org.json.JSONObject;

/** Immutable OTA response view. Code/challenge remain memory-only and are never logged or stored. */
public final class ActivationResponse {
    public final String message, code, challenge, websocketUrl, websocketToken;
    public final int timeoutMs, websocketVersion;
    private ActivationResponse(String message, String code, String challenge, int timeoutMs, String url, String token, int version) {
        this.message = message; this.code = code; this.challenge = challenge; this.timeoutMs = timeoutMs;
        websocketUrl = url; websocketToken = token; websocketVersion = version;
    }
    public static ActivationResponse parse(String body) throws Exception {
        JSONObject root = new JSONObject(body); JSONObject activation = root.optJSONObject("activation"); JSONObject websocket = root.optJSONObject("websocket");
        String message = activation == null ? "" : activation.optString("message", "");
        String code = activation == null ? "" : activation.optString("code", "");
        String challenge = activation == null ? "" : activation.optString("challenge", "");
        int timeout = activation == null ? 30000 : activation.optInt("timeout_ms", 30000);
        String url = websocket == null ? "" : websocket.optString("url", "");
        String token = websocket == null ? "" : websocket.optString("token", "");
        // Current v1 OTA responses may omit this field; source protocol v1 is the documented default.
        int version = websocket == null ? 0 : websocket.optInt("version", 1);
        return new ActivationResponse(message, code, challenge, timeout, url, token, version);
    }
    public boolean hasActivation() { return !code.isEmpty() || !challenge.isEmpty(); }
    public boolean hasChallenge() { return !challenge.isEmpty(); }
    public boolean hasValidWebsocketV1() { return websocketUrl.startsWith("wss://") && !websocketToken.isEmpty() && websocketVersion == 1; }
}
