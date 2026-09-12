package io.github.choumax.a1260xiaozhi.wake;

import static org.junit.Assert.*;
import org.junit.Test;

public final class WakeFeaturesTest {
    private static short[] tone(int frequency, int amplitude, int samples) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) pcm[i] = (short) (amplitude * Math.sin(2 * Math.PI * frequency * i / 16000));
        return pcm;
    }
    @Test public void featuresAreFiniteBoundedAndDeterministic() {
        WakeFeatures extractor = new WakeFeatures();
        float[][] a = extractor.extract(tone(440, 8000, WakeFeatures.MAX_SAMPLES));
        float[][] b = extractor.extract(tone(440, 8000, WakeFeatures.MAX_SAMPLES));
        assertTrue(a.length <= WakeFeatures.MAX_FRAMES);
        for (float[] frame : a) { assertEquals(WakeFeatures.DIMENSIONS, frame.length); for (float value : frame) assertTrue(Float.isFinite(value)); }
        assertEquals(0, BoundedDtw.distance(a, b), 1e-8);
    }
    @Test public void volumeChangeIsCloserThanDifferentSpectrum() {
        WakeFeatures extractor = new WakeFeatures();
        float[][] reference = extractor.extract(tone(440, 10000, 16000));
        double volume = BoundedDtw.distance(reference, extractor.extract(tone(440, 5000, 16000)));
        double different = BoundedDtw.distance(reference, extractor.extract(tone(1700, 10000, 16000)));
        assertTrue("Different spectrum should be distinguishable", volume < different);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsSilence() { new WakeFeatures().extract(new short[16000]); }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnboundedAudio() { new WakeFeatures().extract(new short[WakeFeatures.MAX_SAMPLES + 1]); }
}
