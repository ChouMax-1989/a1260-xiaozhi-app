package io.github.choumax.a1260xiaozhi.wake;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class EnergyVadTest {
    private static short[] frame(int amplitude) { short[] pcm = new short[320]; Arrays.fill(pcm, (short) amplitude); return pcm; }
    @Test public void silenceAndClicksDoNotEmitUtterances() {
        EnergyVad vad = new EnergyVad();
        for (int i = 0; i < 100; i++) assertNull(vad.accept(frame(0)));
        assertNull(vad.accept(frame(8000)));
        for (int i = 0; i < 30; i++) assertNull(vad.accept(frame(0)));
    }
    @Test public void emitsSpeechOnceAfterHangover() {
        EnergyVad vad = new EnergyVad();
        for (int i = 0; i < 20; i++) vad.accept(frame(50));
        for (int i = 0; i < 40; i++) assertNull(vad.accept(frame(5000)));
        int found = 0;
        for (int i = 0; i < 30; i++) {
            short[] utterance = vad.accept(frame(0));
            if (utterance != null) { found++; assertTrue(utterance.length >= 40 * 320); assertTrue(utterance.length <= WakeFeatures.MAX_SAMPLES); }
        }
        assertEquals(1, found);
    }
    @Test public void longNoiseIsDiscardedAndVadRecovers() {
        EnergyVad vad = new EnergyVad();
        for (int i = 0; i < 20; i++) vad.accept(frame(0));
        for (int i = 0; i < 300; i++) assertNull(vad.accept(frame(7000)));
        for (int i = 0; i < 30; i++) assertNull(vad.accept(frame(0)));
        for (int i = 0; i < 30; i++) assertNull(vad.accept(frame(7000)));
        boolean found = false;
        for (int i = 0; i < 30; i++) found |= vad.accept(frame(0)) != null;
        assertTrue(found);
    }
    @Test public void speechAtDetectorStartupIsNotLearnedAsNoise() {
        EnergyVad vad = new EnergyVad();
        for (int i = 0; i < 40; i++) assertNull(vad.accept(frame(1000)));
        int found = 0;
        for (int i = 0; i < 30; i++) if (vad.accept(frame(0)) != null) found++;
        assertEquals(1, found);
    }
}
