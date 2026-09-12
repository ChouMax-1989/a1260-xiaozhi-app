package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FollowUpSettingsTest {
    @Test public void exposesTheRequestedDefaultAndRange() {
        assertEquals(500, FollowUpSettings.DEFAULT_WAIT_MS);
        assertEquals(500, FollowUpSettings.MIN_WAIT_MS);
        assertEquals(10_000, FollowUpSettings.MAX_WAIT_MS);
        assertEquals(100, FollowUpSettings.STEP_MS);
        assertEquals(95, FollowUpSettings.MAX_PROGRESS);
    }

    @Test public void convertsEverySeekBarEndpointExactly() {
        assertEquals(500, FollowUpSettings.fromProgress(0));
        assertEquals(10_000, FollowUpSettings.fromProgress(FollowUpSettings.MAX_PROGRESS));
        assertEquals(0, FollowUpSettings.toProgress(500));
        assertEquals(95, FollowUpSettings.toProgress(10_000));
    }

    @Test public void clampsAndSnapsUnexpectedStoredValues() {
        assertEquals(500, FollowUpSettings.normalizeMs(-1));
        assertEquals(500, FollowUpSettings.normalizeMs(549));
        assertEquals(600, FollowUpSettings.normalizeMs(550));
        assertEquals(10_000, FollowUpSettings.normalizeMs(10_001));
        assertEquals(500, FollowUpSettings.fromProgress(-1));
        assertEquals(10_000, FollowUpSettings.fromProgress(96));
    }

    @Test public void formatsTheLiveValueAsSeconds() {
        assertEquals("0.5 秒", FollowUpSettings.secondsLabel(500));
        assertEquals("1 秒", FollowUpSettings.secondsLabel(1000));
        assertEquals("10 秒", FollowUpSettings.secondsLabel(10_000));
    }
}
