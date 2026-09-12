package io.github.choumax.a1260xiaozhi;

import android.app.Instrumentation;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline device benchmark for the real platform OpusPlayback decoder and AudioTrack path.
 * "Cold" is a fresh playback after a one-second idle interval; "warm" immediately repeats
 * with another fresh playback. Android may retain codec/audio caches between cold trials.
 * The playback head is a framework observation, not an acoustic speaker measurement.
 */
public final class PlaybackLatencyDeviceInstrumentation extends Instrumentation {
    private static final int RATE = PlatformOpusEncoder.SAMPLE_RATE;
    private static final int FRAME_SAMPLES = PlatformOpusEncoder.SAMPLES_PER_FRAME;
    private static final int FRAMES = 15; // 300 ms, enough to rule out immediate empty EOS.
    private static final long START_TIMEOUT_NS = 3_000_000_000L;
    private static final long RUN_TIMEOUT_NS = 5_000_000_000L;
    private static final Field DECODER = field("decoder");
    private static final Field TRACK = field("track");
    private static final Field WRITTEN_FRAMES = field("writtenFrames");

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            List<byte[]> packets = encodeSyntheticTone();
            long packetDurationUs = 0;
            for (byte[] packet : packets) packetDurationUs += OpusPlayback.packetDurationUs(packet);
            if (packets.isEmpty() || packetDurationUs < 200_000L)
                throw new AssertionError("synthetic stream contains too little audio");

            StringBuilder report = new StringBuilder("PASS offline synthetic Opus playback; ");
            report.append("packets=").append(packets.size());
            report.append(" packetDurationMs=").append(packetDurationUs / 1000);
            report.append("; timings are decoder-ready observation to framework write/head/drain, not speaker acoustics");
            for (int pair = 1; pair <= 3; pair++) {
                SystemClock.sleep(1000);
                appendTrial(report, "cold" + pair, measure(packets));
                appendTrial(report, "warm" + pair, measure(packets));
            }
            output.putString("result", report.toString());
            finish(-1, output);
        } catch (Throwable failure) {
            output.putString("result", "FAIL offline Opus playback benchmark: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            finish(1, output);
        }
    }

    private static List<byte[]> encodeSyntheticTone() throws Exception {
        List<byte[]> packets = new ArrayList<>();
        try (PlatformOpusEncoder encoder = new PlatformOpusEncoder()) {
            encoder.start();
            for (int frame = 0; frame < FRAMES; frame++) {
                short[] pcm = new short[FRAME_SAMPLES];
                for (int sample = 0; sample < FRAME_SAMPLES; sample++) {
                    int index = frame * FRAME_SAMPLES + sample;
                    double seconds = index / (double) RATE;
                    double fade = Math.min(1.0, Math.min(index, FRAMES * FRAME_SAMPLES - index - 1) / 160.0);
                    double modulation = 0.75 + 0.25 * Math.sin(2.0 * Math.PI * 4.0 * seconds);
                    pcm[sample] = (short) Math.round(1800.0 * fade * modulation
                            * Math.sin(2.0 * Math.PI * 1000.0 * seconds));
                }
                packets.addAll(encoder.encodeAvailable(pcm));
            }
            packets.addAll(encoder.finish());
        }
        return packets;
    }

    private static Trial measure(List<byte[]> packets) throws Exception {
        AtomicLong drainedAt = new AtomicLong();
        AtomicReference<String> playbackError = new AtomicReference<>();
        OpusPlayback playback = new OpusPlayback(RATE);
        try {
            playback.start(playbackError::set);
            long decoderReadyAt = awaitDecoderReady(playback);
            for (byte[] packet : packets) playback.offer(packet);
            playback.finish(() -> drainedAt.set(SystemClock.elapsedRealtimeNanos()));

            long firstWriteAt = 0, firstHeadAt = 0, maximumWrittenFrames = 0;
            long deadline = decoderReadyAt + RUN_TIMEOUT_NS;
            while (SystemClock.elapsedRealtimeNanos() < deadline) {
                if (playbackError.get() != null) throw new AssertionError(playbackError.get());
                long now = SystemClock.elapsedRealtimeNanos();
                long written = WRITTEN_FRAMES.getLong(playback);
                maximumWrittenFrames = Math.max(maximumWrittenFrames, written);
                if (written > 0 && firstWriteAt == 0) firstWriteAt = now;
                AudioTrack track = (AudioTrack) TRACK.get(playback);
                if (track != null && firstHeadAt == 0) {
                    try {
                        if ((track.getPlaybackHeadPosition() & 0xffffffffL) > 0) firstHeadAt = now;
                    } catch (IllegalStateException ignored) { /* Worker released the track. */ }
                }
                long complete = drainedAt.get();
                if (complete > 0 && firstWriteAt > 0 && firstHeadAt > 0) {
                    Trial trial = new Trial(decoderReadyAt, firstWriteAt, firstHeadAt,
                            complete, maximumWrittenFrames);
                    trial.assertReasonable();
                    return trial;
                }
                SystemClock.sleep(2);
            }
            throw new AssertionError("timed out: write=" + (firstWriteAt > 0)
                    + " head=" + (firstHeadAt > 0) + " drained=" + (drainedAt.get() > 0)
                    + " writtenFrames=" + maximumWrittenFrames);
        } finally {
            playback.close();
        }
    }

    private static long awaitDecoderReady(OpusPlayback playback) throws Exception {
        long deadline = SystemClock.elapsedRealtimeNanos() + START_TIMEOUT_NS;
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            MediaCodec decoder = (MediaCodec) DECODER.get(playback);
            if (decoder != null) {
                try {
                    // This API becomes available in MediaCodec's started state. The observation
                    // follows the actual decoder.start() by at most the polling delay.
                    decoder.getInputBuffers();
                    return SystemClock.elapsedRealtimeNanos();
                } catch (IllegalStateException ignored) { /* Configure/start still in progress. */ }
            }
            SystemClock.sleep(2);
        }
        throw new AssertionError("decoder did not become ready within 3 seconds");
    }

    private static void appendTrial(StringBuilder report, String label, Trial trial) {
        report.append("; ").append(label)
                .append(" readyToWriteMs=").append(ms(trial.firstWriteAt - trial.readyAt))
                .append(" readyToHeadMs=").append(ms(trial.firstHeadAt - trial.readyAt))
                .append(" readyToDrainMs=").append(ms(trial.drainedAt - trial.readyAt))
                .append(" writtenFrames=").append(trial.writtenFrames);
    }

    private static long ms(long nanos) { return Math.round(nanos / 1_000_000.0); }

    private static Field field(String name) {
        try {
            Field field = OpusPlayback.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static final class Trial {
        final long readyAt, firstWriteAt, firstHeadAt, drainedAt, writtenFrames;
        Trial(long readyAt, long firstWriteAt, long firstHeadAt, long drainedAt, long writtenFrames) {
            this.readyAt = readyAt;
            this.firstWriteAt = firstWriteAt;
            this.firstHeadAt = firstHeadAt;
            this.drainedAt = drainedAt;
            this.writtenFrames = writtenFrames;
        }
        void assertReasonable() {
            if (writtenFrames < RATE / 10) throw new AssertionError("decoded stream under 100 ms");
            if (firstWriteAt < readyAt || firstHeadAt < firstWriteAt || drainedAt < firstHeadAt)
                throw new AssertionError("playback milestones out of order");
            // These generous local-only limits flag multi-second playback stalls while allowing
            // the fixed A1260 platform codec to initialize and AudioTrack to fill its buffer.
            if (firstWriteAt - readyAt > 1_500_000_000L
                    || firstHeadAt - readyAt > 2_500_000_000L
                    || drainedAt - readyAt > 4_500_000_000L)
                throw new AssertionError("local playback exceeded latency budget: write="
                        + ms(firstWriteAt - readyAt) + "ms head="
                        + ms(firstHeadAt - readyAt) + "ms drain="
                        + ms(drainedAt - readyAt) + "ms");
        }
    }
}
