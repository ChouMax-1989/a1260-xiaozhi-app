package io.github.choumax.a1260xiaozhi;

import java.util.Locale;

/** Pure-Java bounds and display conversion for the wake-session follow-up window. */
public final class FollowUpSettings {
    public static final int DEFAULT_WAIT_MS = 500;
    public static final int MIN_WAIT_MS = 500;
    public static final int MAX_WAIT_MS = 10_000;
    public static final int STEP_MS = 100;
    public static final int MAX_PROGRESS = (MAX_WAIT_MS - MIN_WAIT_MS) / STEP_MS;

    private FollowUpSettings() { }

    public static int normalizeMs(int waitMs) {
        int clamped = Math.max(MIN_WAIT_MS, Math.min(MAX_WAIT_MS, waitMs));
        int steps = Math.round((clamped - MIN_WAIT_MS) / (float) STEP_MS);
        return MIN_WAIT_MS + steps * STEP_MS;
    }

    public static int fromProgress(int progress) {
        int safeProgress = Math.max(0, Math.min(MAX_PROGRESS, progress));
        return MIN_WAIT_MS + safeProgress * STEP_MS;
    }

    public static int toProgress(int waitMs) {
        return (normalizeMs(waitMs) - MIN_WAIT_MS) / STEP_MS;
    }

    public static String secondsLabel(int waitMs) {
        int normalized = normalizeMs(waitMs);
        if (normalized % 1000 == 0) return (normalized / 1000) + " 秒";
        return String.format(Locale.ROOT, "%.1f 秒", normalized / 1000.0);
    }
}
