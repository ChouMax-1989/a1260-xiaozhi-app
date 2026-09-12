package io.github.choumax.a1260xiaozhi.features;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Original, restricted device-side MCP server. No Android dependencies.
 * Wire reference: 78/xiaozhi-esp32 c7241272f2d5fd140c77542f3cf12d09e717fc2f,
 * docs/mcp-protocol.md and main/mcp_server.cc. No source/assets copied.
 * Calls are serialized, bounded to one local backend operation, with no executor queue.
 */
public final class McpDispatcher {
    public static final String STATUS = "self.get_device_status";
    public static final String SET_VOLUME = "self.audio_speaker.set_volume";
    public static final String ADJUST_VOLUME = "self.audio_speaker.adjust_volume";
    public static final String SET_MUTE = "self.audio_speaker.set_mute";
    public static final String MEDIA = "self.media.control";
    public static final String APP_LAUNCH = "self.app.launch";
    public static final int MAX_MESSAGE_CHARS = 8192;
    private static final int MAX_DEPTH = 16;
    private static final String PROTOCOL_VERSION = "2024-11-05";

    public interface InvocationListener { void onInvocation(String tool, boolean success); }
    public interface ToolPolicy { boolean isEnabled(String tool); }

    private final DeviceToolBackend backend;
    private final InvocationListener listener;
    private final ToolPolicy policy;
    private boolean initialized;

    public McpDispatcher(DeviceToolBackend backend) { this(backend, null, tool -> true); }
    public McpDispatcher(DeviceToolBackend backend, InvocationListener listener) {
        this(backend, listener, tool -> true);
    }
    public McpDispatcher(DeviceToolBackend backend, InvocationListener listener, ToolPolicy policy) {
        if (backend == null) throw new IllegalArgumentException("backend required");
        this.backend = backend;
        this.listener = listener;
        this.policy = policy;
    }

    public synchronized void resetSession() { initialized = false; }

    /** Null means no reply: notification, unrelated message, or stale session. */
    public synchronized JSONObject handleEnvelope(String text, String expectedSessionId) {
        String session = safeSession(expectedSessionId);
        if (!boundedJson(text)) return envelope(session, error(null, -32700, "Invalid or oversized JSON"));
        try {
            JSONTokener tokener = new JSONTokener(text);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0) {
                return envelope(session, error(null, -32600, "Expected one request object"));
            }
            return handleEnvelope((JSONObject) parsed, session);
        } catch (JSONException malformed) {
            return envelope(session, error(null, -32700, "Invalid JSON"));
        }
    }

    /** Integrator validates its transport and supplies the current authenticated session id. */
    public synchronized JSONObject handleEnvelope(JSONObject incoming, String expectedSessionId) {
        if (incoming == null || !"mcp".equals(incoming.opt("type"))) return null;
        String session = safeSession(expectedSessionId);
        Object suppliedSession = incoming.opt("session_id");
        if (suppliedSession != null && (!(suppliedSession instanceof String)
                || !session.equals(suppliedSession))) return null;
        JSONObject request = incoming.optJSONObject("payload");
        if (request == null) return envelope(session, error(null, -32600, "Expected JSON-RPC payload"));
        // JSON objects arriving from a transport parser still receive a size/depth bound.
        if (!boundedJson(request.toString())) return envelope(session, error(null, -32600, "Payload exceeds limits"));
        Object id = request.has("id") ? request.opt("id") : null;
        if (!"2.0".equals(request.opt("jsonrpc")) || !validId(id)) {
            return envelope(session, error(null, -32600, "Invalid JSON-RPC request"));
        }
        Object methodValue = request.opt("method");
        // Responses are not requests; do not start an error-response loop.
        if (methodValue == null && (request.has("result") || request.has("error"))) return null;
        if (!(methodValue instanceof String) || ((String) methodValue).isEmpty()
                || ((String) methodValue).length() > 96 || request.has("result") || request.has("error")) {
            return envelope(session, error(id, -32600, "Invalid JSON-RPC request"));
        }
        // Notifications never invoke physical tools, and never receive JSON-RPC replies.
        if (!request.has("id")) return null;
        String method = (String) methodValue;
        try {
            JSONObject params = objectOrEmpty(request, "params");
            JSONObject result;
            switch (method) {
                case "initialize":
                    validateInitialize(params);
                    initialized = true;
                    result = object("protocolVersion", PROTOCOL_VERSION,
                            "capabilities", object("tools", new JSONObject()),
                            "serverInfo", object("name", "A1260 XiaoZhi", "version", "1.0"));
                    break;
                case "tools/list":
                    requireInitialized();
                    checkKeys(params, "cursor", "withUserTools", "_meta");
                    if (params.has("cursor") && (!(params.opt("cursor") instanceof String)
                            || !"".equals(params.opt("cursor")))) throw new RpcFailure(-32602, "Unknown cursor");
                    if (params.has("withUserTools") && !(params.opt("withUserTools") instanceof Boolean)) {
                        throw new RpcFailure(-32602, "withUserTools must be boolean");
                    }
                    result = object("tools", availableTools());
                    break;
                case "tools/call":
                    requireInitialized();
                    checkKeys(params, "name", "arguments", "_meta");
                    Object name = params.opt("name");
                    if (!(name instanceof String) || ((String) name).length() > 96) {
                        throw new RpcFailure(-32602, "Tool name required");
                    }
                    result = invoke((String) name, objectOrEmpty(params, "arguments"));
                    break;
                default:
                    throw new RpcFailure(-32601, "Method not found");
            }
            return envelope(session, object("jsonrpc", "2.0", "id", id, "result", result));
        } catch (RpcFailure rejected) {
            return envelope(session, error(id, rejected.code, rejected.getMessage()));
        }
    }

    /** The UI uses this same registry, so unavailable platform controls are never advertised. */
    public synchronized JSONArray availableTools() {
        JSONArray tools = new JSONArray();
        if (policy.isEnabled(STATUS)) tools.put(tool(STATUS, "Read media volume, playback activity, battery and memory status; no device identity.", schema(new JSONObject())));
        if (backend.supportsVolumeControl() && policy.isEnabled(SET_VOLUME)) {
            tools.put(tool(SET_VOLUME, "Set media stream volume to 0–100 percent. Read device status first.",
                    schema(object("volume", object("type", "integer", "minimum", 0, "maximum", 100)), "volume")));
            tools.put(tool(ADJUST_VOLUME, "Adjust media stream by one system volume step: -1 down, 1 up.",
                    schema(object("direction", object("type", "integer", "enum", new JSONArray().put(-1).put(1))), "direction")));
            tools.put(tool(SET_MUTE, "Mute or unmute the media stream only.",
                    schema(object("muted", object("type", "boolean")), "muted")));
        }
        if (backend.supportsMediaControl() && policy.isEnabled(MEDIA)) {
            tools.put(tool(MEDIA, "Send a media key to the system media session. Delivery does not confirm playback; requires a responsive media app.",
                    schema(object("action", object("type", "string", "enum", new JSONArray()
                            .put("play").put("pause").put("next").put("previous"))), "action")));
        }
        if (policy.isEnabled(APP_LAUNCH) && !backend.allowedApps().isEmpty()) {
            JSONArray packages = new JSONArray();
            for (String pkg : backend.allowedApps()) if (AllowedAppPolicy.validPackage(pkg)) packages.put(pkg);
            if (packages.length() > 0) tools.put(tool(APP_LAUNCH,
                    "Request launch of an app explicitly selected by the user on this device. The OS may restrict background launches.",
                    schema(object("package", object("type", "string", "enum", packages)), "package")));
        }
        return tools;
    }

    /** Local UI actions share the validation and backend but do not change MCP initialization. */
    public synchronized JSONObject callLocalTool(String name, JSONObject arguments) {
        try {
            return invoke(name, arguments == null ? new JSONObject() : arguments);
        } catch (RpcFailure invalid) {
            return toolResult(object("status", "rejected", "message", invalid.getMessage()), true);
        }
    }

    private JSONObject invoke(String name, JSONObject arguments) throws RpcFailure {
        if (!isAvailable(name)) throw new RpcFailure(-32601, "Tool not found or unavailable");
        int number = 0;
        String action = null;
        boolean muted = false;
        switch (name) {
            case STATUS: checkKeys(arguments); break;
            case SET_VOLUME:
                checkKeys(arguments, "volume"); number = integer(arguments, "volume", 0, 100); break;
            case ADJUST_VOLUME:
                checkKeys(arguments, "direction"); number = integer(arguments, "direction", -1, 1);
                if (number == 0) throw new RpcFailure(-32602, "direction must be -1 or 1");
                break;
            case SET_MUTE:
                checkKeys(arguments, "muted");
                if (!(arguments.opt("muted") instanceof Boolean)) throw new RpcFailure(-32602, "muted must be boolean");
                muted = (Boolean) arguments.opt("muted"); break;
            case MEDIA:
                checkKeys(arguments, "action");
                Object candidate = arguments.opt("action");
                if (!(candidate instanceof String) || !Arrays.asList("play", "pause", "next", "previous").contains(candidate)) {
                    throw new RpcFailure(-32602, "Unsupported media action");
                }
                action = (String) candidate; break;
            case APP_LAUNCH:
                checkKeys(arguments, "package");
                Object pkg = arguments.opt("package");
                if (!(pkg instanceof String) || !AllowedAppPolicy.validPackage((String) pkg)
                        || !backend.allowedApps().contains(pkg)) throw new RpcFailure(-32602, "App is not in the selected allowlist");
                action = (String) pkg; break;
            default: throw new RpcFailure(-32601, "Tool not found");
        }
        try {
            JSONObject result;
            switch (name) {
                case STATUS: result = backend.getDeviceStatus(); break;
                case SET_VOLUME: result = backend.setVolume(number); break;
                case ADJUST_VOLUME: result = backend.adjustVolume(number); break;
                case SET_MUTE: result = backend.setMuted(muted); break;
                case APP_LAUNCH: result = backend.launchAllowedApp(action); break;
                default: result = backend.mediaControl(action); break;
            }
            if (result == null || !boundedJson(result.toString())) throw new IllegalStateException("Invalid platform result");
            notifyInvocation(name, true);
            return toolResult(result, false);
        } catch (Exception platformFailure) {
            // Exception text can contain system data. Expose a fixed, non-sensitive failure.
            notifyInvocation(name, false);
            return toolResult(object("status", "failed", "message", "Device operation failed or is restricted by the system"), true);
        }
    }

    private boolean isAvailable(String name) {
        return policy.isEnabled(name) && (STATUS.equals(name) || ((SET_VOLUME.equals(name) || ADJUST_VOLUME.equals(name)
                || SET_MUTE.equals(name)) && backend.supportsVolumeControl())
                || MEDIA.equals(name) && backend.supportsMediaControl()
                || APP_LAUNCH.equals(name) && !backend.allowedApps().isEmpty());
    }

    private void notifyInvocation(String name, boolean success) {
        if (listener != null) {
            try { listener.onInvocation(name, success); } catch (RuntimeException ignored) { /* Observer cannot change operation result. */ }
        }
    }

    private void requireInitialized() throws RpcFailure {
        if (!initialized) throw new RpcFailure(-32002, "MCP session not initialized");
    }

    private static void validateInitialize(JSONObject params) throws RpcFailure {
        checkKeys(params, "protocolVersion", "capabilities", "clientInfo", "_meta");
        if (params.has("protocolVersion") && !(params.opt("protocolVersion") instanceof String)) {
            throw new RpcFailure(-32602, "protocolVersion must be string");
        }
        for (String key : new String[]{"capabilities", "clientInfo"}) {
            if (params.has(key) && !(params.opt(key) instanceof JSONObject)) throw new RpcFailure(-32602, key + " must be object");
        }
    }

    private static JSONObject objectOrEmpty(JSONObject owner, String key) throws RpcFailure {
        if (!owner.has(key)) return new JSONObject();
        Object value = owner.opt(key);
        if (!(value instanceof JSONObject)) throw new RpcFailure(-32602, key + " must be object");
        return (JSONObject) value;
    }

    private static int integer(JSONObject args, String key, int min, int max) throws RpcFailure {
        Object value = args.opt(key);
        // Do not coerce strings, booleans, fractional values, or NaN into device actions.
        if (!(value instanceof Number)) throw new RpcFailure(-32602, key + " must be integer");
        double numeric = ((Number) value).doubleValue();
        if (!Double.isFinite(numeric) || numeric != Math.rint(numeric) || numeric < min || numeric > max) {
            throw new RpcFailure(-32602, key + " is outside the allowed integer range");
        }
        return (int) numeric;
    }

    private static void checkKeys(JSONObject args, String... allowed) throws RpcFailure {
        Set<String> keys = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> iterator = args.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (!keys.contains(key)) throw new RpcFailure(-32602, "Unexpected parameter");
            if ("_meta".equals(key) && !(args.opt(key) instanceof JSONObject)) throw new RpcFailure(-32602, "_meta must be object");
        }
    }

    private static boolean validId(Object id) {
        if (id == null || id == JSONObject.NULL) return true;
        if (id instanceof String) return ((String) id).length() <= 128;
        if (!(id instanceof Number)) return false;
        double value = ((Number) id).doubleValue();
        return Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) <= 9007199254740991d;
    }

    private static String safeSession(String session) {
        return session != null && session.length() <= 256 ? session : "";
    }

    private static boolean boundedJson(String text) {
        if (text == null || text.length() > MAX_MESSAGE_CHARS) return false;
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > MAX_DEPTH) return false; }
            else if (c == '}' || c == ']') { if (--depth < 0) return false; }
        }
        return depth == 0 && !quoted;
    }

    private static JSONObject tool(String name, String description, JSONObject schema) {
        return object("name", name, "description", description, "inputSchema", schema);
    }

    private static JSONObject schema(JSONObject properties, String... required) {
        JSONArray fields = new JSONArray();
        for (String name : required) fields.put(name);
        return object("type", "object", "properties", properties, "required", fields, "additionalProperties", false);
    }

    private static JSONObject toolResult(JSONObject result, boolean isError) {
        return object("content", new JSONArray().put(object("type", "text", "text", result.toString())), "isError", isError);
    }

    private static JSONObject error(Object id, int code, String message) {
        return object("jsonrpc", "2.0", "id", id, "error", object("code", code, "message", message));
    }

    private static JSONObject envelope(String session, JSONObject payload) {
        return object("session_id", session, "type", "mcp", "payload", payload);
    }

    static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1] == null ? JSONObject.NULL : values[i + 1]);
        } catch (JSONException impossible) { throw new IllegalStateException("Invalid internal JSON", impossible); }
        return result;
    }

    private static final class RpcFailure extends Exception {
        final int code;
        RpcFailure(int code, String message) { super(message); this.code = code; }
    }
}
