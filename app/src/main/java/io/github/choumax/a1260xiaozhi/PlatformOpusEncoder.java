package io.github.choumax.a1260xiaozhi;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Platform codec only. Capture completion owns final drain and release. */
public final class PlatformOpusEncoder implements AutoCloseable {
    public static final int SAMPLE_RATE = 16000, SAMPLES_PER_FRAME = 320;
    private MediaCodec codec;
    private long inputSamples;
    private boolean ended, outputEnded;
    public static boolean isAdvertised() {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) for (String type : info.getSupportedTypes()) if ("audio/opus".equalsIgnoreCase(type)) return true;
        }
        return false;
    }
    public synchronized void start() throws IOException {
        if (codec != null) throw new IOException("Encoder already started.");
        if (!isAdvertised()) throw new IOException("This Android device has no platform Opus encoder.");
        MediaCodec created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 24000);
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT);
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); created.start();
            codec = created; inputSamples = 0; ended = outputEnded = false;
        } catch (RuntimeException e) { created.release(); throw new IOException("Opus encoder configuration failed.", e); }
    }

    /** Codec output can lag input and can contain multiple packets per capture callback. */
    public synchronized List<byte[]> encodeAvailable(short[] pcm) throws IOException {
        if (codec == null || ended) throw new IOException("Opus encoder is not accepting input.");
        if (pcm == null || pcm.length != SAMPLES_PER_FRAME) throw new IOException("Expected one 20 ms PCM frame.");
        List<byte[]> packets = new ArrayList<>();
        int input = waitForInput(packets);
        ByteBuffer buffer = codec.getInputBuffer(input);
        if (buffer == null || buffer.capacity() < pcm.length * 2) throw new IOException("Opus input buffer unavailable.");
        buffer.clear(); buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm);
        codec.queueInputBuffer(input, 0, pcm.length * 2, inputSamples * 1_000_000L / SAMPLE_RATE, 0);
        inputSamples += pcm.length; drain(packets, 0); return packets;
    }

    /** Kept for the one-frame device codec probe; streaming uses encodeAvailable. */
    public synchronized byte[] encode(short[] pcm) throws IOException {
        List<byte[]> packets = encodeAvailable(pcm);
        for (int i = 0; packets.isEmpty() && i < 20; i++) drain(packets, 10000);
        if (packets.size() != 1) throw new IOException("One-frame codec probe did not return exactly one packet.");
        return packets.get(0);
    }

    public synchronized List<byte[]> finish() throws IOException {
        List<byte[]> packets = new ArrayList<>();
        if (codec == null || ended) return packets;
        int input = waitForInput(packets);
        codec.queueInputBuffer(input, 0, 0, inputSamples * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM); ended = true;
        for (int i = 0; !outputEnded && i < 100; i++) drain(packets, 10000);
        if (!outputEnded) throw new IOException("Opus encoder did not finish queued audio.");
        return packets;
    }

    private int waitForInput(List<byte[]> packets) throws IOException {
        for (int i = 0; i < 30; i++) {
            drain(packets, 0);
            int input = codec.dequeueInputBuffer(10000); if (input >= 0) return input;
        }
        throw new IOException("Opus encoder input stalled.");
    }
    private void drain(List<byte[]> packets, long waitUs) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int i = 0; i < 64; i++) {
            int out = codec.dequeueOutputBuffer(info, i == 0 ? waitUs : 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (out < 0) continue;
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEnded = true;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) continue;
                if (info.size > 4000 || packets.size() >= 64) throw new IOException("Opus output budget exceeded.");
                ByteBuffer buffer = codec.getOutputBuffer(out);
                if (buffer == null || info.offset < 0 || info.size > buffer.capacity() - info.offset) throw new IOException("Invalid Opus output buffer.");
                buffer.position(info.offset); buffer.limit(info.offset + info.size);
                byte[] packet = new byte[info.size]; buffer.get(packet); packets.add(packet);
            } finally { codec.releaseOutputBuffer(out, false); }
        }
    }
    @Override public synchronized void close() {
        MediaCodec owned = codec; codec = null;
        if (owned != null) { try { owned.stop(); } catch (RuntimeException ignored) { } owned.release(); }
    }
}