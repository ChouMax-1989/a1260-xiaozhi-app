package io.github.choumax.a1260xiaozhi;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import io.github.choumax.a1260xiaozhi.features.FeatureRuntime;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Control thread owns session state; each audio run owns its own worker resources and generation. */
public final class SessionController implements AutoCloseable {
    public interface Listener {
        default void onVoiceSubmitted() { }
        default void onConversationEnded() { }
        void onState(SessionState state, String detail);
        void onTranscript(String label, String text);
        default void onDiagnostic(ConnectionDiagnostic diagnostic) { }
    }
    private static final long MAX_SOCKET_QUEUE_BYTES = 128 * 1024;
    private final HandlerThread thread = new HandlerThread("xiaozhi-control");
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS).build();
    private final Listener listener;
    private final Context context;
    private final FeatureRuntime features;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Handler control;
    private SessionState state = SessionState.DISCONNECTED;
    private WebSocket socket;
    private String sessionId = "";
    private int downSampleRate = 16000;
    private long requestStarted; private boolean measuredText, measuredAudio;
    private boolean manualTurn;
    private boolean wakeConversation;
    private volatile long turnId;
    private boolean continuousHalfDuplex, fullDuplex, voiceLoopArmed, textTurn, acceptReplies;
    private InputRun input;
    private int retiringInputs;
    private OpusPlayback playback;
    private long playbackId;
    private Runnable helloTimeout, replyTimeout, listenTimeout;
    private volatile ConnectionDiagnostic lastDiagnostic;
    private volatile String recentErrorSummary = "尚无已分类连接错误";

    public ConnectionDiagnostic getLastDiagnostic() { return lastDiagnostic; }
    /** Safe for UI/support: fixed summaries and numeric codes only; last actual error survives success. */
    public String getRecentErrorSummary() { return recentErrorSummary; }

    private final class InputRun {
        final long g = generation.get();
        final PlatformOpusEncoder encoder = new PlatformOpusEncoder();
        final AudioCapture capture = new AudioCapture(context);
        final AtomicBoolean graceful = new AtomicBoolean(), aborted = new AtomicBoolean();
        final AtomicInteger pendingPackets = new AtomicInteger();
        boolean wakeTriggered, followUp;
        SpeechEndpoint endpoint = new SpeechEndpoint();
        int frames, peak, sent;
        boolean retired;
    }
    public SessionController(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.features = FeatureRuntime.get(this.context); this.listener = listener;
        thread.start(); control = new Handler(thread.getLooper());
    }
    private void post(Runnable action) { if (!closed.get()) control.post(() -> { if (!closed.get()) action.run(); }); }
    public void connect(ConnectionConfig config) { post(() -> connectOnControl(config)); }
    public void disconnect() { post(() -> terminateConnection(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.USER_DISCONNECTED, state), false)); }
    public void startTalking() { post(this::startTalkingOnControl); }
    public void startWakeListening() { post(() -> startTalkingOnControl(true, true)); }
    public void startPushToTalk() { post(() -> startTalkingOnControl(true)); }
    public void stopTalking() { post(this::stopTalkingOnControl); }
    /** Arbitrary detect/text is a server extension: successful queueing does not prove server support. */
    public void sendText(String text) { post(() -> sendTextOnControl(text)); }
    public void stopAll() {
        post(() -> {
            if (state == SessionState.CONNECTING || state == SessionState.HELLO_WAIT) { terminateConnection(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.USER_DISCONNECTED, state), false); return; }
            turnId++; wakeConversation = false;
            voiceLoopArmed = false; textTurn = false; acceptReplies = false; cancelTurnTimeouts();
            if (input != null) sendJson(ProtocolJson.listen(sessionId, "stop"));
            sendAbort(); stopAudio(); transition(SessionReducer.Event.STOP, "Stopped");
        });
    }

    private void connectOnControl(ConnectionConfig config) {
        String error = config == null ? "Connection settings unavailable." : config.validationError();
        if (error != null) { fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.CONFIG_INVALID, state)); return; }
        closeOnControl("Replacing connection");
        continuousHalfDuplex = config.continuousHalfDuplex; fullDuplex = config.fullDuplex;
        long g = generation.get();
        transition(SessionReducer.Event.CONNECT, "Connecting");
        try {
            Request.Builder request = new Request.Builder().url(config.endpoint)
                    .header("Protocol-Version", Integer.toString(config.protocolVersion))
                    .header("Device-Id", config.deviceId).header("Client-Id", config.clientId);
            if (!config.token.isEmpty()) request.header("Authorization", config.token.startsWith("Bearer ") ? config.token : "Bearer " + config.token);
            socket = client.newWebSocket(request.build(), new SocketEvents(g));
        } catch (RuntimeException e) { fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.CONFIG_INVALID, state)); }
    }

    private final class SocketEvents extends WebSocketListener {
        private final long g;
        private final ConnectionEventGate terminalGate;
        private final AtomicInteger pendingCount = new AtomicInteger(), pendingBytes = new AtomicInteger();
        private final AtomicBoolean overflowed = new AtomicBoolean();
        SocketEvents(long g) { this.g = g; terminalGate = new ConnectionEventGate(g); }
        private boolean current() { return !closed.get() && g == generation.get(); }
        private void overflow() {
            if (overflowed.compareAndSet(false, true)) control.post(() -> { if (current()) fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.RECEIVE_OVERFLOW, state)); });
        }
        private boolean reserve(int bytes) {
            if (!current() || !terminalGate.isOpen(generation.get()) || overflowed.get()) return false;
            int count = pendingCount.incrementAndGet(), total = pendingBytes.addAndGet(bytes);
            if (count > 128 || total > 256 * 1024) { pendingCount.decrementAndGet(); pendingBytes.addAndGet(-bytes); overflow(); return false; }
            return true;
        }
        private void release(int bytes) { pendingCount.decrementAndGet(); pendingBytes.addAndGet(-bytes); }
        @Override public void onOpen(WebSocket ws, Response response) {
            control.post(() -> {
                if (!current()) { ws.cancel(); return; }
                if (!terminalGate.isOpen(generation.get())) return;
                transition(SessionReducer.Event.SOCKET_OPEN, "Sending hello");
                if (!sendJson(ProtocolJson.hello())) { fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.SEND_FAILURE, state)); return; }
                helloTimeout = () -> { if (current() && state == SessionState.HELLO_WAIT) fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.HELLO_TIMEOUT, state)); };
                control.postDelayed(helloTimeout, 10_000);
            });
        }
        @Override public void onMessage(WebSocket ws, String text) {
            if (text.length() > 32768) { overflow(); return; }
            int bytes = text.length() * 2;
            if (!reserve(bytes)) return;
            if (!control.post(() -> { try { if (current()) handleText(text); } finally { release(bytes); } })) release(bytes);
        }
        @Override public void onMessage(WebSocket ws, ByteString bytes) {
            int size = bytes.size();
            if (size > 4000) { overflow(); return; }
            if (!reserve(size)) return;
            byte[] packet = bytes.toByteArray();
            if (!control.post(() -> { try { if (current()) handleBinary(packet); } finally { release(size); } })) release(size);
        }
        @Override public void onClosing(WebSocket ws, int code, String reason) {
            if (!terminalGate.terminate(generation.get())) return;
            // Reply with no peer-provided close reason. Never immediately cancel the close handshake.
            try { ws.close(code == 1005 ? 1000 : code, null); } catch (IllegalArgumentException ignored) { ws.cancel(); }
            control.post(() -> { if (current()) terminateConnection(ConnectionDiagnostic.remoteClose(code, state), true); });
        }
        @Override public void onClosed(WebSocket ws, int code, String reason) {
            if (!terminalGate.terminate(generation.get())) return;
            control.post(() -> { if (current()) terminateConnection(ConnectionDiagnostic.remoteClose(code, state), true); });
        }
        @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
            if (!terminalGate.terminate(generation.get())) return;
            int http = response == null ? 0 : response.code();
            control.post(() -> {
                if (!current()) return;
                fail(ConnectionDiagnostic.failure(t, http, state));
            });
        }
    }

    private void handleText(String text) {
        final ProtocolJson.ServerMessage message;
        try { message = ProtocolJson.parse(text); }
        catch (Exception ignored) { visibleError("Ignored invalid server JSON."); return; }
        if ("hello".equals(message.type)) {
            if (state != SessionState.HELLO_WAIT || !"websocket".equals(message.raw.optString("transport", "")) || message.sessionId.isEmpty()) {
                fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.PROTOCOL_FAILURE, state)); return;
            }
            if (!validateHelloAudio(message)) return;
            sessionId = message.sessionId; if (helloTimeout != null) control.removeCallbacks(helloTimeout);
            publishDiagnostic(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.CONNECTED, state));
            transition(SessionReducer.Event.HELLO_OK, "Ready"); return;
        }
        if (sessionId.isEmpty()) return;
        if (!message.sessionId.isEmpty() && !sessionId.equals(message.sessionId)) return;
        if ("mcp".equals(message.type)) {
            try {
                org.json.JSONObject reply = features.handleMcpEnvelope(message.raw, sessionId);
                if (reply != null && !sendJson(reply.toString())) fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.SEND_FAILURE, state));
            } catch (Exception ignored) { visibleError("MCP message could not be handled."); }
            return;
        }
        if ("alert".equals(message.type)) { visibleError(message.text.isEmpty() ? "Server alert" : message.text); return; }
        if (!acceptReplies) return;
        if ("stt".equals(message.type)) {
            if (!textTurn && !message.text.isEmpty()) transcript("You", message.text);
        } else if ("tts".equals(message.type)) {
            if ("start".equals(message.state)) startPlayback();
            else if ("stop".equals(message.state)) finishPlayback();
            if ("sentence_start".equals(message.state) && !message.text.isEmpty()) {
                boolean binding = message.text.contains("控制面板添加设备") || (message.text.contains("验证码") && message.text.matches("(?s).*\\d{6}.*"));
                transcript(binding ? "Binding" : "Assistant", message.text);
            }
        } else if ("llm".equals(message.type) && !message.text.isEmpty()) transcript("Emotion", message.text);
    }
    private boolean validateHelloAudio(ProtocolJson.ServerMessage message) {
        org.json.JSONObject audio = message.raw.optJSONObject("audio_params");
        if (audio == null) { downSampleRate = 16000; return true; }
        if (!"opus".equals(audio.optString("format", "opus")) || audio.optInt("channels", 1) != 1) { fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.PROTOCOL_FAILURE, state)); return false; }
        int rate = audio.optInt("sample_rate", 16000);
        if (rate != 16000 && rate != 24000 && rate != 48000) { fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.PROTOCOL_FAILURE, state)); return false; }
        downSampleRate = rate; return true;
    }
    private void handleBinary(byte[] packet) {
        if (!acceptReplies || playback == null || (state != SessionState.SPEAKING && state != SessionState.DRAINING)) return;
        if(requestStarted>0 && !measuredAudio){measuredAudio=true;android.util.Log.i("A1260Latency","first_audio_ms="+(android.os.SystemClock.elapsedRealtime()-requestStarted));}
        try { playback.offer(packet); } catch (IOException e) { fail(e.getMessage()); }
    }

    private void sendTextOnControl(String text) {
        if (text == null || text.trim().isEmpty() || text.length() > ProtocolJson.MAX_TEXT_CHARS) { visibleError("Enter 1 to 4000 characters."); return; }
        if (state != SessionState.READY || socket == null || input != null) { visibleError("Wait until the connection is ready before sending text."); return; }
        String request = text.trim();
        turnId++; wakeConversation = false;
        requestStarted=android.os.SystemClock.elapsedRealtime(); measuredText=false; measuredAudio=false;
        voiceLoopArmed = false; textTurn = true; acceptReplies = true;
        if (!sendJson(ProtocolJson.text(sessionId, request))) { fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.SEND_FAILURE, state)); return; }
        transcript("You", request); // Exactly once locally; any server STT echo is suppressed for this turn.
        transition(SessionReducer.Event.SEND_TEXT, "Text sent; this server's text extension still requires verification.");
        scheduleReplyTimeout();
    }
    private void startTalkingOnControl() { startTalkingOnControl(false); }
    private void startTalkingOnControl(boolean manual) { startTalkingOnControl(manual, false); }
    private void startTalkingOnControl(boolean manual, boolean wake) {
        startTalkingOnControl(manual, wake, false);
    }
    private void startTalkingOnControl(boolean manual, boolean wake, boolean followUp) {
        if (input != null) return;
        if (retiringInputs != 0) { visibleError("Microphone is finishing its previous capture; try again shortly."); return; }
        if (state != SessionState.READY && !(fullDuplex && state == SessionState.SPEAKING)) { visibleError("Wait until the connection is ready."); return; }
        requestStarted=0;
        manualTurn = manual;
        turnId++; wakeConversation = wake;
        InputRun run = new InputRun(); run.wakeTriggered = wake; run.followUp = followUp;
        SettingsStore voiceSettings = new SettingsStore(context);
        run.endpoint = new SpeechEndpoint(followUp ? voiceSettings.followUpWaitMs() : 10_000,
                voiceSettings.speechPauseMs());
        if (followUp) {
            int waitMs = voiceSettings.followUpWaitMs();
            android.util.Log.i("A1260FollowUp", "capture_start wait_ms=" + waitMs);
        }
        boolean captureStarted = false;
        SpeechEndpoint endpoint = run.endpoint;
        AtomicBoolean endpointSent = new AtomicBoolean();
        try {
            run.encoder.start();
            if (!sendJson(ProtocolJson.listen(sessionId, "start", ProtocolJson.listeningMode(!manual && fullDuplex, !manual && continuousHalfDuplex)))) {
                run.encoder.close();
                fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.SEND_FAILURE, state)); return;
            }
            input = run; voiceLoopArmed = !manual && continuousHalfDuplex && !fullDuplex; textTurn = false; acceptReplies = true;
            run.capture.start(frame -> {
                run.frames++; for (short sample : frame) run.peak = Math.max(run.peak, Math.abs((int)sample));
                if (!run.aborted.get()) enqueuePackets(run, run.encoder.encodeAvailable(frame));
                if (wake && endpoint.accept(frame) && endpointSent.compareAndSet(false,true)) control.post(() -> { if(currentInput(run)) stopTalkingOnControl(); });
            }, error -> control.post(() -> { if (currentInput(run)) fail(error); }), () -> completeCapture(run));
            captureStarted = true;
            if (state == SessionState.READY) transition(SessionReducer.Event.START_LISTEN, "Listening");
            else postState(state, "Speaking and listening (echo cancellation is not guaranteed)");
            scheduleListenTimeout(run);
        } catch (Exception e) {
            // start() failed before a capture worker acquired the codec.
            if (captureStarted) stopCapture();
            else { run.aborted.set(true); run.capture.close(); run.encoder.close(); if (input == run) input = null; }
            sendAbort(); voiceLoopArmed = false; acceptReplies = false; visibleError("Voice input unavailable. Check microphone permission and platform codec.");
        }
    }
    private boolean currentInput(InputRun run) { return !closed.get() && run.g == generation.get() && input == run && !run.aborted.get(); }
    private void enqueuePackets(InputRun run, List<byte[]> packets) throws IOException {
        for (byte[] packet : packets) {
            if (run.aborted.get() || run.g != generation.get() || closed.get()) return;
            if (packet.length > 4000) throw new IOException("Outgoing audio packet exceeded its budget.");
            if (run.pendingPackets.incrementAndGet() > 64) { run.pendingPackets.decrementAndGet(); throw new IOException("Outgoing audio queue exceeded its budget."); }
            if (!control.post(() -> {
                try {
                    if (currentInput(run) && (socket == null || socket.queueSize() + packet.length > MAX_SOCKET_QUEUE_BYTES || !socket.send(ByteString.of(packet))))
                        fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.SEND_FAILURE, state));
                run.sent++;
                } finally { run.pendingPackets.decrementAndGet(); }
            })) run.pendingPackets.decrementAndGet();
        }
    }
    private void completeCapture(InputRun run) {
        String error = null;
        try {
            if (run.graceful.get() && !run.aborted.get()) enqueuePackets(run, run.encoder.finish());
        } catch (Exception e) { if (!run.aborted.get()) error = "Could not drain the microphone encoder."; }
        finally { run.encoder.close(); }
        String failure = error;
        control.post(() -> {
            if (run.retired) { retiringInputs--; run.retired = false; }
            if (!currentInput(run)) return;
            input = null;
            if (failure != null) { fail(failure); return; }
            if (!run.graceful.get()) { fail("Microphone stopped unexpectedly."); return; }
            android.util.Log.i("A1260AudioStats", "capture frames=" + run.frames + " peak=" + run.peak + " packets=" + run.sent);
            if (!run.wakeTriggered || run.endpoint.hasSpeech()) {
                requestStarted=android.os.SystemClock.elapsedRealtime(); measuredText=false; measuredAudio=false;
                android.util.Log.i("A1260Latency", "voice_submitted quiet_ms=" + run.endpoint.quietMillis());
            }
            if (!sendJson(ProtocolJson.listen(sessionId, "stop"))) { fail(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.SEND_FAILURE, state)); return; }
            SessionReducer.Event completion = SessionReducer.captureCompletedEvent(run.wakeTriggered, run.endpoint.hasSpeech());
            if (completion == SessionReducer.Event.STOP) {
                // A wake with no following utterance must not wait a minute for a reply to silence.
                voiceLoopArmed = false; acceptReplies = false; sendAbort();
                wakeConversation = false;
                transition(completion, run.followUp ? "Conversation ended; ready for wake" : "No speech after wake; ready again");
                if (run.followUp) {
                    android.util.Log.i("A1260FollowUp", "ended_no_speech frames=" + run.frames);
                    long g = generation.get(), endedTurn = turnId;
                    main.post(() -> { if (!closed.get() && g == generation.get() && endedTurn == turnId) listener.onConversationEnded(); });
                }
                return;
            }
            if(run.wakeTriggered) { long g=generation.get(); main.post(() -> { if(!closed.get() && g==generation.get())listener.onVoiceSubmitted(); }); }
            if (state == SessionState.LISTENING) { transition(completion, "Waiting for reply"); scheduleReplyTimeout(); }
        });
    }
    private void stopTalkingOnControl() {
        InputRun run = input;
        if (run == null || run.graceful.get()) return;
        if (listenTimeout != null) control.removeCallbacks(listenTimeout);
        run.graceful.set(true); run.capture.close();
        postState(state, "Finishing microphone input");
    }
    private void startPlayback() {
        if (state == SessionState.SPEAKING || state == SessionState.DRAINING) return; // Duplicate start cannot truncate current audio.
        if (state != SessionState.LISTENING && state != SessionState.WAITING_REPLY && state != SessionState.READY) return;
        cancelTurnTimeouts();
        if (manualTurn || !fullDuplex) stopCapture();
        OpusPlayback owned = new OpusPlayback(downSampleRate); long g = generation.get(), id = ++playbackId;
        playback = owned;
        try {
            owned.start(error -> control.post(() -> { if (g == generation.get() && id == playbackId && playback == owned) fail("Playback unavailable: " + error); }));
            transition(SessionReducer.Event.TTS_START, fullDuplex && input != null ? "Speaking and listening" : "Speaking");
            replyTimeout = () -> { if (g == generation.get() && id == playbackId && state == SessionState.SPEAKING) fail("Server did not finish its TTS stream."); };
            control.postDelayed(replyTimeout, 120_000);
        } catch (IOException e) { fail("Opus playback unavailable."); }
    }
    private void finishPlayback() {
        OpusPlayback owned = playback;
        if (owned == null || state != SessionState.SPEAKING) return;
        if (replyTimeout != null) control.removeCallbacks(replyTimeout);
        transition(SessionReducer.Event.TTS_STOP, "Finishing reply");
        long g = generation.get(), id = playbackId;
        owned.finish(() -> control.post(() -> {
            if (closed.get() || g != generation.get() || id != playbackId || playback != owned || state != SessionState.DRAINING) return;
            playback = null;
            boolean followUp = wakeConversation && !textTurn && !fullDuplex;
            if (followUp) android.util.Log.i("A1260FollowUp", "reply_audio_drained");
            // Keep the wake detector off while acquiring the follow-up microphone.
            // READY is internal here; only publish LISTENING once capture starts.
            if (followUp) state = SessionReducer.next(state, SessionReducer.Event.DRAINED);
            else transition(SessionReducer.Event.DRAINED, "Ready");
            if (followUp && input == null) {
                startFollowUpWhenReleased(g, id, turnId);
                return;
            }
            if (input != null) { transition(SessionReducer.Event.START_LISTEN, "Listening"); scheduleListenTimeout(input); }
            else if (SessionReducer.resumeMicrophoneAfterDrain(voiceLoopArmed, textTurn, false)) {
                // The prior capture completion is normally already queued, but do not race its release.
                if (retiringInputs == 0) startTalkingOnControl();
                else control.postDelayed(() -> { if (g == generation.get() && state == SessionState.READY && voiceLoopArmed && !textTurn) startTalkingOnControl(); }, 100);
            } else { acceptReplies = false; textTurn = false; }
        }));
    }
    private void startFollowUpWhenReleased(long g, long id, long expectedTurn) {
        if (closed.get() || g != generation.get() || id != playbackId || expectedTurn != turnId
                || state != SessionState.READY || !wakeConversation) return;
        if (retiringInputs != 0) { control.postDelayed(() -> startFollowUpWhenReleased(g, id, expectedTurn), 20); return; }
        startTalkingOnControl(true, true, true);
    }
    private void scheduleReplyTimeout() {
        if (replyTimeout != null) control.removeCallbacks(replyTimeout);
        long g = generation.get();
        replyTimeout = () -> { if (g == generation.get() && state == SessionState.WAITING_REPLY) { voiceLoopArmed = false; acceptReplies = false; sendAbort(); transition(SessionReducer.Event.STOP, "Server reply timed out; text support may be unavailable."); } };
        control.postDelayed(replyTimeout, 60_000);
    }
    private void scheduleListenTimeout(InputRun run) {
        if (listenTimeout != null) control.removeCallbacks(listenTimeout);
        listenTimeout = () -> { if (currentInput(run)) { voiceLoopArmed = false; stopTalkingOnControl(); } };
        control.postDelayed(listenTimeout, 120_000);
    }
    private boolean sendJson(String json) {
        return socket != null && json.length() <= 32768 && socket.queueSize() + json.length() * 3L <= MAX_SOCKET_QUEUE_BYTES && socket.send(json);
    }
    private void sendAbort() { if (!sessionId.isEmpty()) sendJson(ProtocolJson.abort(sessionId)); }
    private void stopCapture() {
        InputRun owned = input; input = null;
        if (owned != null) { owned.aborted.set(true); owned.retired = true; retiringInputs++; owned.capture.close(); }
    }
    private void stopPlayback() { playbackId++; OpusPlayback owned = playback; playback = null; if (owned != null) owned.close(); }
    private void stopAudio() { stopCapture(); stopPlayback(); }
    private void cancelTurnTimeouts() {
        if (replyTimeout != null) control.removeCallbacks(replyTimeout);
        if (listenTimeout != null) control.removeCallbacks(listenTimeout);
    }
    private void closeOnControl(String reason) {
        generation.incrementAndGet(); if (helloTimeout != null) control.removeCallbacks(helloTimeout); cancelTurnTimeouts();
        voiceLoopArmed = false; textTurn = false; acceptReplies = false; stopAudio(); features.resetMcpSession();
        sessionId = ""; downSampleRate = 16000;
        WebSocket old = socket; socket = null; if (old != null) old.cancel();
        // Internal replacement/disposal must not emit a transient disconnect that schedules another retry.
        state = SessionState.DISCONNECTED;
    }
    private void fail(String reason) {
        // Existing audio callers may pass provider exception text: never put that in diagnostics/logs.
        terminateConnection(ConnectionDiagnostic.event(ConnectionDiagnostic.Reason.LOCAL_FAILURE, state), false);
    }
    private void fail(ConnectionDiagnostic diagnostic) { terminateConnection(diagnostic, false); }
    private void terminateConnection(ConnectionDiagnostic diagnostic, boolean allowCloseHandshake) {
        generation.incrementAndGet(); if (helloTimeout != null) control.removeCallbacks(helloTimeout); cancelTurnTimeouts();
        voiceLoopArmed = false; textTurn = false; acceptReplies = false; stopAudio(); features.resetMcpSession();
        WebSocket old = socket; socket = null; sessionId = ""; downSampleRate = 16000;
        if (old != null) {
            if (allowCloseHandshake) main.postDelayed(old::cancel, 3000); // One bounded cleanup, never a retry.
            else old.cancel();
        }
        state = diagnostic.nextState;
        publishDiagnostic(diagnostic); postState(state, diagnostic.summary);
    }
    private void publishDiagnostic(ConnectionDiagnostic diagnostic) {
        lastDiagnostic = diagnostic;
        if (diagnostic.isError) recentErrorSummary = diagnostic.summary;
        android.util.Log.i("A1260Network", diagnostic.summary);
        long g = generation.get();
        main.post(() -> { if (!closed.get() && g == generation.get()) listener.onDiagnostic(diagnostic); });
    }
    private void transition(SessionReducer.Event event, String detail) { state = SessionReducer.next(state, event); postState(state, detail); }
    private void visibleError(String text) { postState(state, text); }
    private void postState(SessionState current, String detail) {
        long g = generation.get(); main.post(() -> { if (!closed.get() && g == generation.get()) listener.onState(current, detail); });
    }
    private void transcript(String label, String text) {
        if("Assistant".equals(label) && requestStarted>0 && !measuredText){measuredText=true;android.util.Log.i("A1260Latency","first_text_ms="+(android.os.SystemClock.elapsedRealtime()-requestStarted));}
        long g = generation.get(); main.post(() -> { if (!closed.get() && g == generation.get()) listener.onTranscript(label, text); });
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        control.post(() -> {
            closeOnControl("Closed"); client.dispatcher().cancelAll(); client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown(); thread.quitSafely();
        });
    }
}
