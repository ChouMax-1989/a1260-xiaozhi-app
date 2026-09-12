package io.github.choumax.a1260xiaozhi.features;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Tests only the pure-Java wire/allowlist boundary, never Android audio or device state. */
public final class McpDispatcherTest {
    private FakeBackend backend;
    private McpDispatcher dispatcher;

    @Before public void setUp() {
        backend = new FakeBackend();
        dispatcher = new McpDispatcher(backend);
    }

    @Test public void initializeAdvertisesToolsAndEchoesStringId() throws Exception {
        JSONObject response = request("initialize", new JSONObject(), "id\"quoted");
        assertEquals("mcp", response.getString("type"));
        assertEquals("test-session", response.getString("session_id"));
        JSONObject payload = response.getJSONObject("payload");
        assertEquals("id\"quoted", payload.getString("id"));
        assertEquals("2024-11-05", payload.getJSONObject("result").getString("protocolVersion"));
        assertTrue(payload.getJSONObject("result").getJSONObject("capabilities").has("tools"));
        assertEquals(0, backend.calls);
    }

    @Test public void listRequiresInitializationAndResetClearsIt() throws Exception {
        assertError(-32002, request("tools/list", new JSONObject(), 1));
        initialize();
        assertEquals(5, result(request("tools/list", new JSONObject(), 2)).getJSONArray("tools").length());
        dispatcher.resetSession();
        assertError(-32002, request("tools/list", new JSONObject(), 3));
    }

    @Test public void registryOnlyListsClosedSchemasAndKnownTools() throws Exception {
        initialize();
        JSONArray tools = result(request("tools/list", obj("cursor", "", "withUserTools", true), 2)).getJSONArray("tools");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            names.add(tool.getString("name"));
            assertFalse(tool.getJSONObject("inputSchema").getBoolean("additionalProperties"));
            assertNotNull(BuiltinPlugins.pluginForTool(tool.getString("name")));
        }
        assertEquals(5, names.size());
        assertFalse(names.contains("self.shell.execute"));
        assertFalse(names.contains("self.app.launch"));
    }

    @Test public void platformUnavailableControlsAreNotAdvertisedOrExecuted() throws Exception {
        backend.volumeSupported = false;
        backend.mediaSupported = false;
        initialize();
        assertEquals(1, result(request("tools/list", new JSONObject(), 2)).getJSONArray("tools").length());
        assertError(-32601, call(McpDispatcher.SET_VOLUME, obj("volume", 20)));
        assertError(-32601, call(McpDispatcher.MEDIA, obj("action", "play")));
        assertEquals(0, backend.calls);
    }

    @Test public void pluginDisableImmediatelyRemovesAndRejectsGroupWithoutReinitializing() throws Exception {
        Set<String> disabled = new HashSet<>();
        dispatcher = new McpDispatcher(backend, null, name -> !disabled.contains(BuiltinPlugins.pluginForTool(name)));
        initialize();
        disabled.add(BuiltinPlugins.AUDIO);
        assertEquals(2, result(request("tools/list", new JSONObject(), 2)).getJSONArray("tools").length());
        assertError(-32601, call(McpDispatcher.SET_VOLUME, obj("volume", 20)));
        assertError(-32601, call(McpDispatcher.ADJUST_VOLUME, obj("direction", 1)));
        assertError(-32601, call(McpDispatcher.SET_MUTE, obj("muted", true)));
        assertEquals(0, backend.calls);
        disabled.clear();
        assertFalse(result(call(McpDispatcher.SET_VOLUME, obj("volume", 20))).getBoolean("isError"));
        assertEquals(20, backend.volume);
    }

    @Test public void volumeAcceptsEndpointsAndPreservesIntegerValue() throws Exception {
        initialize();
        assertFalse(result(call(McpDispatcher.SET_VOLUME, obj("volume", 0))).getBoolean("isError"));
        assertEquals(0, backend.volume);
        assertFalse(result(call(McpDispatcher.SET_VOLUME, obj("volume", 100))).getBoolean("isError"));
        assertEquals(100, backend.volume);
    }

    @Test public void volumeRejectsCoercionFractionsMissingAndOutOfRangeWithoutCallingBackend() throws Exception {
        initialize();
        for (Object value : new Object[]{"50", true, 0.5, -1, 101, JSONObject.NULL}) {
            assertError(-32602, call(McpDispatcher.SET_VOLUME, obj("volume", value)));
        }
        assertError(-32602, call(McpDispatcher.SET_VOLUME, new JSONObject()));
        assertError(-32602, call(McpDispatcher.SET_VOLUME, obj("volume", 20, "command", "ignored")));
        assertEquals(0, backend.calls);
    }

    @Test public void mediaOnlyAllowsFourActionsAndDoesNotClaimPlaybackConfirmation() throws Exception {
        initialize();
        for (String action : new String[]{"play", "pause", "next", "previous"}) {
            JSONObject result = result(call(McpDispatcher.MEDIA, obj("action", action)));
            assertFalse(result.getBoolean("isError"));
            JSONObject content = new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
            assertFalse(content.getBoolean("playback_confirmed"));
            assertEquals(action, backend.action);
        }
        int before = backend.calls;
        assertError(-32602, call(McpDispatcher.MEDIA, obj("action", "launch")));
        assertError(-32602, call(McpDispatcher.MEDIA, obj("action", "play", "package", "other.app")));
        assertEquals(before, backend.calls);
    }

    @Test public void muteRequiresBooleanAndAdjustmentRequiresNonzeroUnitStep() throws Exception {
        initialize();
        assertError(-32602, call(McpDispatcher.SET_MUTE, obj("muted", "true")));
        assertError(-32602, call(McpDispatcher.ADJUST_VOLUME, obj("direction", 0)));
        assertError(-32602, call(McpDispatcher.ADJUST_VOLUME, obj("direction", 2)));
        assertEquals(0, backend.calls);
        call(McpDispatcher.SET_MUTE, obj("muted", true));
        assertTrue(backend.muted);
        call(McpDispatcher.ADJUST_VOLUME, obj("direction", -1));
        assertEquals(-1, backend.direction);
    }

    @Test public void unknownMethodsToolsAndParametersReturnRpcErrors() throws Exception {
        initialize();
        assertError(-32601, request("shell/run", obj("command", "anything"), 2));
        assertError(-32601, call("self.shell.execute", new JSONObject()));
        assertError(-32602, request("tools/list", obj("cursor", "unknown"), 3));
        assertError(-32602, request("tools/list", obj("withUserTools", "true"), 4));
        assertError(-32602, request("tools/call", obj("name", McpDispatcher.STATUS, "arguments", new JSONArray()), 5));
        assertError(-32602, call(McpDispatcher.STATUS, obj("includeIdentity", true)));
        assertEquals(0, backend.calls);
    }

    @Test public void notificationsCannotInvokePhysicalToolsAndReceiveNoReply() throws Exception {
        initialize();
        JSONObject notification = obj("type", "mcp", "payload", obj("jsonrpc", "2.0", "method", "tools/call",
                "params", obj("name", McpDispatcher.MEDIA, "arguments", obj("action", "next"))));
        assertNull(dispatcher.handleEnvelope(notification, "test-session"));
        assertNull(dispatcher.handleEnvelope(obj("type", "mcp", "payload", obj("jsonrpc", "2.0",
                "method", "notifications/initialized")), "test-session"));
        assertEquals(0, backend.calls);
    }

    @Test public void notificationInitializeDoesNotInitializeSession() throws Exception {
        assertNull(dispatcher.handleEnvelope(obj("type", "mcp", "payload", obj("jsonrpc", "2.0", "method", "initialize")), "test-session"));
        assertError(-32002, request("tools/list", new JSONObject(), 1));
    }

    @Test public void mismatchedSessionAndIncomingResponseAreIgnored() throws Exception {
        initialize();
        JSONObject stale = envelope("tools/call", obj("name", McpDispatcher.MEDIA, "arguments", obj("action", "play")), 1);
        stale.put("session_id", "old-session");
        assertNull(dispatcher.handleEnvelope(stale, "test-session"));
        assertNull(dispatcher.handleEnvelope(obj("type", "mcp", "payload", obj("jsonrpc", "2.0", "id", 1, "result", new JSONObject())), "test-session"));
        assertNull(dispatcher.handleEnvelope(obj("type", "tts"), "test-session"));
        assertEquals(0, backend.calls);
    }

    @Test public void invalidRpcVersionIdPayloadAndParamsCannotReachBackend() throws Exception {
        JSONObject invalid = envelope("initialize", new JSONObject(), true);
        assertError(-32600, dispatcher.handleEnvelope(invalid, "test-session"));
        invalid.getJSONObject("payload").put("id", 1).put("jsonrpc", "1.0");
        assertError(-32600, dispatcher.handleEnvelope(invalid, "test-session"));
        assertError(-32600, dispatcher.handleEnvelope(obj("type", "mcp", "payload", "wrong"), "test-session"));
        assertError(-32602, request("initialize", obj("capabilities", new JSONArray()), 2));
        assertEquals(0, backend.calls);
    }

    @Test public void sizeDepthMalformedAndTrailingJsonAreRejected() throws Exception {
        assertError(-32700, dispatcher.handleEnvelope("{", "test-session"));
        assertError(-32700, dispatcher.handleEnvelope(" ".repeat(McpDispatcher.MAX_MESSAGE_CHARS + 1), "test-session"));
        String deep = "{\"type\":\"mcp\",\"payload\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}";
        assertError(-32700, dispatcher.handleEnvelope(deep, "test-session"));
        assertError(-32600, dispatcher.handleEnvelope("{}{}", "test-session"));
        assertEquals(0, backend.calls);
    }

    @Test public void backendFailureIsToolErrorAndDoesNotLeakExceptionText() throws Exception {
        initialize();
        backend.fail = true;
        JSONObject response = call(McpDispatcher.STATUS, new JSONObject());
        assertTrue(result(response).getBoolean("isError"));
        assertFalse(response.toString().contains("private-secret"));
    }

    @Test public void localActionsValidateButDoNotInitializeRemoteSession() throws Exception {
        assertFalse(dispatcher.callLocalTool(McpDispatcher.SET_MUTE, obj("muted", true)).getBoolean("isError"));
        assertTrue(dispatcher.callLocalTool(McpDispatcher.MEDIA, obj("action", "shell")).getBoolean("isError"));
        assertEquals(1, backend.calls);
        assertError(-32002, request("tools/list", new JSONObject(), 1));
    }

    @Test public void manifestRequiresCorrectHashAndClosedVersionedToolDeclarations() throws Exception {
        byte[] valid = manifest(1, BuiltinPlugins.AUDIO, McpDispatcher.SET_VOLUME);
        BuiltinPluginManifest parsed = BuiltinPluginManifest.verify(valid, hash(valid));
        assertEquals(BuiltinPlugins.AUDIO, parsed.pluginId);
        assertEquals(McpDispatcher.SET_VOLUME, parsed.tools.get(0));
        assertThrows(UnsupportedOperationException.class, () -> parsed.tools.add(McpDispatcher.MEDIA));
        assertThrows(IllegalArgumentException.class, () -> BuiltinPluginManifest.verify(valid, "0".repeat(64)));
        for (byte[] invalid : new byte[][]{manifest(2, BuiltinPlugins.AUDIO, McpDispatcher.SET_VOLUME),
                manifest(1, BuiltinPlugins.AUDIO, McpDispatcher.MEDIA), manifest(1, "shell", "exec"),
                manifest(1, BuiltinPlugins.MEDIA, "self.app.launch")}) {
            assertThrows(IllegalArgumentException.class, () -> BuiltinPluginManifest.verify(invalid, hash(invalid)));
        }
    }

    @Test public void manifestRejectsExecutableFieldsDuplicatesAndOversize() throws Exception {
        JSONObject content = obj("schemaVersion", 1, "pluginId", BuiltinPlugins.MEDIA,
                "tools", new JSONArray().put(McpDispatcher.MEDIA), "class", "arbitrary.Class");
        byte[] extra = content.toString().getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> BuiltinPluginManifest.verify(extra, hash(extra)));
        content.remove("class");
        content.getJSONArray("tools").put(McpDispatcher.MEDIA);
        byte[] duplicate = content.toString().getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> BuiltinPluginManifest.verify(duplicate, hash(duplicate)));
        byte[] large = new byte[BuiltinPluginManifest.MAX_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> BuiltinPluginManifest.verify(large, hash(large)));
        byte[] badUtf8 = new byte[]{(byte) 0xc3, 0x28};
        assertThrows(IllegalArgumentException.class, () -> BuiltinPluginManifest.verify(badUtf8, hash(badUtf8)));
    }

    private void initialize() { request("initialize", new JSONObject(), 0); }
    private JSONObject call(String name, JSONObject arguments) { return request("tools/call", obj("name", name, "arguments", arguments), 42); }
    private JSONObject request(String method, JSONObject params, Object id) {
        return dispatcher.handleEnvelope(envelope(method, params, id).toString(), "test-session");
    }
    private static JSONObject envelope(String method, JSONObject params, Object id) {
        return obj("type", "mcp", "session_id", "test-session", "payload", obj("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }
    private static JSONObject result(JSONObject response) throws Exception { return response.getJSONObject("payload").getJSONObject("result"); }
    private static void assertError(int code, JSONObject response) throws Exception {
        assertNotNull(response);
        assertEquals(code, response.getJSONObject("payload").getJSONObject("error").getInt("code"));
    }
    private static JSONObject obj(Object... values) { return McpDispatcher.object(values); }
    private static byte[] manifest(int version, String plugin, String tool) {
        return obj("schemaVersion", version, "pluginId", plugin, "tools", new JSONArray().put(tool)).toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String hash(byte[] input) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(input)) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }

    private static final class FakeBackend implements DeviceToolBackend {
        boolean volumeSupported = true, mediaSupported = true, muted, fail;
        int calls, volume, direction;
        String action;
        @Override public boolean supportsVolumeControl() { return volumeSupported; }
        @Override public boolean supportsMediaControl() { return mediaSupported; }
        @Override public JSONObject getDeviceStatus() {
            calls++;
            if (fail) throw new IllegalStateException("private-secret");
            return obj("volume", volume);
        }
        @Override public JSONObject setVolume(int percent) { volume = percent; calls++; return obj("volume", volume); }
        @Override public JSONObject adjustVolume(int step) { direction = step; calls++; return obj("direction", step); }
        @Override public JSONObject setMuted(boolean value) { muted = value; calls++; return obj("muted", value); }
        @Override public JSONObject mediaControl(String value) { action = value; calls++; return obj("playback_confirmed", false); }
    }
}
