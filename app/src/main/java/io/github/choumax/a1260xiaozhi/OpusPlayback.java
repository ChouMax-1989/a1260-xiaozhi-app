package io.github.choumax.a1260xiaozhi;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/** One worker owns decoder and AudioTrack. Normal completion drains EOS and the hardware playback head. */
final class OpusPlayback implements AutoCloseable {
    private static final int MAX_PACKETS = 3000, MAX_PACKET_BYTES = 4000;
    private static final long MAX_QUEUED_US = 120_000_000;
    private final Object lock = new Object();
    private final ArrayDeque<Packet> queue = new ArrayDeque<>();
    private final int streamSampleRate;
    private volatile boolean running, inputFinished;
    private boolean started;
    private long queuedUs;
    private int queuedBytes;
    private Runnable drained;
    private Thread worker;
    // These resources are accessed/released only by worker.
    private MediaCodec decoder;
    private AudioTrack track;
    private int outputRate;
    private long writtenFrames, headWraps, lastHead, nextPtsUs;
    private boolean outputEnded;
    private volatile long firstPacketAt;
    private boolean firstWriteMeasured, firstHeadMeasured;

    private static final class Packet {
        final byte[] data; final long durationUs;
        Packet(byte[] data, long durationUs) { this.data = data; this.durationUs = durationUs; }
    }
    OpusPlayback(int streamSampleRate) { this.streamSampleRate = streamSampleRate; }
    void start(Consumer<String> error) throws IOException {
        synchronized (lock) {
            if (started) throw new IOException("Playback cannot be restarted.");
            started = true; running = true;
            worker = new Thread(() -> decodeLoop(error), "xiaozhi-playback"); worker.start();
        }
    }
    void offer(byte[] packet) throws IOException {
        if (packet == null || packet.length == 0 || packet.length > MAX_PACKET_BYTES) throw new IOException("Invalid Opus packet size.");
        long duration = packetDurationUs(packet);
        synchronized (lock) {
            if (!running) return;
            if (firstPacketAt == 0) firstPacketAt = SystemClock.elapsedRealtime();
            if (inputFinished) throw new IOException("Audio arrived after TTS end.");
            if (queue.size() >= MAX_PACKETS || queuedUs + duration > MAX_QUEUED_US || queuedBytes + packet.length > 512 * 1024) throw new IOException("Incoming speech exceeded the bounded playback buffer.");
            queue.addLast(new Packet(packet, duration)); queuedUs += duration; queuedBytes += packet.length; lock.notifyAll();
        }
    }
    void finish(Runnable onDrained) {
        synchronized (lock) {
            if (!running || inputFinished) return;
            drained = onDrained; inputFinished = true; lock.notifyAll();
        }
    }
    private Packet poll() {
        synchronized (lock) {
            Packet next = queue.pollFirst(); if (next != null) { queuedUs -= next.durationUs; queuedBytes -= next.data.length; } return next;
        }
    }
    private boolean noQueuedPackets() { synchronized (lock) { return queue.isEmpty(); } }

    private void decodeLoop(Consumer<String> error) {
        boolean complete = false; String failure = null;
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, streamSampleRate, 1);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(streamSampleRate)));
            format.setByteBuffer("csd-1", nativeLong(0));
            format.setByteBuffer("csd-2", nativeLong(80_000_000L));
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            decoder.configure(format, null, null, 0); decoder.start();
            Packet pending = null; boolean eosQueued = false;
            long stalledSince = SystemClock.elapsedRealtime(), eosDeadline = 0;
            while (running && !outputEnded) {
                boolean progress = drainOutput();
                if (pending == null) pending = poll();
                if (!eosQueued && (pending != null || (inputFinished && noQueuedPackets()))) {
                    int in = decoder.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        if (pending == null) {
                            decoder.queueInputBuffer(in, 0, 0, nextPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosQueued = true; eosDeadline = SystemClock.elapsedRealtime() + 5000;
                        } else {
                            ByteBuffer buffer = decoder.getInputBuffer(in);
                            if (buffer == null || buffer.capacity() < pending.data.length) throw new IOException("Opus decoder input unavailable.");
                            buffer.clear(); buffer.put(pending.data);
                            decoder.queueInputBuffer(in, 0, pending.data.length, nextPtsUs, 0);
                            nextPtsUs += pending.durationUs; pending = null;
                        }
                        progress = true;
                    }
                }
                long now = SystemClock.elapsedRealtime();
                if (progress) stalledSince = now;
                else if (pending != null && now - stalledSince > 3000) throw new IOException("Opus decoder input stalled.");
                if (eosQueued && now > eosDeadline) throw new IOException("Opus decoder did not finish queued audio.");
                if (!progress) { synchronized (lock) { if (running) lock.wait(10); } }
            }
            if (running && outputEnded) { awaitSpeakerDrain(); complete = running; }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); if (running) failure = "Playback worker interrupted."; }
        catch (Exception e) { if (running) failure = e instanceof IOException ? e.getMessage() : "Platform Opus playback failed."; }
        finally {
            synchronized (lock) { queue.clear(); queuedUs = 0; queuedBytes = 0; }
            if (track != null) { try { track.pause(); track.flush(); } catch (RuntimeException ignored) { } track.release(); track = null; }
            if (decoder != null) { try { decoder.stop(); } catch (RuntimeException ignored) { } decoder.release(); decoder = null; }
            boolean notify = running; running = false;
            if (notify && failure != null) error.accept(failure);
            else if (notify && complete && drained != null) drained.run();
        }
    }
    private static ByteBuffer nativeLong(long value) { ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()); buffer.putLong(value); buffer.flip(); return buffer; }

    private boolean drainOutput() throws IOException, InterruptedException {
        boolean progress = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int i = 0; running && i < 64; i++) {
            int out = decoder.dequeueOutputBuffer(info, 0);
            if (out == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { openTrack(decoder.getOutputFormat()); progress = true; continue; }
            if (out < 0) continue;
            progress = true;
            try {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    if (track == null) openTrack(decoder.getOutputFormat());
                    ByteBuffer pcm = decoder.getOutputBuffer(out);
                    if (pcm == null || info.offset < 0 || info.size > pcm.capacity() - info.offset || (info.size & 1) != 0)
                        throw new IOException("Invalid decoded PCM buffer.");
                    pcm.position(info.offset); pcm.limit(info.offset + info.size);
                    long lastProgress = SystemClock.elapsedRealtime();
                    while (running && pcm.hasRemaining()) {
                        int count = track.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                        if (count < 0 || (count & 1) != 0) throw new IOException("Speaker write failed.");
                        if (count > 0) {
                            writtenFrames += count / 2; lastProgress = SystemClock.elapsedRealtime();
                            if (!firstWriteMeasured) {
                                firstWriteMeasured = true;
                                android.util.Log.i("A1260Latency", "packet_to_pcm_write_ms=" + (lastProgress-firstPacketAt)
                                        + " track_buffer_ms=" + track.getBufferSizeInFrames()*1000/outputRate);
                            }
                            measureFirstPlaybackHead();
                        }
                        else {
                            if (SystemClock.elapsedRealtime() - lastProgress > 3000) throw new IOException("Speaker output stalled.");
                            Thread.sleep(5);
                        }
                    }
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputEnded = true;
            } finally { decoder.releaseOutputBuffer(out, false); }
        }
        return progress;
    }
    private void openTrack(MediaFormat format) throws IOException {
        int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING) ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
        if (channels != 1 || encoding != AudioFormat.ENCODING_PCM_16BIT) throw new IOException("Unsupported decoded PCM format.");
        if (track != null) { if (rate != outputRate) throw new IOException("Mid-stream sample rate change is unsupported."); return; }
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("Speaker output unavailable.");
        track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min * 2, rate / 5 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Speaker initialization failed.");
        outputRate = rate; track.play();
    }
    private long playedFrames() {
        long head = track.getPlaybackHeadPosition() & 0xffffffffL;
        if (head < lastHead) headWraps += 1L << 32;
        lastHead = head; return headWraps + head;
    }
    private void measureFirstPlaybackHead() {
        if (!firstHeadMeasured && firstPacketAt > 0 && track.getPlaybackHeadPosition() != 0) {
            firstHeadMeasured = true;
            android.util.Log.i("A1260Latency", "packet_to_playback_head_ms=" + (SystemClock.elapsedRealtime()-firstPacketAt));
        }
    }
    private void awaitSpeakerDrain() throws IOException, InterruptedException {
        if (track == null || writtenFrames == 0) return;
        // Android 11 has no public setStartThresholdInFrames. Prime a short stream with silence;
        // completion still waits for every real decoded sample, not for an arbitrary wall-clock delay.
        int primeFrames = (int) Math.max(0, track.getBufferSizeInFrames() - writtenFrames);
        if (primeFrames > 0) {
            ByteBuffer silence = ByteBuffer.allocate(primeFrames * 2);
            long primingDeadline = SystemClock.elapsedRealtime() + 3000;
            while (running && silence.hasRemaining()) {
                int count = track.write(silence, silence.remaining(), AudioTrack.WRITE_NON_BLOCKING);
                if (count < 0) throw new IOException("Speaker could not finish a short stream.");
                if (count == 0) {
                    if (SystemClock.elapsedRealtime() > primingDeadline) throw new IOException("Speaker priming stalled.");
                    Thread.sleep(5);
                }
            }
        }
        long remaining = Math.max(0, writtenFrames - playedFrames());
        long deadline = SystemClock.elapsedRealtime() + Math.max(5000, remaining * 1000 / outputRate + 2000);
        while (running && playedFrames() < writtenFrames) {
            measureFirstPlaybackHead();
            if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Speaker did not drain its queued samples.");
            Thread.sleep(10);
        }
    }
    static long packetDurationUs(byte[] packet) throws IOException {
        if (packet == null || packet.length < 1) throw new IOException("Empty Opus packet.");
        int toc = packet[0] & 255, code = toc & 3;
        int frames = code == 0 ? 1 : code == 3 ? (packet.length > 1 ? packet[1] & 63 : 0) : 2;
        long frameUs;
        if ((toc & 128) != 0) frameUs = 2500L << ((toc >> 3) & 3);
        else if ((toc & 96) == 96) frameUs = (toc & 8) != 0 ? 20000 : 10000;
        else { int index = (toc >> 3) & 3; frameUs = index == 3 ? 60000 : 10000L << index; }
        long total = frameUs * frames;
        if (frames == 0 || total > 120000) throw new IOException("Invalid Opus packet duration.");
        return total;
    }
    static byte[] opusHead(int rate) { return new byte[] {'O','p','u','s','H','e','a','d',1,1,0,0,(byte) rate,(byte) (rate >> 8),(byte) (rate >> 16),(byte) (rate >> 24),0,0,0}; }
    @Override public void close() {
        synchronized (lock) { running = false; queue.clear(); queuedUs = 0; queuedBytes = 0; lock.notifyAll(); if (worker != null) worker.interrupt(); }
        // Worker finally releases after all decoder/track calls have stopped; no release-after-join race.
    }
}
