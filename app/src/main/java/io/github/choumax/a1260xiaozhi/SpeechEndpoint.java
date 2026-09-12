package io.github.choumax.a1260xiaozhi;
/** Wake-triggered one-turn endpoint; never used for held PTT. */
final class SpeechEndpoint {
    private int frames, voiced, quiet;
    private boolean heard;
    private double noise=120;
    private final int initialQuietFrames;
    private final int speechQuietFrames;
    SpeechEndpoint() { this(10_000); }
    SpeechEndpoint(int initialWaitMs) { this(initialWaitMs, 1000); }
    SpeechEndpoint(int initialWaitMs, int speechPauseMs) {
        initialQuietFrames = Math.max(1, (initialWaitMs + 19) / 20);
        speechQuietFrames = Math.max(1, (speechPauseMs + 19) / 20);
    }
    boolean hasSpeech() { return heard; }
    int quietMillis() { return quiet * 20; }
    boolean accept(short[] pcm) {
        double sum=0; for(short x:pcm) sum+=(double)x*x;
        double rms=Math.sqrt(sum/pcm.length);
        frames++;
        boolean speech=rms>Math.max(300,noise*2.5);
        // The user may speak immediately after the cue: do not learn their first
        // syllables as background noise or discard them during a blind warm-up.
        if(frames<=15 && !speech)noise=Math.min(1800,0.7*noise+0.3*rms);
        if(speech){ voiced++;quiet=0;if(voiced>=5)heard=true; }
        else { quiet++; }
        // Give speech beginning at the deadline enough frames to be confirmed.
        return (heard && quiet>=speechQuietFrames) || (!heard && frames>=initialQuietFrames && !speech) || frames>=1500;
    }
}
