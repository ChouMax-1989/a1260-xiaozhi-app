package io.github.choumax.a1260xiaozhi;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayDeque;

/** Sequentially plays the official MIT zh-CN activation cue and only decimal digit assets. */
final class ActivationAudioPlayer implements AutoCloseable {
    private final Context context; private final Handler main = new Handler(Looper.getMainLooper()); private final ArrayDeque<String> queue = new ArrayDeque<>(); private MediaPlayer player;
    ActivationAudioPlayer(Context context) { this.context = context.getApplicationContext(); }
    void announce(String code) { main.post(() -> { stopNow(); queue.add("activation-voice/activation.ogg"); for (int i = 0; i < code.length(); i++) { char c = code.charAt(i); if (c >= '0' && c <= '9') queue.add("activation-voice/" + c + ".ogg"); } playNext(); }); }
    private void playNext() { String name = queue.poll(); if (name == null) return; try { AssetFileDescriptor asset = context.getAssets().openFd(name); player = new MediaPlayer(); player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()); player.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength()); asset.close(); player.setOnCompletionListener(done -> { done.release(); player = null; playNext(); }); player.setOnErrorListener((failed, what, extra) -> { failed.release(); player = null; playNext(); return true; }); player.setOnPreparedListener(ready -> ready.start()); player.prepareAsync(); } catch (IOException ignored) { playNext(); } }
    void stopNow() { queue.clear(); if (player != null) { try { player.stop(); } catch (Exception ignored) { } player.release(); player = null; } }
    @Override public void close() { main.post(this::stopNow); }
}
