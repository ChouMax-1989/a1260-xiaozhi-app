package io.github.choumax.a1260xiaozhi;

import java.util.UUID;

public final class ConnectionConfig {
    public final String endpoint;
    public final String deviceId;
    public final String clientId;
    public final String token;
    public final int protocolVersion;
    public final boolean continuousHalfDuplex;
    public final boolean fullDuplex;

    public ConnectionConfig(String endpoint, String deviceId, String clientId, String token,
                            boolean continuousHalfDuplex) {
        this(endpoint, deviceId, clientId, token, 1, continuousHalfDuplex, false);
    }
    public ConnectionConfig(String endpoint, String deviceId, String clientId, String token, int protocolVersion,
                            boolean continuousHalfDuplex) { this(endpoint, deviceId, clientId, token, protocolVersion, continuousHalfDuplex, false); }
    public ConnectionConfig(String endpoint, String deviceId, String clientId, String token, int protocolVersion,
                            boolean continuousHalfDuplex, boolean fullDuplex) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.deviceId = deviceId == null ? "" : deviceId.trim();
        this.clientId = clientId == null ? UUID.randomUUID().toString() : clientId;
        this.token = token == null ? "" : token.trim();
        this.protocolVersion = protocolVersion;
        this.continuousHalfDuplex = continuousHalfDuplex;
        this.fullDuplex = fullDuplex;
    }

    public String validationError() {
        if (!endpoint.startsWith("wss://")) return "Endpoint must start with wss://";
        if (deviceId.isEmpty()) return "Enter the Device-Id registered by your server.";
        if (protocolVersion != 1) return "This client currently supports WebSocket protocol version 1 only.";
        return null;
    }
}
