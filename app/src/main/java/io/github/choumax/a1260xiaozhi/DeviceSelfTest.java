package io.github.choumax.a1260xiaozhi;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs a real microphone -> platform Opus encoder -> platform Opus decoder probe without a server. */
final class DeviceSelfTest {
    static final class Result { final boolean passed; final String message; Result(boolean passed, String message) { this.passed = passed; this.message = message; } }
    private DeviceSelfTest() { }

    static Result run(Context context) {
        AudioCapture capture = null;
        PlatformOpusEncoder encoder = null;
        MediaCodec decoder = null;
        try {
            AtomicReference<short[]> pcmRef = new AtomicReference<>();
            AtomicReference<String> captureError = new AtomicReference<>();
            CountDownLatch frameReady = new CountDownLatch(1);
            capture = new AudioCapture(context);
            capture.start(frame -> { if (pcmRef.compareAndSet(null, Arrays.copyOf(frame, frame.length))) frameReady.countDown(); }, error -> { captureError.set(error); frameReady.countDown(); });
            if (!frameReady.await(3, TimeUnit.SECONDS)) return new Result(false, "SELF-TEST FAIL: microphone did not produce a frame.");
            capture.close(); capture = null;
            if (captureError.get() != null) return new Result(false, "SELF-TEST FAIL: " + captureError.get());
            short[] pcm = pcmRef.get();
            if (pcm == null || pcm.length != PlatformOpusEncoder.SAMPLES_PER_FRAME) return new Result(false, "SELF-TEST FAIL: microphone frame size is invalid.");

            encoder = new PlatformOpusEncoder(); encoder.start();
            byte[] packet = encoder.encode(pcm);
            if (packet.length == 0) return new Result(false, "SELF-TEST FAIL: Opus encoder returned an empty packet.");

            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, PlatformOpusEncoder.SAMPLE_RATE, 1);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(OpusPlayback.opusHead(PlatformOpusEncoder.SAMPLE_RATE)));
            ByteBuffer preSkip = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0); preSkip.flip(); format.setByteBuffer("csd-1", preSkip);
            ByteBuffer seekPreRoll = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000L); seekPreRoll.flip(); format.setByteBuffer("csd-2", seekPreRoll);
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            String decoderName = decoder.getName();
            decoder.configure(format, null, null, 0); decoder.start();
            boolean gotPcm = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int n = 0; n < 4 && !gotPcm; n++) {
                int inputIndex = decoder.dequeueInputBuffer(100_000);
                if (inputIndex < 0) continue;
                ByteBuffer input = decoder.getInputBuffer(inputIndex);
                if (input == null) continue;
                input.clear(); input.put(packet);
                decoder.queueInputBuffer(inputIndex, 0, packet.length, n * 20_000L, 0);
                for (int drain = 0; drain < 6; drain++) {
                    int outputIndex = decoder.dequeueOutputBuffer(info, 100_000);
                    if (outputIndex >= 0) { gotPcm |= info.size > 0; decoder.releaseOutputBuffer(outputIndex, false); }
                    else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                }
            }
            if (!gotPcm) return new Result(false, "SELF-TEST FAIL: Opus decoder produced no PCM.");
            int peak = 0; for (short sample : pcm) peak = Math.max(peak, Math.abs((int) sample));
            return new Result(true, "SELF-TEST PASS: microphone, Opus encode/decode OK; packet=" + packet.length + " B, mic peak=" + peak + ", decoder=" + decoderName);
        } catch (Exception e) {
            return new Result(false, "SELF-TEST FAIL: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (capture != null) capture.close();
            if (encoder != null) encoder.close();
            if (decoder != null) { try { decoder.stop(); } catch (Exception ignored) { } decoder.release(); }
        }
    }
}
