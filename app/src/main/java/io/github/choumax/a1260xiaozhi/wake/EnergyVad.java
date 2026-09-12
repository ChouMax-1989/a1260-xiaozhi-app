package io.github.choumax.a1260xiaozhi.wake;

import java.util.Arrays;

/** 20 ms energy VAD with 200 ms pre-roll, 400 ms hangover and a hard three-second cap. */
public final class EnergyVad {
    public static final int FRAME_SAMPLES = 320;
    private static final int PRE_FRAMES = 10, END_FRAMES = 20, MIN_VOICE_FRAMES = 10;
    private final short[] preRoll = new short[PRE_FRAMES * FRAME_SAMPLES];
    private final short[] segment = new short[WakeFeatures.MAX_SAMPLES];
    private int preCount, preCursor, voicedRun, voicedTotal, quietRun, used, calibrationFrames;
    private double noiseRms = 120, lastRms;
    public double noiseRms() { return noiseRms; }
    public double lastRms() { return lastRms; }
    public boolean hasActiveSegment() { return active; }
    private boolean active, overlong;

    /** Returns one complete segment, or null. Input must be exactly one full frame. */
    public short[] accept(short[] frame) {
        if (frame == null || frame.length != FRAME_SAMPLES) throw new IllegalArgumentException("Expected 320 samples");
        double rms = lastRms = WakeFeatures.rms(frame, frame.length);
        boolean voice = rms >= Math.max(250, noiseRms * (active ? 2.0 : 3.0));
        // Listening may resume just as a wake phrase begins. Quiet frames can
        // calibrate the noise floor, but speech must enter VAD immediately.
        if (calibrationFrames < 15) {
            calibrationFrames++;
            if (!active && voicedRun == 0 && !voice) {
                noiseRms = Math.min(1200, 0.8 * noiseRms + 0.2 * rms);
                remember(frame); return null;
            }
        }
        if (overlong) {
            quietRun = voice ? 0 : quietRun + 1;
            if (quietRun >= END_FRAMES) { resetSegment(); overlong = false; }
            return null;
        }
        if (!active) {
            remember(frame);
            voicedRun = voice ? voicedRun + 1 : 0;
            if (!voice) noiseRms = Math.min(1200, 0.98 * noiseRms + 0.02 * rms);
            if (voicedRun < 3) return null;
            active = true; voicedTotal = voicedRun; quietRun = 0; used = 0;
            for (int i = 0; i < preCount; i++) {
                int at = (preCursor - preCount + i + PRE_FRAMES) % PRE_FRAMES;
                System.arraycopy(preRoll, at * FRAME_SAMPLES, segment, used, FRAME_SAMPLES); used += FRAME_SAMPLES;
            }
            return null;
        }
        if (used + FRAME_SAMPLES > segment.length) { resetSegment(); overlong = true; return null; }
        System.arraycopy(frame, 0, segment, used, FRAME_SAMPLES); used += FRAME_SAMPLES;
        if (voice) { voicedTotal++; quietRun = 0; } else quietRun++;
        if (quietRun < END_FRAMES) return null;
        // Retain 100 ms tail but remove most VAD hangover, identically for enrollment and matching.
        int length = used - (END_FRAMES - 5) * FRAME_SAMPLES;
        short[] complete = voicedTotal >= MIN_VOICE_FRAMES && length >= WakeFeatures.MIN_SAMPLES
                ? Arrays.copyOf(segment, length) : null;
        resetSegment(); return complete;
    }

    private void remember(short[] frame) {
        System.arraycopy(frame, 0, preRoll, preCursor * FRAME_SAMPLES, FRAME_SAMPLES);
        preCursor = (preCursor + 1) % PRE_FRAMES; preCount = Math.min(PRE_FRAMES, preCount + 1);
    }
    private void resetSegment() { active = false; used = quietRun = voicedRun = voicedTotal = preCount = preCursor = 0; }
}
