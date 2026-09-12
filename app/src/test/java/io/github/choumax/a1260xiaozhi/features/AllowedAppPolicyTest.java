package io.github.choumax.a1260xiaozhi.features;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public final class AllowedAppPolicyTest {
    @Test public void nothingIsAllowedBeforeExplicitSelectionAndUninstallRevokes() {
        assertFalse(AllowedAppPolicy.permits(Collections.emptySet(), Collections.singleton("example.player"), "example.player"));
        assertTrue(AllowedAppPolicy.permits(Collections.singleton("example.player"), Collections.singleton("example.player"), "example.player"));
        assertFalse(AllowedAppPolicy.permits(Collections.singleton("example.player"), Collections.emptySet(), "example.player"));
    }
    @Test public void packageIsAnExactIdentifierNotWildcardUriOrComponent() {
        for (String invalid : Arrays.asList("*", "example.*", "example.app/.Activity", "intent://launch", " example.app", "example.app;cmd", "example..app", "", "a".repeat(256))) {
            assertFalse(invalid, AllowedAppPolicy.validPackage(invalid));
        }
        assertTrue(AllowedAppPolicy.validPackage("com.example.player_2"));
    }
    @Test public void selectionIsBoundedImmutableAndHasNoPrefixPermission() {
        Set<String> approved = AllowedAppPolicy.validate(Collections.singleton("example.app"));
        assertThrows(UnsupportedOperationException.class, () -> approved.add("example.other"));
        assertFalse(AllowedAppPolicy.permits(approved, Collections.singleton("example.app.extra"), "example.app.extra"));
        List<String> tooMany = new ArrayList<>(); for (int i = 0; i < 17; i++) tooMany.add("example.app" + i);
        assertThrows(IllegalArgumentException.class, () -> AllowedAppPolicy.validate(tooMany));
    }
    @Test public void mcpToolOnlyAppearsWithSelectionAndRejectsOtherPackagesOrExtras() throws Exception {
        AppsBackend backend = new AppsBackend();
        McpDispatcher dispatcher = new McpDispatcher(backend);
        initialize(dispatcher);
        assertFalse(dispatcher.availableTools().toString().contains(McpDispatcher.APP_LAUNCH));
        backend.allowed.add("example.player");
        assertTrue(dispatcher.availableTools().toString().contains(McpDispatcher.APP_LAUNCH));
        JSONObject accepted = call(dispatcher, McpDispatcher.object("package", "example.player"));
        assertFalse(accepted.getJSONObject("payload").getJSONObject("result").getBoolean("isError"));
        assertEquals(1, backend.launches);
        assertEquals(-32602, call(dispatcher, McpDispatcher.object("package", "example.other")).getJSONObject("payload").getJSONObject("error").getInt("code"));
        assertEquals(-32602, call(dispatcher, McpDispatcher.object("package", "example.player", "uri", "private://path")).getJSONObject("payload").getJSONObject("error").getInt("code"));
        backend.allowed.clear();
        assertFalse(dispatcher.availableTools().toString().contains(McpDispatcher.APP_LAUNCH));
        assertEquals(-32601, call(dispatcher, McpDispatcher.object("package", "example.player")).getJSONObject("payload").getJSONObject("error").getInt("code"));
        assertEquals(1, backend.launches);
    }
    private static void initialize(McpDispatcher dispatcher) {
        dispatcher.handleEnvelope(McpDispatcher.object("type", "mcp", "payload", McpDispatcher.object("jsonrpc", "2.0", "id", 1, "method", "initialize")), "s");
    }
    private static JSONObject call(McpDispatcher dispatcher, JSONObject args) {
        return dispatcher.handleEnvelope(McpDispatcher.object("type", "mcp", "payload", McpDispatcher.object("jsonrpc", "2.0", "id", 2,
                "method", "tools/call", "params", McpDispatcher.object("name", McpDispatcher.APP_LAUNCH, "arguments", args))), "s");
    }
    private static final class AppsBackend implements DeviceToolBackend {
        final List<String> allowed = new ArrayList<>(); int launches;
        @Override public boolean supportsVolumeControl() { return false; }
        @Override public boolean supportsMediaControl() { return false; }
        @Override public JSONObject getDeviceStatus() { return new JSONObject(); }
        @Override public JSONObject setVolume(int value) { return new JSONObject(); }
        @Override public JSONObject adjustVolume(int value) { return new JSONObject(); }
        @Override public JSONObject setMuted(boolean value) { return new JSONObject(); }
        @Override public JSONObject mediaControl(String value) { return new JSONObject(); }
        @Override public List<String> allowedApps() { return new ArrayList<>(allowed); }
        @Override public JSONObject launchAllowedApp(String pkg) {
            if (!allowed.contains(pkg)) throw new SecurityException("No longer selected");
            launches++; return McpDispatcher.object("status", "launch_requested", "display_confirmed", false);
        }
    }
}
