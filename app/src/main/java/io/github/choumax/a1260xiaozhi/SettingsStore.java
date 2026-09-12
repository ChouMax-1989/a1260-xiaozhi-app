package io.github.choumax.a1260xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Client/device identity persist privately; custom token is memory-only and issued token is Keystore-encrypted. */
public final class SettingsStore {
    private static final String PREFS = "connection";
    private static final String ENDPOINT = "endpoint";
    private static final String DEVICE_ID = "device_id";
    private static final String CLIENT_ID = "client_id";
    private static final String HALF_DUPLEX = "continuous_half_duplex";
    private static final String CUSTOM_SERVER = "custom_server";
    private static final String FULL_DUPLEX = "full_duplex";
    private static final String FOLLOW_UP_WAIT_MS = "follow_up_wait_ms";
    private static final String SPEECH_PAUSE_MS = "speech_pause_ms";
    private static final String ISSUED_URL = "issued_websocket_url";
    private static final String ISSUED_VERSION = "issued_websocket_version";
    private static final String ASSISTANT_DISPLAY_NAME = "assistant_display_name";
    private static final AtomicReference<String> TOKEN = new AtomicReference<>("");
    private final SharedPreferences prefs;
    private final Context appContext;

    public SettingsStore(Context context) { appContext = context.getApplicationContext(); prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE); if (!prefs.getBoolean("manual_default_v3", false)) prefs.edit().putBoolean(HALF_DUPLEX, false).putBoolean(FULL_DUPLEX, false).putBoolean("manual_default_v3", true).apply(); }

    public ConnectionConfig load() {
        String clientId = prefs.getString(CLIENT_ID, null);
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            prefs.edit().putString(CLIENT_ID, clientId).apply();
        }
        String deviceId = prefs.getString(DEVICE_ID, null);
        if (!DeviceIdentity.isLocallyAdministeredMac(deviceId)) { deviceId = DeviceIdentity.createLocallyAdministeredMac(); prefs.edit().putString(DEVICE_ID, deviceId).apply(); }
        boolean custom = prefs.getBoolean(CUSTOM_SERVER, false);
        if (!custom) {
            try {
                String url = prefs.getString(ISSUED_URL, ""); int version = prefs.getInt(ISSUED_VERSION, 0); String token = new IssuedTokenStore(context()).load();
                if (url.startsWith("wss://") && !token.isEmpty() && version == 1) return new ConnectionConfig(url, deviceId, clientId, token, version, prefs.getBoolean(HALF_DUPLEX, false), prefs.getBoolean(FULL_DUPLEX, false));
            } catch (Exception ignored) { /* caller will run official provisioning again; do not expose secret details */ }
        }
        return new ConnectionConfig(prefs.getString(ENDPOINT, ""), deviceId, clientId, TOKEN.get(), 1, prefs.getBoolean(HALF_DUPLEX, false), prefs.getBoolean(FULL_DUPLEX, false));
    }

    public void saveCustom(String endpoint, String token, boolean halfDuplex, boolean fullDuplex) {
        prefs.edit().putString(ENDPOINT, endpoint.trim()).putBoolean(CUSTOM_SERVER, true)
                .putBoolean(HALF_DUPLEX, halfDuplex).putBoolean(FULL_DUPLEX, fullDuplex).apply();
        TOKEN.set(token == null ? "" : token.trim());
    }
    public void setCustomServer(boolean custom) { prefs.edit().putBoolean(CUSTOM_SERVER, custom).apply(); }
    public void saveOfficialPreferences(boolean halfDuplex, boolean fullDuplex) { prefs.edit().putBoolean(CUSTOM_SERVER, false).putBoolean(HALF_DUPLEX, halfDuplex).putBoolean(FULL_DUPLEX, fullDuplex).apply(); TOKEN.set(""); }
    public boolean isFullDuplexEnabled() { return prefs.getBoolean(FULL_DUPLEX, false); }
    public int followUpWaitMs() { return FollowUpSettings.normalizeMs(prefs.getInt(FOLLOW_UP_WAIT_MS, FollowUpSettings.DEFAULT_WAIT_MS)); }
    public void setFollowUpWaitMs(int waitMs) { prefs.edit().putInt(FOLLOW_UP_WAIT_MS, FollowUpSettings.normalizeMs(waitMs)).apply(); }
    public int speechPauseMs() { return SpeechPauseSettings.normalizeMs(prefs.getInt(SPEECH_PAUSE_MS, SpeechPauseSettings.DEFAULT_PAUSE_MS)); }
    public void setSpeechPauseMs(int pauseMs) { prefs.edit().putInt(SPEECH_PAUSE_MS, SpeechPauseSettings.normalizeMs(pauseMs)).apply(); }
    public String assistantDisplayName() { return AssistantDisplayName.normalize(prefs.getString(ASSISTANT_DISPLAY_NAME, null)); }
    public void setAssistantDisplayName(String value) { prefs.edit().putString(ASSISTANT_DISPLAY_NAME, AssistantDisplayName.normalize(value)).apply(); }
    public boolean isCustomServer() { return prefs.getBoolean(CUSTOM_SERVER, false); }
    public boolean hasIssuedConfig() { try { return prefs.getString(ISSUED_URL, "").startsWith("wss://") && prefs.getInt(ISSUED_VERSION, 0) == 1 && !new IssuedTokenStore(context()).load().isEmpty(); } catch (Exception ignored) { return false; } }
    public void saveIssuedConfig(ActivationResponse response) throws Exception { if (!response.hasValidWebsocketV1()) throw new IllegalArgumentException("Invalid issued WebSocket v1 configuration."); new IssuedTokenStore(context()).save(response.websocketToken); prefs.edit().putString(ISSUED_URL, response.websocketUrl).putInt(ISSUED_VERSION, response.websocketVersion).putBoolean(CUSTOM_SERVER, false).commit(); }
    public void clearIssuedConfig() { new IssuedTokenStore(context()).clear(); prefs.edit().remove(ISSUED_URL).remove(ISSUED_VERSION).apply(); }
    public String clientId() { return load().clientId; }
    public String deviceId() { return load().deviceId; }
    private Context context() { return appContext; }
}
