package io.github.choumax.a1260xiaozhi;

import android.app.Instrumentation;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Verifies that the original PCM end cue is non-silent and completes through MediaPlayer. */
public final class ConversationEndCueDeviceInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        long startedAt = SystemClock.elapsedRealtime();
        Bundle output = new Bundle();
        AtomicReference<MediaPlayer> player = new AtomicReference<>();
        try {
            verifyPcmIsAudible();
            CountDownLatch completed = new CountDownLatch(1);
            AtomicBoolean playbackError = new AtomicBoolean();
            runOnMainSync(() -> {
                MediaPlayer cue = MediaPlayer.create(getTargetContext(), R.raw.conversation_end);
                if (cue == null) { playbackError.set(true); completed.countDown(); return; }
                player.set(cue);
                cue.setOnCompletionListener(owned -> { release(owned); player.compareAndSet(owned, null); completed.countDown(); });
                cue.setOnErrorListener((owned, what, extra) -> { playbackError.set(true); release(owned); player.compareAndSet(owned, null); completed.countDown(); return true; });
                try { cue.start(); }
                catch (RuntimeException failure) { playbackError.set(true); release(cue); player.compareAndSet(cue, null); completed.countDown(); }
            });
            boolean finished = completed.await(3, TimeUnit.SECONDS);
            MediaPlayer unfinished = player.getAndSet(null);
            if (unfinished != null) runOnMainSync(() -> release(unfinished));
            if (!finished || playbackError.get()) throw new AssertionError("cue playback failed");
            finishWith(output, -1, "PASS", startedAt);
        } catch (Throwable failure) {
            MediaPlayer unfinished = player.getAndSet(null);
            if (unfinished != null) {
                try { runOnMainSync(() -> release(unfinished)); } catch (RuntimeException ignored) { }
            }
            finishWith(output, 1, "FAIL", startedAt);
        }
    }

    private void verifyPcmIsAudible() throws Exception {
        byte[] wav;
        try (InputStream input = getTargetContext().getResources().openRawResource(R.raw.conversation_end);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096]; int count;
            while ((count = input.read(block)) != -1) output.write(block, 0, count);
            wav = output.toByteArray();
        }
        if (wav.length < 48 || !ascii(wav, 0, "RIFF") || !ascii(wav, 8, "WAVE")) throw new AssertionError("invalid wav");
        int dataOffset = -1, dataLength = 0;
        for (int offset = 12; offset + 8 <= wav.length;) {
            int length = littleEndianInt(wav, offset + 4);
            if (length < 0 || offset + 8L + length > wav.length) throw new AssertionError("invalid chunk");
            if (ascii(wav, offset, "data")) { dataOffset = offset + 8; dataLength = length; break; }
            offset += 8 + length + (length & 1);
        }
        if (dataOffset < 0 || dataLength < 2000 || (dataLength & 1) != 0) throw new AssertionError("missing pcm");
        long energy = 0; int peak = 0;
        for (int offset = dataOffset; offset < dataOffset + dataLength; offset += 2) {
            int sample = (short)((wav[offset] & 255) | (wav[offset + 1] << 8));
            int magnitude = Math.abs(sample); peak = Math.max(peak, magnitude); energy += (long)sample * sample;
        }
        if (peak < 1500 || energy / (dataLength / 2L) < 40_000L) throw new AssertionError("silent pcm");
    }

    private static boolean ascii(byte[] source, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > source.length) return false;
        for (int i = 0; i < expected.length(); i++) if ((source[offset + i] & 255) != expected.charAt(i)) return false;
        return true;
    }

    private static int littleEndianInt(byte[] source, int offset) {
        return (source[offset] & 255) | ((source[offset + 1] & 255) << 8)
                | ((source[offset + 2] & 255) << 16) | (source[offset + 3] << 24);
    }

    private static void release(MediaPlayer player) {
        try { player.setOnCompletionListener(null); player.setOnErrorListener(null); player.release(); }
        catch (RuntimeException ignored) { }
    }

    private void finishWith(Bundle output, int code, String result, long startedAt) {
        output.putString("result", result + " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt));
        finish(code, output);
    }
}
