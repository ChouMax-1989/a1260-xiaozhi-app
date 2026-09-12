package io.github.choumax.a1260xiaozhi;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.Request;
import okhttp3.WebSocket;
import okio.ByteString;

/** Tests real controller completion and codec drain with synthetic PCM and a sink socket. */
public final class FollowUpDeviceInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(true, false, false, 1, 0);
            check(true, true, false, 0, 1);
            check(false, false, false, 0, 0);
            check(true, false, true, 0, 0);
            result.putString("result", "PASS 4 controller cases: silent follow-up ends once; spoken follow-up submits; initial silence has no end cue; cancelled completion has no cue. Synthetic PCM only; no network.");
            finish(-1, result);
        } catch (Throwable e) { result.putString("result", "FAIL " + e.toString()); finish(1, result); }
    }
    private void check(boolean followUp, boolean speech, boolean cancel, int expectedEnd, int expectedSubmit) throws Exception {
        AtomicInteger ended = new AtomicInteger(), submitted = new AtomicInteger();
        AtomicReference<SessionState> observed = new AtomicReference<>();
        SessionController controller = new SessionController(getTargetContext(), new SessionController.Listener() {
            public void onState(SessionState state, String detail) { observed.set(state); }
            public void onTranscript(String label, String text) { throw new AssertionError("Unexpected transcript"); }
            public void onConversationEnded() { ended.incrementAndGet(); }
            public void onVoiceSubmitted() { submitted.incrementAndGet(); }
        });
        try {
            Handler control = (Handler)get(controller, "control");
            CountDownLatch prepared = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            control.post(() -> {
                try {
                    set(controller, "state", SessionState.LISTENING);
                    set(controller, "socket", new Sink()); set(controller, "sessionId", "synthetic-test");
                    Class<?> type = Class.forName(SessionController.class.getName()+"$InputRun");
                    Constructor<?> constructor = type.getDeclaredConstructor(SessionController.class); constructor.setAccessible(true);
                    Object run = constructor.newInstance(controller);
                    set(run, "wakeTriggered", true); set(run, "followUp", followUp);
                    SpeechEndpoint endpoint = new SpeechEndpoint(followUp ? 500 : 10000);
                    short[] frame = new short[320];
                    if (speech) { java.util.Arrays.fill(frame,(short)1000); for(int i=0;i<20;i++)endpoint.accept(frame); java.util.Arrays.fill(frame,(short)0); }
                    for(int i=0;i<(speech?50:(followUp?25:500));i++)endpoint.accept(frame);
                    set(run, "endpoint", endpoint);
                    PlatformOpusEncoder encoder = (PlatformOpusEncoder)get(run,"encoder"); encoder.start(); encoder.encodeAvailable(frame);
                    ((AtomicBoolean)get(run,"graceful")).set(true);
                    set(controller,"input",run);
                    if(cancel) controller.stopAll();
                    Method completion = SessionController.class.getDeclaredMethod("completeCapture",type); completion.setAccessible(true); completion.invoke(controller,run);
                } catch(Throwable e) { error.set(e); }
                finally { prepared.countDown(); }
            });
            if(!prepared.await(10,TimeUnit.SECONDS))throw new AssertionError("Controller preparation timeout");
            if(error.get()!=null)throw new AssertionError(error.get());
            CountDownLatch settled = new CountDownLatch(1); control.postDelayed(settled::countDown,350);
            if(!settled.await(3,TimeUnit.SECONDS))throw new AssertionError("Controller settle timeout");
            waitForIdleSync();
            if(ended.get()!=expectedEnd || submitted.get()!=expectedSubmit)throw new AssertionError("cue="+ended+" submitted="+submitted);
            SessionState expected = speech && !cancel ? SessionState.WAITING_REPLY : SessionState.READY;
            if(observed.get()!=expected)throw new AssertionError("Wrong final state "+observed.get());
        } finally { controller.close(); }
    }
    private static Object get(Object o,String name)throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o); }
    private static void set(Object o,String name,Object value)throws Exception { Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value); }
    private static final class Sink implements WebSocket {
        public Request request(){return new Request.Builder().url("https://localhost/").build();}
        public long queueSize(){return 0;}
        public boolean send(String text){return true;}
        public boolean send(ByteString bytes){return true;}
        public boolean close(int code,String reason){return true;}
        public void cancel(){}
    }
}
