package io.github.choumax.a1260xiaozhi.wake;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WakeSensitivityTest {
    @Test public void defaultRaisesRecallAndOldOperatingPointRemainsAvailable() {
        assertEquals(0.15f, WakeSensitivity.threshold(WakeSensitivity.DEFAULT), 0.00001f);
        assertEquals(0.25f, WakeSensitivity.threshold(60), 0.00001f);
    }
    @Test public void increasingSliderAlwaysLowersThresholdWithoutReachingZero() {
        assertEquals(0.55f, WakeSensitivity.threshold(0), 0.00001f);
        assertEquals(0.05f, WakeSensitivity.threshold(100), 0.00001f);
        for (int value=1; value<=100; value++)
            assertTrue(WakeSensitivity.threshold(value) < WakeSensitivity.threshold(value-1));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsUnderflow() { WakeSensitivity.threshold(-1); }
    @Test(expected=IllegalArgumentException.class) public void rejectsOverflow() { WakeSensitivity.threshold(101); }
}
