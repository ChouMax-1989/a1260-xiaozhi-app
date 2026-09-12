package io.github.choumax.a1260xiaozhi.features;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONObject;

/** AudioManager-only device operations: no shell, arbitrary intents, packages or identifiers. */
public final class AndroidDeviceTools implements DeviceToolBackend {
    private final Context context;
    private final AudioManager audio;
    private final AppAllowlistStore apps;

    public AndroidDeviceTools(Context context) {
        this.context = context.getApplicationContext();
        audio = this.context.getSystemService(AudioManager.class);
        apps = new AppAllowlistStore(this.context);
    }
    public AppAllowlistStore appAllowlist() { return apps; }
    @Override public java.util.List<String> allowedApps() { return apps.allowedPackages(); }
    @Override public JSONObject launchAllowedApp(String packageName) { return apps.launch(packageName); }

    @Override public boolean supportsVolumeControl() {
        return audio != null && !audio.isVolumeFixed()
                && context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public boolean supportsMediaControl() { return audio != null; }

    @Override public JSONObject getDeviceStatus() {
        JSONObject status = McpDispatcher.object("android_sdk", Build.VERSION.SDK_INT,
                "volume_control_available", supportsVolumeControl(), "media_key_available", supportsMediaControl());
        try {
            if (audio != null) {
                int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                status.put("audio_speaker", McpDispatcher.object("volume", maximum > 0 ? Math.round(100f * current / maximum) : 0,
                        "step", current, "max_step", maximum, "muted", audio.isStreamMute(AudioManager.STREAM_MUSIC)));
                status.put("music_active", audio.isMusicActive());
            }
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(memory);
                status.put("memory", McpDispatcher.object("available_mib", memory.availMem / (1024 * 1024), "low_memory", memory.lowMemory));
            }
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) status.put("battery_percent", Math.min(100, Math.round(100f * level / scale)));
            }
            return status;
        } catch (org.json.JSONException impossible) { throw new IllegalStateException("Invalid platform status", impossible); }
    }

    @Override public JSONObject setVolume(int percent) {
        requireVolume();
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("Volume out of range");
        int min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.max(min, Math.min(max, Math.round(max * percent / 100f)));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != target) throw new IllegalStateException("Volume restricted");
        return getDeviceStatus();
    }

    @Override public JSONObject adjustVolume(int direction) {
        requireVolume();
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("Invalid direction");
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction < 0 ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE, 0);
        return getDeviceStatus();
    }

    @Override public JSONObject setMuted(boolean muted) {
        requireVolume();
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC) != muted) throw new IllegalStateException("Mute restricted");
        return getDeviceStatus();
    }

    @Override public JSONObject mediaControl(String action) {
        if (audio == null) throw new IllegalStateException("Audio unavailable");
        int key;
        switch (action) {
            case "play": key = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": key = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "next": key = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": key = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            default: throw new IllegalArgumentException("Unsupported media action");
        }
        long time = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, key, 0));
        return McpDispatcher.object("status", "sent_to_system", "action", action,
                "playback_confirmed", false, "music_active", audio.isMusicActive());
    }

    private void requireVolume() {
        if (!supportsVolumeControl()) throw new IllegalStateException("Volume unavailable");
    }
}
