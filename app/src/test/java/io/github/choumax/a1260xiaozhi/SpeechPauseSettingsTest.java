package io.github.choumax.a1260xiaozhi;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SpeechPauseSettingsTest {
    @Test public void exposesTheRequestedDefaultAndRange() {
        assertEquals(600, SpeechPauseSettings.DEFAULT_PAUSE_MS);
        assertEquals(400, SpeechPauseSettings.MIN_PAUSE_MS);
        assertEquals(2_000, SpeechPauseSettings.MAX_PAUSE_MS);
        assertEquals(100, SpeechPauseSettings.STEP_MS);
        assertEquals(16, SpeechPauseSettings.MAX_PROGRESS);
    }

    @Test public void convertsEverySeekBarEndpointExactly() {
        assertEquals(400, SpeechPauseSettings.fromProgress(0));
        assertEquals(2_000, SpeechPauseSettings.fromProgress(SpeechPauseSettings.MAX_PROGRESS));
        assertEquals(0, SpeechPauseSettings.toProgress(400));
        assertEquals(16, SpeechPauseSettings.toProgress(2_000));
    }

    @Test public void clampsAndSnapsUnexpectedStoredValues() {
        assertEquals(400, SpeechPauseSettings.normalizeMs(-1));
        assertEquals(400, SpeechPauseSettings.normalizeMs(449));
        assertEquals(500, SpeechPauseSettings.normalizeMs(450));
        assertEquals(2_000, SpeechPauseSettings.normalizeMs(2_001));
        assertEquals(400, SpeechPauseSettings.fromProgress(-1));
        assertEquals(2_000, SpeechPauseSettings.fromProgress(17));
    }

    @Test public void formatsTheLiveValueAsSeconds() {
        assertEquals("0.4 秒", SpeechPauseSettings.secondsLabel(400));
        assertEquals("0.6 秒", SpeechPauseSettings.secondsLabel(600));
        assertEquals("1 秒", SpeechPauseSettings.secondsLabel(1000));
        assertEquals("2 秒", SpeechPauseSettings.secondsLabel(2_000));
    }
}
