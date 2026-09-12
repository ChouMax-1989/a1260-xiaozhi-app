package io.github.choumax.a1260xiaozhi.wake;

import java.util.Arrays;

/** Full-utterance DTW with a Sakoe-Chiba band and O(maxFrames) working storage. */
public final class BoundedDtw {
    private BoundedDtw() { }

    public static double distance(float[][] a, float[][] b) {
        validate(a); validate(b);
        int n = a.length, m = b.length;
        if (Math.min(n, m) / (double) Math.max(n, m) < 0.55) return Double.POSITIVE_INFINITY;
        int radius = Math.max(Math.abs(n - m), (int) Math.ceil(Math.max(n, m) * 0.25));
        double[] previous = new double[m + 1], current = new double[m + 1];
        int[] previousLength = new int[m + 1], currentLength = new int[m + 1];
        Arrays.fill(previous, Double.POSITIVE_INFINITY); previous[0] = 0;
        for (int i = 1; i <= n; i++) {
            Arrays.fill(current, Double.POSITIVE_INFINITY); Arrays.fill(currentLength, 0);
            for (int j = Math.max(1, i - radius); j <= Math.min(m, i + radius); j++) {
                double best = previous[j - 1]; int count = previousLength[j - 1];
                if (previous[j] < best) { best = previous[j]; count = previousLength[j]; }
                if (current[j - 1] < best) { best = current[j - 1]; count = currentLength[j - 1]; }
                double local = 0;
                for (int k = 0; k < WakeFeatures.DIMENSIONS; k++) { double d = a[i - 1][k] - b[j - 1][k]; local += d * d; }
                current[j] = best + Math.sqrt(local / WakeFeatures.DIMENSIONS);
                currentLength[j] = count + 1;
            }
            double[] swap = previous; previous = current; current = swap;
            int[] swapLength = previousLength; previousLength = currentLength; currentLength = swapLength;
        }
        return previousLength[m] == 0 ? Double.POSITIVE_INFINITY : previous[m] / previousLength[m];
    }

    private static void validate(float[][] frames) {
        if (frames == null || frames.length == 0 || frames.length > WakeFeatures.MAX_FRAMES)
            throw new IllegalArgumentException("Invalid feature frame count");
        for (float[] frame : frames) {
            if (frame == null || frame.length != WakeFeatures.DIMENSIONS) throw new IllegalArgumentException("Invalid feature dimension");
            for (float value : frame) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite feature");
        }
    }
}
