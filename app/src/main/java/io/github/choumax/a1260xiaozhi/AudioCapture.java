package io.github.choumax.a1260xiaozhi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** The capture worker owns AudioRecord.release. close only requests stop and unblocks read. */
final class AudioCapture implements AutoCloseable {
    interface FrameConsumer { void onFrame(short[] pcm) throws IOException; }
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile AudioRecord record;
    private boolean started;
    private final Context context;
    AudioCapture(Context context) { this.context = context.getApplicationContext(); }
    void start(FrameConsumer consumer, Consumer<String> error) throws IOException { start(consumer, error, () -> { }); }

    @SuppressLint("MissingPermission")
    synchronized void start(FrameConsumer consumer, Consumer<String> error, Runnable completed) throws IOException {
        if (started) throw new IOException("Capture instances cannot be restarted.");
        started = true;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new IOException("Microphone permission is not granted.");
        int min = AudioRecord.getMinBufferSize(PlatformOpusEncoder.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("16 kHz microphone capture is unavailable.");
        AudioRecord local = null;
        try {
            local = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, PlatformOpusEncoder.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 4096));
            if (local.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone could not initialize.");
            local.startRecording();
            if (local.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone did not start.");
            record = local; running.set(true);
            AudioRecord owned = local;
            new Thread(() -> readLoop(owned, consumer, error, completed), "xiaozhi-capture").start();
        } catch (IOException | RuntimeException e) {
            running.set(false); record = null;
            if (local != null) { try { local.stop(); } catch (RuntimeException ignored) { } local.release(); }
            throw new IOException("Microphone initialization failed.", e);
        }
    }

    private void readLoop(AudioRecord owned, FrameConsumer consumer, Consumer<String> error, Runnable completed) {
        short[] frame = new short[PlatformOpusEncoder.SAMPLES_PER_FRAME]; int filled = 0, zeroReads = 0;
        String failure = null;
        try {
            while (running.get()) {
                int count = owned.read(frame, filled, frame.length - filled, AudioRecord.READ_BLOCKING);
                if (!running.get()) break;
                if (count < 0) throw new IOException("Microphone read failed.");
                if (count == 0) { if (++zeroReads > 5) throw new IOException("Microphone returned no data."); continue; }
                zeroReads = 0; filled += count;
                AudioRecordingConfiguration configuration = owned.getActiveRecordingConfiguration();
                if (configuration != null && configuration.isClientSilenced()) throw new IOException("Microphone is silenced by Android.");
                if (filled == frame.length) { consumer.onFrame(frame); filled = 0; }
            }
            if (filled > 0) { Arrays.fill(frame, filled, frame.length, (short) 0); consumer.onFrame(frame); }
        } catch (Exception e) { if (running.get()) failure = "Microphone or encoder read failed."; }
        finally {
            running.set(false); Arrays.fill(frame, (short) 0);
            synchronized (this) {
                record = null;
                try { owned.stop(); } catch (RuntimeException ignored) { }
                owned.release();
            }
            try { if (failure != null) error.accept(failure); }
            finally { completed.run(); }
        }
    }

    @Override public synchronized void close() {
        running.set(false);
        AudioRecord active = record;
        if (active != null) { try { active.stop(); } catch (RuntimeException ignored) { } }
        // No cross-thread release or timed join: a codec callback can still be in flight.
    }
}