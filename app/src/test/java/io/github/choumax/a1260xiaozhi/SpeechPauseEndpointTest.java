package io.github.choumax.a1260xiaozhi;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpeechPauseEndpointTest {
    private short[] speech() { short[] pcm = new short[320]; java.util.Arrays.fill(pcm,(short)1000); return pcm; }
    @Test public void sixHundredMsPauseSubmitsFourHundredMsEarlier() {
        SpeechEndpoint fast = new SpeechEndpoint(10000,600), old = new SpeechEndpoint(10000,1000);
        short[] voice = speech(), quiet = new short[320];
        for(int i=0;i<20;i++){assertFalse(fast.accept(voice));assertFalse(old.accept(voice));}
        for(int i=0;i<29;i++){assertFalse(fast.accept(quiet));assertFalse(old.accept(quiet));}
        assertTrue(fast.accept(quiet));assertFalse(old.accept(quiet));
        for(int i=0;i<19;i++)assertFalse(old.accept(quiet));
        assertTrue(old.accept(quiet));
    }
    @Test public void speechBeforeDeadlineResetsPause() {
        SpeechEndpoint endpoint = new SpeechEndpoint(10000,600);
        short[] voice=speech(), quiet=new short[320];
        for(int i=0;i<20;i++)endpoint.accept(voice);
        for(int i=0;i<29;i++)assertFalse(endpoint.accept(quiet));
        assertFalse(endpoint.accept(voice));
        for(int i=0;i<29;i++)assertFalse(endpoint.accept(quiet));
        assertTrue(endpoint.accept(quiet));
    }
    @Test public void followUpWaitAndSpeechPauseAreIndependent() {
        SpeechEndpoint quietOnly = new SpeechEndpoint(2000,600), spoken = new SpeechEndpoint(2000,600);
        short[] voice=speech(), quiet=new short[320];
        for(int i=0;i<99;i++)assertFalse(quietOnly.accept(quiet));
        assertTrue(quietOnly.accept(quiet));assertFalse(quietOnly.hasSpeech());
        for(int i=0;i<20;i++)spoken.accept(voice);
        for(int i=0;i<29;i++)assertFalse(spoken.accept(quiet));
        assertTrue(spoken.accept(quiet));assertTrue(spoken.hasSpeech());
    }
    @Test public void longestSettingDoesNotTruncateAtDefault() {
        SpeechEndpoint endpoint = new SpeechEndpoint(10000,2000);
        short[] voice=speech(), quiet=new short[320];
        for(int i=0;i<20;i++)endpoint.accept(voice);
        for(int i=0;i<99;i++)assertFalse(endpoint.accept(quiet));
        assertTrue(endpoint.accept(quiet));
    }
}
