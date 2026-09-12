package io.github.choumax.a1260xiaozhi.features;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.choumax.a1260xiaozhi.R;

/** Integration: declare this Activity exported=false and launch with an explicit Intent. */
public final class FeatureHubActivity extends Activity {
    public static final String AGENT_CONSOLE_URL = "https://xiaozhi.me/console/agents";
    private FeatureRuntime runtime;
    private TextView deviceStatus, operationStatus, toolList;
    private final Map<String, CheckBox> pluginChecks = new LinkedHashMap<>();
    private final Map<Button, String> controlTools = new LinkedHashMap<>();
    private boolean resumed;
    private final Runnable observer = () -> runOnUiThread(() -> {
        if (resumed && !isFinishing() && !isDestroyed()) refresh();
    });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.feature_hub_title);
        runtime = FeatureRuntime.get(this);
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        runtime.addObserver(observer);
        refresh();
    }

    @Override protected void onPause() {
        resumed = false;
        runtime.removeObserver(observer);
        super.onPause();
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);
        heading(root, R.string.feature_hub_title);
        text(root, R.string.feature_hub_description);
        heading(root, R.string.feature_device_heading);
        deviceStatus = text(root, R.string.feature_status_loading);
        action(root, R.string.feature_refresh, () -> refresh());

        heading(root, R.string.feature_plugin_heading);
        text(root, R.string.feature_plugin_help);
        plugin(root, BuiltinPlugins.DEVICE, R.string.feature_plugin_device);
        plugin(root, BuiltinPlugins.AUDIO, R.string.feature_plugin_audio);
        plugin(root, BuiltinPlugins.MEDIA, R.string.feature_plugin_media);
        plugin(root, BuiltinPlugins.APPS, R.string.apps_plugin);
        action(root, R.string.apps_title, () -> startActivity(new Intent(this, AllowedAppsActivity.class)));

        heading(root, R.string.feature_audio_heading);
        control(root, R.string.feature_volume_down, McpDispatcher.ADJUST_VOLUME, McpDispatcher.object("direction", -1));
        control(root, R.string.feature_volume_up, McpDispatcher.ADJUST_VOLUME, McpDispatcher.object("direction", 1));
        control(root, R.string.feature_mute, McpDispatcher.SET_MUTE, McpDispatcher.object("muted", true));
        control(root, R.string.feature_unmute, McpDispatcher.SET_MUTE, McpDispatcher.object("muted", false));

        heading(root, R.string.feature_media_heading);
        action(root, R.string.music_title, () -> startActivity(new Intent(this, LocalMusicActivity.class)));
        text(root, R.string.feature_media_help);
        control(root, R.string.feature_media_play, McpDispatcher.MEDIA, McpDispatcher.object("action", "play"));
        control(root, R.string.feature_media_pause, McpDispatcher.MEDIA, McpDispatcher.object("action", "pause"));
        control(root, R.string.feature_media_previous, McpDispatcher.MEDIA, McpDispatcher.object("action", "previous"));
        control(root, R.string.feature_media_next, McpDispatcher.MEDIA, McpDispatcher.object("action", "next"));
        operationStatus = text(root, R.string.feature_no_operation);
        operationStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        heading(root, R.string.feature_agent_heading);
        text(root, R.string.feature_agent_help);
        action(root, R.string.feature_open_agents, this::openAgents);

        heading(root, R.string.feature_tools_heading);
        text(root, R.string.feature_tools_help);
        toolList = text(root, R.string.feature_status_loading);
        toolList.setTextIsSelectable(true);
        action(root, R.string.feature_close, this::finish);
        return scroll;
    }

    private void plugin(LinearLayout root, String id, int title) {
        CheckBox check = new CheckBox(this);
        check.setText(title);
        check.setMinHeight(dp(48));
        check.setChecked(runtime.isPluginEnabled(id));
        check.setOnCheckedChangeListener((button, enabled) -> runtime.setPluginEnabled(id, enabled));
        pluginChecks.put(id, check);
        root.addView(check);
    }

    private void control(LinearLayout root, int label, String tool, JSONObject args) {
        Button button = action(root, label, () -> {
            JSONObject result = runtime.callLocalTool(tool, args);
            operationStatus.setText(result.optBoolean("isError", true) ? R.string.feature_operation_failed
                    : McpDispatcher.MEDIA.equals(tool) ? R.string.feature_media_sent : R.string.feature_operation_done);
            refresh();
        });
        controlTools.put(button, tool);
    }

    private void refresh() {
        JSONObject status = runtime.readDeviceStatus();
        JSONObject speaker = status.optJSONObject("audio_speaker");
        if (speaker == null) deviceStatus.setText(R.string.feature_audio_unavailable);
        else deviceStatus.setText(getString(R.string.feature_status_format, speaker.optInt("volume"),
                getString(speaker.optBoolean("muted") ? R.string.feature_muted : R.string.feature_unmuted),
                getString(status.optBoolean("music_active") ? R.string.feature_music_active : R.string.feature_music_inactive)));
        for (Map.Entry<String, CheckBox> entry : pluginChecks.entrySet()) {
            boolean enabled = runtime.isPluginEnabled(entry.getKey());
            if (entry.getValue().isChecked() != enabled) entry.getValue().setChecked(enabled);
        }
        JSONArray tools = runtime.availableTools();
        java.util.Set<String> available = new java.util.HashSet<>();
        Map<String, Boolean> calls = runtime.lastCalls();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            String name = tool.optString("name");
            available.add(name);
            int result = !calls.containsKey(name) ? R.string.feature_tool_not_called
                    : Boolean.TRUE.equals(calls.get(name)) ? R.string.feature_tool_success : R.string.feature_tool_failed;
            if (lines.length() > 0) lines.append("\n\n");
            lines.append(name).append('\n').append(getString(result));
        }
        toolList.setText(lines.length() == 0 ? getString(R.string.feature_no_tools) : lines.toString());
        for (Map.Entry<Button, String> entry : controlTools.entrySet()) entry.getKey().setEnabled(available.contains(entry.getValue()));
    }

    private void openAgents() {
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(AGENT_CONSOLE_URL));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(browser);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            operationStatus.setText(R.string.feature_browser_unavailable);
        }
    }

    private void heading(LinearLayout root, int title) {
        TextView view = text(root, title);
        view.setTextSize(21);
        view.setPadding(0, dp(18), 0, dp(8));
        view.setAccessibilityHeading(true);
    }

    private TextView text(LinearLayout root, int text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setPadding(0, dp(4), 0, dp(8));
        root.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Button action(LinearLayout root, int label, Runnable callback) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> callback.run());
        root.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
