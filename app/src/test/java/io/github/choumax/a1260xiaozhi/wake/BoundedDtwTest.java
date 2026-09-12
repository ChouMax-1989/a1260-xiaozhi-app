package io.github.choumax.a1260xiaozhi.wake;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class BoundedDtwTest {
    private static float[][] sequence(int repetitions, float shift) {
        float[][] frames = new float[8 * repetitions][WakeFeatures.DIMENSIONS];
        for (int i = 0; i < frames.length; i++) Arrays.fill(frames[i], i / repetitions + shift);
        return frames;
    }
    @Test public void permitsModerateTimingVariation() {
        float[][] a = sequence(3, 0), b = sequence(4, 0);
        assertEquals(0, BoundedDtw.distance(a, b), 1e-6);
        assertTrue(BoundedDtw.distance(a, sequence(3, 4)) > 0);
    }
    @Test public void excessiveDurationMismatchCannotWake() {
        assertTrue(Double.isInfinite(BoundedDtw.distance(sequence(1, 0), sequence(3, 0))));
    }
    @Test public void requiresTwoAgreeingTemplatesAndUsesThreshold() {
        float[][] candidate = sequence(3, 0), different = sequence(3, 20);
        WakeMatcher one = new WakeMatcher(new float[][][]{candidate, different, different});
        assertFalse(one.match(candidate, 0.78).matched);
        WakeMatcher two = new WakeMatcher(new float[][][]{candidate, candidate, different});
        assertTrue(two.match(candidate, 0.95).matched);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsNanFeatures() {
        float[][] a = sequence(3, 0); a[0][0] = Float.NaN; BoundedDtw.distance(a, a);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsNanThreshold() {
        float[][] a = sequence(3, 0); new WakeMatcher(new float[][][]{a, a, a}).match(a, Double.NaN);
    }
}
