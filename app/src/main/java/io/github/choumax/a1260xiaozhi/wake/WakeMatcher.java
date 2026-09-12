package io.github.choumax.a1260xiaozhi.wake;

import java.util.Arrays;

/** Speaker-dependent acoustic match, not speech recognition or identity authentication. */
public final class WakeMatcher {
    public static final double DEFAULT_THRESHOLD = 0.85;
    public static final double MIN_THRESHOLD = 0.50, MAX_THRESHOLD = 0.95;
    private final float[][][] templates;

    public WakeMatcher(float[][][] templates) {
        if (templates == null || templates.length != 3) throw new IllegalArgumentException("Three templates required");
        this.templates = new float[3][][];
        for (int i = 0; i < 3; i++) {
            BoundedDtw.distance(templates[i], templates[i]);
            this.templates[i] = new float[templates[i].length][];
            for (int j = 0; j < templates[i].length; j++) this.templates[i][j] = templates[i][j].clone();
        }
    }

    public Result match(float[][] candidate, double threshold) {
        if (!Double.isFinite(threshold) || threshold < MIN_THRESHOLD || threshold > MAX_THRESHOLD)
            throw new IllegalArgumentException("Invalid wake threshold");
        double[] distances = new double[3];
        for (int i = 0; i < 3; i++) { double ratio=Math.min(candidate.length,templates[i].length)/(double)Math.max(candidate.length,templates[i].length); distances[i] = ratio < 0.75 ? Double.POSITIVE_INFINITY : BoundedDtw.distance(candidate, templates[i]); }
        Arrays.sort(distances);
        // Require agreement with at least two recordings; one accidental close template is insufficient.
        double distance = distances[1];
        double score = Double.isFinite(distance) ? Math.exp(-distance) : 0;
        return new Result(score >= threshold, score, distance);
    }

    public static final class Result {
        public final boolean matched;
        public final double score, distance;
        Result(boolean matched, double score, double distance) { this.matched = matched; this.score = score; this.distance = distance; }
    }
}
