package io.github.choumax.a1260xiaozhi.features;

import org.json.JSONObject;

/** Platform boundary. Implementations must finish locally and must not execute supplied code. */
public interface DeviceToolBackend {
    boolean supportsVolumeControl();
    boolean supportsMediaControl();
    JSONObject getDeviceStatus() throws Exception;
    JSONObject setVolume(int percent) throws Exception;
    JSONObject adjustVolume(int direction) throws Exception;
    JSONObject setMuted(boolean muted) throws Exception;
    JSONObject mediaControl(String action) throws Exception;
    default java.util.List<String> allowedApps() { return java.util.Collections.emptyList(); }
    default JSONObject launchAllowedApp(String packageName) throws Exception { throw new UnsupportedOperationException("App launch unavailable"); }
}
