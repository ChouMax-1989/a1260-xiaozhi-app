package io.github.choumax.a1260xiaozhi.features;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A closed plugin catalog. A plugin is a group of built-in tools, never executable input. */
public final class BuiltinPlugins {
    public static final String DEVICE = "device_info";
    public static final String AUDIO = "audio";
    public static final String MEDIA = "media";
    public static final String APPS = "selected_apps";
    public static final List<String> IDS = Collections.unmodifiableList(Arrays.asList(DEVICE, AUDIO, MEDIA, APPS));
    private BuiltinPlugins() { }

    public static String pluginForTool(String tool) {
        if (McpDispatcher.STATUS.equals(tool)) return DEVICE;
        if (McpDispatcher.SET_VOLUME.equals(tool) || McpDispatcher.ADJUST_VOLUME.equals(tool)
                || McpDispatcher.SET_MUTE.equals(tool)) return AUDIO;
        if (McpDispatcher.MEDIA.equals(tool)) return MEDIA;
        if (McpDispatcher.APP_LAUNCH.equals(tool)) return APPS;
        return null;
    }

    public static List<String> toolsForPlugin(String plugin) {
        switch (plugin) {
            case DEVICE: return Collections.singletonList(McpDispatcher.STATUS);
            case AUDIO: return Collections.unmodifiableList(Arrays.asList(McpDispatcher.SET_VOLUME,
                    McpDispatcher.ADJUST_VOLUME, McpDispatcher.SET_MUTE));
            case MEDIA: return Collections.singletonList(McpDispatcher.MEDIA);
            case APPS: return Collections.singletonList(McpDispatcher.APP_LAUNCH);
            default: return Collections.emptyList();
        }
    }
}
