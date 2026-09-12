package io.github.choumax.a1260xiaozhi.wake;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.IOException;

/** Single local-wake mic lease. Session recording must still be coordinated by the host activity. */
final class WakeMicrophone implements AutoCloseable {
    private static boolean leased;
    private final AudioRecord recorder;
    private volatile boolean stopped;
    private boolean closed;

    @SuppressLint("MissingPermission") // Checked immediately before construction; revoke is caught by caller.
    static WakeMicrophone open(Context context) throws IOException {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Microphone permission required");
        synchronized (WakeMicrophone.class) {
            if (leased) throw new IOException("Wake microphone busy");
            leased = true;
        }
        AudioRecord audio = null;
        try {
            int min = AudioRecord.getMinBufferSize(WakeFeatures.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("16 kHz microphone unavailable");
            audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeFeatures.SAMPLE_RATE,
                    // KWS decodes in chunks. Keep two seconds of hardware capture capacity
                    // so one native decode does not overrun the old 200 ms buffer.
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 64000));
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone initialization failed");
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone did not start");
            return new WakeMicrophone(audio);
        } catch (IOException | RuntimeException e) {
            if (audio != null) audio.release();
            synchronized (WakeMicrophone.class) { leased = false; }
            throw e;
        }
    }

    private WakeMicrophone(AudioRecord recorder) { this.recorder = recorder; }

    boolean readFrame(short[] frame) throws IOException {
        int offset = 0, emptyReads = 0;
        while (!stopped && offset < frame.length) {
            int count;
            try { count = recorder.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING); }
            catch (IllegalStateException e) { if (stopped) return false; throw new IOException("Microphone read failed", e); }
            if (stopped) return false;
            if (count < 0) throw new IOException("Microphone read failed");
            android.media.AudioRecordingConfiguration configuration = recorder.getActiveRecordingConfiguration();
            if (configuration != null && configuration.isClientSilenced()) throw new IOException("Microphone silenced by system");
            if (count == 0) { if (++emptyReads >= 5) throw new IOException("Microphone returned no audio"); }
            else { offset += count; emptyReads = 0; }
        }
        return !stopped && offset == frame.length;
    }

    /** Called on stop to unblock read; the worker retains ownership until close. */
    synchronized void cancel() {
        stopped = true;
        if (!closed) { try { recorder.stop(); } catch (IllegalStateException ignored) { /* Already stopped. */ } }
    }

    @Override public synchronized void close() {
        if (closed) return;
        cancel(); closed = true;
        try { recorder.release(); }
        finally { synchronized (WakeMicrophone.class) { leased = false; } }
    }
}
