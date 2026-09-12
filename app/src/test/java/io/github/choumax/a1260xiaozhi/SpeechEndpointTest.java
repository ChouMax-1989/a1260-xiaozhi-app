package io.github.choumax.a1260xiaozhi;
import org.junit.Test;
import static org.junit.Assert.*;
public class SpeechEndpointTest {
 @Test public void speechEndsAfterPause(){SpeechEndpoint e=new SpeechEndpoint();short[] a=new short[320];for(int i=0;i<15;i++)e.accept(a);java.util.Arrays.fill(a,(short)1000);for(int i=0;i<50;i++)assertFalse(e.accept(a));a=new short[320];for(int i=0;i<49;i++)assertFalse(e.accept(a));assertTrue(e.accept(a));}
 @Test public void silenceDoesNotWaitForever(){SpeechEndpoint e=new SpeechEndpoint();short[] a=new short[320];for(int i=0;i<499;i++)assertFalse(e.accept(a));assertTrue(e.accept(a));}
 @Test public void silentWakeReturnsReadyInsteadOfWaitingForServer(){
  SpeechEndpoint endpoint=new SpeechEndpoint(); short[] silent=new short[320];
  for(int i=0;i<500;i++)endpoint.accept(silent);
  assertEquals(SessionState.READY,SessionReducer.next(SessionState.LISTENING,SessionReducer.captureCompletedEvent(true,endpoint.hasSpeech())));
  assertEquals(SessionState.WAITING_REPLY,SessionReducer.next(SessionState.LISTENING,SessionReducer.captureCompletedEvent(false,endpoint.hasSpeech())));
 }
 @Test public void spokenWakeStillSubmitsAfterOneSecondPause(){
  SpeechEndpoint endpoint=new SpeechEndpoint(); short[] frame=new short[320];
  for(int i=0;i<15;i++)endpoint.accept(frame);
  java.util.Arrays.fill(frame,(short)1000);for(int i=0;i<30;i++)endpoint.accept(frame);
  java.util.Arrays.fill(frame,(short)0);for(int i=0;i<49;i++)assertFalse(endpoint.accept(frame));assertTrue(endpoint.accept(frame));
  assertEquals(SessionState.WAITING_REPLY,SessionReducer.next(SessionState.LISTENING,SessionReducer.captureCompletedEvent(true,endpoint.hasSpeech())));
 }
 @Test public void speechImmediatelyAfterCueMustNotCalibrateItselfAsNoise(){
  SpeechEndpoint endpoint=new SpeechEndpoint();short[] frame=new short[320];java.util.Arrays.fill(frame,(short)1000);
  for(int i=0;i<40;i++)assertFalse(endpoint.accept(frame));
  java.util.Arrays.fill(frame,(short)0);for(int i=0;i<49;i++)assertFalse(endpoint.accept(frame));
  assertTrue(endpoint.accept(frame));assertTrue(endpoint.hasSpeech());
 }
}


