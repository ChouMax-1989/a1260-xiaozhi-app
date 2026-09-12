package io.github.choumax.a1260xiaozhi.wake;

import android.content.Context;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** One worker owns this instance and its native objects. No network or retained audio. */
public final class WakeKeywordVerifier implements AutoCloseable {
    public enum Result { MATCH, NO_MATCH }
    private static WakeKeywordVerifier cached;
    private static final Object CACHE_LOCK = new Object();
    private KeywordSpotter spotter;
    private OnlineStream stream;
    private final float[] frameBuffer = new float[320];
    private File keywordFile;
    private final String keyword;
    final float threshold;
    final long lexiconMillis;
    final long nativeLoadMillis;

    public WakeKeywordVerifier(Context context, String keyword) throws IOException {
        this(context, keyword, 1);
    }

    WakeKeywordVerifier(Context context, String keyword, int threads) throws IOException {
        this.keyword = keyword;
        this.threshold = new WakeSettings(context).keywordThreshold();
        long begin = android.os.SystemClock.elapsedRealtime();
        String encoded = WakeKeywordLexicon.encode(new InputStreamReader(
                context.getAssets().open("kws/lexicon.tsv"), StandardCharsets.UTF_8), keyword);
        lexiconMillis = android.os.SystemClock.elapsedRealtime() - begin;
        File directory = prepareModels(context);
        try {
            keywordFile = File.createTempFile("keyword-", ".txt", directory);
            Files.write(keywordFile.toPath(), encoded.getBytes(StandardCharsets.UTF_8));
            OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
            transducer.setEncoder(new File(directory, "encoder.onnx").getPath());
            transducer.setDecoder(new File(directory, "decoder.onnx").getPath());
            transducer.setJoiner(new File(directory, "joiner.onnx").getPath());
            OnlineModelConfig model = new OnlineModelConfig();
            model.setTransducer(transducer);
            model.setTokens(new File(directory, "tokens.txt").getPath());
            model.setModelType("zipformer2");
            model.setNumThreads(threads);
            model.setDebug(false);
            KeywordSpotterConfig config = new KeywordSpotterConfig();
            config.setModelConfig(model);
            config.setKeywordsFile(keywordFile.getPath());
            config.setKeywordsScore(1.5f);
            config.setKeywordsThreshold(threshold);
            config.setNumTrailingBlanks(2);
            begin = android.os.SystemClock.elapsedRealtime();
            spotter = new KeywordSpotter(null, config);
            nativeLoadMillis = android.os.SystemClock.elapsedRealtime() - begin;
            renewStream();
        } catch (IOException | RuntimeException | LinkageError e) {
            close();
            throw e;
        }
    }

    /** Retain only one idle model while the user keeps wake enabled; never retain a microphone. */
    static WakeKeywordVerifier acquireListening(Context context, String keyword) throws IOException {
        WakeKeywordVerifier previous;
        synchronized (CACHE_LOCK) { previous = cached; cached = null; }
        if (previous != null) {
            if (previous.keyword.equals(keyword)
                    && Float.compare(previous.threshold, new WakeSettings(context).keywordThreshold()) == 0) {
                previous.renewStream(); return previous;
            }
            previous.close();
        }
        return new WakeKeywordVerifier(context, keyword);
    }

    static void recycleListening(Context context, WakeKeywordVerifier recognizer) {
        recognizer.clearAudio();
        // Drop the stream before caching; only the immutable model stays resident during playback.
        if (recognizer.stream != null) { recognizer.stream.release(); recognizer.stream = null; }
        WakeKeywordVerifier previous = null;
        synchronized (CACHE_LOCK) {
            if (new WakeSettings(context).isEnabled()) { previous = cached; cached = recognizer; }
            else previous = recognizer;
        }
        if (previous != null) previous.close();
    }

    static void discardCached() {
        WakeKeywordVerifier previous;
        synchronized (CACHE_LOCK) { previous = cached; cached = null; }
        if (previous != null) {
            // UI calls setEnabled(false); native destruction must not block the UI thread.
            new Thread(previous::close, "wake-model-release").start();
        }
    }

    private static synchronized File prepareModels(Context context) throws IOException {
        File directory = new File(context.getCodeCacheDir(), "kws-1.13.7-epoch12-v1");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Model directory unavailable");
        for (String name : new String[]{"encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"}) {
            File target = new File(directory, name);
            if (target.isFile()) continue;
            File temporary = new File(directory, name + ".tmp");
            try (java.io.InputStream input = context.getAssets().open("kws/" + name)) {
                Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return directory;
    }

    /** Enrollment uses an independent stream for each full utterance. */
    public Result verify(short[] pcm) {
        if (pcm == null || pcm.length < 320 || pcm.length > 16000 * 8)
            throw new IllegalArgumentException("Invalid recording length");
        clearAudio(); renewStream();
        boolean matched = false;
        for (int offset = 0; offset < pcm.length; offset += 320) {
            int count = Math.min(320, pcm.length - offset);
            float[] block = count == 320 ? frameBuffer : new float[count];
            for (int i = 0; i < count; i++) block[i] = pcm[offset + i] / 32768f;
            matched |= accept(block);
            Arrays.fill(block, 0);
        }
        // Flush the model's right context, also used by the upstream file examples.
        matched |= accept(new float[16000]);
        renewStream();
        return matched ? Result.MATCH : Result.NO_MATCH;
    }

    boolean acceptFrame(short[] pcm) {
        if (pcm.length != frameBuffer.length) throw new IllegalArgumentException("Expected 20 ms frame");
        for (int i = 0; i < pcm.length; i++) frameBuffer[i] = pcm[i] / 32768f;
        boolean matched;
        try { matched = accept(frameBuffer); }
        finally { Arrays.fill(frameBuffer, 0); }
        // Upstream features.cc GetFrames() calls PopWrapper() to discard consumed frames.
        // Do not periodically restart the stream and introduce a recognition gap.
        return matched;
    }

    private boolean accept(float[] samples) {
        if (stream == null) throw new IllegalStateException("Recognizer closed");
        stream.acceptWaveform(samples, 16000);
        boolean matched = false;
        while (spotter.isReady(stream)) {
            spotter.decode(stream);
            if ("wake".equals(spotter.getResult(stream).getKeyword())) {
                matched = true;
                spotter.reset(stream);
            }
        }
        return matched;
    }

    private void renewStream() {
        if (stream != null) stream.release();
        stream = spotter.createStream("");
        if (stream.getPtr() == 0) throw new IllegalStateException("Recognizer stream unavailable");
    }

    private void clearAudio() {
        Arrays.fill(frameBuffer, 0);
    }

    @Override public void close() {
        if (stream != null) { stream.release(); stream = null; }
        if (spotter != null) { spotter.release(); spotter = null; }
        clearAudio();
        if (keywordFile != null) { keywordFile.delete(); keywordFile = null; }
    }
}
