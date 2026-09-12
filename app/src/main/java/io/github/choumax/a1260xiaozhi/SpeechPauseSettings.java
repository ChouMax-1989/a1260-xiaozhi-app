package io.github.choumax.a1260xiaozhi;

import java.util.Locale;

/** Pure-Java bounds and display conversion for the post-speech send pause. */
public final class SpeechPauseSettings {
    public static final int DEFAULT_PAUSE_MS = 600;
    public static final int MIN_PAUSE_MS = 400;
    public static final int MAX_PAUSE_MS = 2_000;
    public static final int STEP_MS = 100;
    public static final int MAX_PROGRESS = (MAX_PAUSE_MS - MIN_PAUSE_MS) / STEP_MS;

    private SpeechPauseSettings() { }

    public static int normalizeMs(int pauseMs) {
        int clamped = Math.max(MIN_PAUSE_MS, Math.min(MAX_PAUSE_MS, pauseMs));
        int steps = Math.round((clamped - MIN_PAUSE_MS) / (float) STEP_MS);
        return MIN_PAUSE_MS + steps * STEP_MS;
    }

    public static int fromProgress(int progress) {
        int safeProgress = Math.max(0, Math.min(MAX_PROGRESS, progress));
        return MIN_PAUSE_MS + safeProgress * STEP_MS;
    }

    public static int toProgress(int pauseMs) {
        return (normalizeMs(pauseMs) - MIN_PAUSE_MS) / STEP_MS;
    }

    public static String secondsLabel(int pauseMs) {
        int normalized = normalizeMs(pauseMs);
        if (normalized % 1000 == 0) return (normalized / 1000) + " 秒";
        return String.format(Locale.ROOT, "%.1f 秒", normalized / 1000.0);
    }
}
