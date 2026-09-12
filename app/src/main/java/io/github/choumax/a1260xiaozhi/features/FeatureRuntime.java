package io.github.choumax.a1260xiaozhi.features;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local integration entry. Persist only plugin preferences; invocation states are ephemeral. */
public final class FeatureRuntime {
    private static FeatureRuntime instance;
    private final AndroidDeviceTools backend;
    private final McpDispatcher dispatcher;
    private final SharedPreferences preferences;
    private final Map<String, Boolean> lastCalls = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();

    public static synchronized FeatureRuntime get(Context context) {
        if (instance == null) instance = new FeatureRuntime(context.getApplicationContext());
        return instance;
    }

    private FeatureRuntime(Context context) {
        backend = new AndroidDeviceTools(context);
        preferences = context.getSharedPreferences("builtin_feature_plugins", Context.MODE_PRIVATE);
        dispatcher = new McpDispatcher(backend, this::recordCall, tool -> {
            String plugin = BuiltinPlugins.pluginForTool(tool);
            return plugin != null && isPluginEnabled(plugin);
        });
    }

    public JSONObject handleMcpEnvelope(JSONObject envelope, String expectedSessionId) {
        return dispatcher.handleEnvelope(envelope, expectedSessionId);
    }

    public JSONObject handleMcpEnvelope(String text, String expectedSessionId) {
        return dispatcher.handleEnvelope(text, expectedSessionId);
    }

    /** Must be called at each transport disconnect/new connection to clear MCP initialization. */
    public void resetMcpSession() { dispatcher.resetSession(); }
    public JSONArray availableTools() { return dispatcher.availableTools(); }
    public AppAllowlistStore appAllowlist() { return backend.appAllowlist(); }
    public JSONObject callLocalTool(String name, JSONObject arguments) { return dispatcher.callLocalTool(name, arguments); }

    /** Local status rendering only, even if remote device-info plugin is disabled. */
    public JSONObject readDeviceStatus() {
        try { return backend.getDeviceStatus(); } catch (RuntimeException unavailable) { return new JSONObject(); }
    }

    public boolean isPluginEnabled(String plugin) {
        if (!BuiltinPlugins.IDS.contains(plugin)) return false;
        return preferences.getBoolean(plugin, true);
    }

    public void setPluginEnabled(String plugin, boolean enabled) {
        if (!BuiltinPlugins.IDS.contains(plugin)) throw new IllegalArgumentException("Unknown built-in plugin");
        preferences.edit().putBoolean(plugin, enabled).apply();
        notifyObservers();
    }

    public synchronized Map<String, Boolean> lastCalls() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lastCalls));
    }

    public void addObserver(Runnable observer) { observers.addIfAbsent(observer); }
    public void removeObserver(Runnable observer) { observers.remove(observer); }

    private void recordCall(String tool, boolean success) {
        synchronized (this) {
            // Closed catalog bounds entries. No arguments/results/session IDs retained.
            lastCalls.put(tool, success);
        }
        notifyObservers();
    }

    private void notifyObservers() {
        for (Runnable observer : observers) {
            try { observer.run(); } catch (RuntimeException ignored) { /* UI observation is best effort. */ }
        }
    }
}
