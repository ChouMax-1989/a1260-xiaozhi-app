package io.github.choumax.a1260xiaozhi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import io.github.choumax.a1260xiaozhi.wake.WakeForegroundService;
import io.github.choumax.a1260xiaozhi.wake.WakeSettings;
import io.github.choumax.a1260xiaozhi.chat.ChatHistoryStore;
import io.github.choumax.a1260xiaozhi.chat.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Process-lifetime owner of the sole SessionController. Activities only attach an observer. */
public final class VoiceSessionService extends Service implements SessionController.Listener {
    public static final String ACTION_START = "io.github.choumax.a1260xiaozhi.session.START";
    public static final String ACTION_STOP = "io.github.choumax.a1260xiaozhi.session.STOP";
    private static final String CHANNEL = "voice_session"; private static final int NOTIFICATION = 4270;
    private static final long WAKE_COOLDOWN_MS = 3000, WAKE_START_GUARD_MS = 2000, RECONNECT_STABLE_MS = 60_000;
    private final SessionBinder binder = new SessionBinder(); private final Handler main = new Handler(Looper.getMainLooper()); private SessionController session; private SessionController.Listener observer; private ChatHistoryStore history; private List<ChatMessage> offlineMessages; private SessionState state = SessionState.DISCONNECTED; private long wakeAllowedAt; private boolean wakeDetectorStartPending;
    private int commandGeneration, reconnectAttempt; private boolean pttRequested, stopped; private boolean wakeTurnPending, wakeCueStarted, wakeStartIssued, receiverRegistered, networkCallbackRegistered, networkUsable; private PowerManager.WakeLock cpuLock; private WifiManager.WifiLock wifiLock;
    private ConnectivityManager connectivity; private ConnectivityManager.NetworkCallback networkCallback; private ReconnectPolicy reconnectPolicy; private long reconnectAt; private String reconnectReason = "NONE code=0 http=0";
    private android.media.MediaPlayer wakeCue, submitCue, conversationEndCue;
    private long conversationEndCueGeneration;
    private boolean conversationEndCuePlaying;
    private final Runnable wakeDetectorStart = () -> { wakeDetectorStartPending = false; startTemplateWake(); };
    private final Runnable wakeListenStart = this::startWakeListeningAfterCue;
    private final Runnable wakeStartGuard = this::guardWakeListeningStart;
    private final Runnable reconnectRun = () -> { reconnectAt = 0; if (reconnectPolicy != null) applyReconnect(reconnectPolicy.onRetryTimer()); };
    private final Runnable reconnectStableRun = () -> { if (reconnectPolicy != null) { reconnectPolicy.onConnectionStable(); reconnectAttempt = 0; } };
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() { @Override public void onReceive(Context context, Intent intent) { if (!WakeForegroundService.isAuthenticWake(intent)) return; beginWakeTurn(); } };
    public final class SessionBinder extends Binder {
        public void attach(SessionController.Listener value) { observer = value; value.onState(state, ""); }
        public void detach(SessionController.Listener value) { if (observer == value) { observer = null; offlineMessages = new ArrayList<>(history.load()); } }
        public void connect(ConnectionConfig config) { cancelConversationEndCue(); ensureSession(); cancelReconnectTimer(); applyReconnect(reconnectPolicy.onUserConnect(), config); }
        public void startTalking() { cancelConversationEndCue(); cancelWakeTurn(); stopTemplateWake(); if (session != null) session.startTalking(); }
        public void startPushToTalk() { cancelConversationEndCue(); cancelWakeTurn(); pttRequested=true; int request=++commandGeneration; stopTemplateWake(); main.postDelayed(() -> { if (pttRequested && request==commandGeneration && session != null && state == SessionState.READY) session.startPushToTalk(); }, 220); }
        public void stopTalking() { pttRequested=false; commandGeneration++; if (session != null) session.stopTalking(); main.postDelayed(() -> { if(state==SessionState.READY && !pttRequested)startTemplateWake(); },300); }
        public void stopAll() { cancelConversationEndCue(); cancelWakeTurn(); pttRequested=false; commandGeneration++; if (session != null) session.stopAll(); }
        public void disconnect() { cancelConversationEndCue(); cancelWakeTurn(); pttRequested=false; commandGeneration++; cancelReconnectTimer(); cancelReconnectStability(); reconnectPolicy.onUserDisconnect(); if (session != null) session.disconnect(); }
        public void sendText(String text) { cancelConversationEndCue(); stopTemplateWake(); if (session != null) session.sendText(text); }
        public String diagnosticStatus() { return "fgs=active wakeLock=" + (cpuLock != null && cpuLock.isHeld()) + " wifiLock=" + (wifiLock != null && wifiLock.isHeld()); }
    }
    @Override public void onCreate() { super.onCreate(); history = new ChatHistoryStore(this); offlineMessages = new ArrayList<>(history.load()); ensureSession(); registerReceiver(wakeReceiver, new IntentFilter(WakeForegroundService.ACTION_WAKE_DETECTED)); receiverRegistered = true; registerNetworkCallback(); notificationChannel(); startForegroundSafely(); }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { String action = intent == null ? ACTION_START : intent.getAction(); if (ACTION_STOP.equals(action)) { stopSubmitCue(); cancelConversationEndCue(); cancelWakeTurn(); stopped=true; pttRequested=false; commandGeneration++; if(reconnectPolicy!=null)reconnectPolicy.onServiceStop(); main.removeCallbacksAndMessages(null); reconnectAt=0; new WakeSettings(this).setEnabled(false); stopTemplateWake(); if (session != null) { session.close(); session = null; } releaseLocks(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; } stopped=false; ensureSession(); startForegroundSafely(); wakeAllowedAt=Math.max(wakeAllowedAt,android.os.SystemClock.elapsedRealtime()+300); SettingsStore store = new SettingsStore(this); boolean hasConfig=store.hasIssuedConfig() || store.isCustomServer(); if ((state == SessionState.DISCONNECTED || state == SessionState.ERROR) && hasConfig) applyReconnect(reconnectPolicy.onServiceStart(true),store.load()); else if(state==SessionState.READY)reconnectPolicy.onConnected(); if (new WakeSettings(this).isEnabled()) { acquireLocks(); startTemplateWake(); } else { stopTemplateWake(); releaseLocks(); } return START_STICKY; }
    private void registerNetworkCallback() {
        connectivity = getSystemService(ConnectivityManager.class);
        networkUsable = hasUsableNetwork();
        reconnectPolicy = new ReconnectPolicy(networkUsable);
        if (connectivity == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { main.post(VoiceSessionService.this::refreshNetworkState); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { main.post(VoiceSessionService.this::refreshNetworkState); }
            @Override public void onLost(Network network) { main.post(VoiceSessionService.this::refreshNetworkState); }
        };
        try { connectivity.registerDefaultNetworkCallback(networkCallback); networkCallbackRegistered = true; }
        catch (RuntimeException ignored) { networkCallback = null; }
    }
    private boolean hasUsableNetwork() {
        if (connectivity == null) return true;
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private void refreshNetworkState() {
        if (reconnectPolicy == null) return;
        boolean usable = hasUsableNetwork();
        if (usable == networkUsable) return;
        networkUsable = usable;
        if (usable) applyReconnect(reconnectPolicy.onNetworkAvailable());
        else { cancelReconnectTimer(); cancelReconnectStability(); reconnectPolicy.onNetworkLost(); }
    }
    private void applyReconnect(ReconnectPolicy.Decision decision) { applyReconnect(decision, null); }
    private void applyReconnect(ReconnectPolicy.Decision decision, ConnectionConfig requested) {
        if (decision == null || decision.action == ReconnectPolicy.Action.NONE || stopped) return;
        if (decision.action == ReconnectPolicy.Action.SCHEDULE) {
            cancelReconnectTimer(); cancelReconnectStability(); reconnectAttempt = decision.attempt;
            reconnectAt = android.os.SystemClock.elapsedRealtime() + decision.delayMillis;
            main.postDelayed(reconnectRun, decision.delayMillis); return;
        }
        if (session == null) ensureSession();
        if (requested == null && state != SessionState.DISCONNECTED && state != SessionState.ERROR
                && reconnectPolicy.isConnected()) return;
        ConnectionConfig config = requested;
        if (config == null) {
            SettingsStore store = new SettingsStore(this);
            if (!store.hasIssuedConfig() && !store.isCustomServer()) { reconnectPolicy.onUserDisconnect(); return; }
            config = store.load();
        }
        session.connect(config);
    }
    private void cancelReconnectTimer() { main.removeCallbacks(reconnectRun); reconnectAt = 0; }
    private void cancelReconnectStability() { main.removeCallbacks(reconnectStableRun); }
    private void unregisterNetworkCallback() {
        if (!networkCallbackRegistered || connectivity == null || networkCallback == null) return;
        try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) { }
        networkCallbackRegistered = false; networkCallback = null;
    }
    private void ensureSession() { if (session == null) session = new SessionController(this, this); }
    private void beginWakeTurn() {
        cancelConversationEndCue();
        WakeSessionPolicy.WakeAction action = WakeSessionPolicy.actionForWake(state, stopped, pttRequested, wakeTurnPending,
                android.os.SystemClock.elapsedRealtime() >= wakeAllowedAt);
        if (action == WakeSessionPolicy.WakeAction.IGNORE) { startTemplateWake(); return; }
        stopTemplateWake();
        wakeTurnPending = true; wakeCueStarted = false; wakeStartIssued = false;
        if (session == null) ensureSession();
        if (action == WakeSessionPolicy.WakeAction.START_NOW) startWakeWithCue();
        else if (action == WakeSessionPolicy.WakeAction.CANCEL_REPLY_THEN_START) session.stopAll();
        else {
            SettingsStore store = new SettingsStore(this);
            if (store.hasIssuedConfig() || store.isCustomServer()) {
                cancelReconnectTimer(); applyReconnect(reconnectPolicy.onUserConnect(), store.load());
            }
            else { cancelWakeTurn(); startTemplateWake(); }
        }
    }
    private void startWakeWithCue() {
        if (!wakeTurnPending || wakeCueStarted || wakeStartIssued || stopped || pttRequested || state != SessionState.READY) return;
        wakeCueStarted = true;
        try {
            wakeCue=android.media.MediaPlayer.create(this,R.raw.wake_dida);
            if(wakeCue==null){main.post(wakeListenStart);return;}
            wakeCue.setOnCompletionListener(player -> { if (wakeCue != player) { releasePlayer(player); return; } releaseWakeCue(); if (wakeTurnPending) main.postDelayed(wakeListenStart,80); });
            wakeCue.setOnErrorListener((player,what,extra) -> { if (wakeCue == player) { releaseWakeCue(); if (wakeTurnPending) main.post(wakeListenStart); } else releasePlayer(player); return true; });
            wakeCue.start();
        } catch(RuntimeException ignored){releaseWakeCue();main.post(wakeListenStart);}
    }
    private void startWakeListeningAfterCue() {
        if (!wakeTurnPending || stopped || pttRequested || session == null || state != SessionState.READY) { cancelWakeTurn(); startTemplateWake(); return; }
        wakeStartIssued = true;
        session.startWakeListening();
        main.removeCallbacks(wakeStartGuard);
        main.postDelayed(wakeStartGuard, WAKE_START_GUARD_MS);
    }
    private void guardWakeListeningStart() { if (wakeTurnPending && wakeStartIssued && state == SessionState.READY) { cancelWakeTurn(); startTemplateWake(); } }
    private void releasePlayer(android.media.MediaPlayer player) { try { player.setOnCompletionListener(null); player.setOnErrorListener(null); player.release(); } catch (RuntimeException ignored) { } }
    private void releaseWakeCue() { android.media.MediaPlayer cue=wakeCue; wakeCue=null; if(cue!=null)releasePlayer(cue); }
    private void cancelWakeTurn() { wakeTurnPending=false; wakeCueStarted=false; wakeStartIssued=false; main.removeCallbacks(wakeListenStart); main.removeCallbacks(wakeStartGuard); releaseWakeCue(); }
    private void stopSubmitCue() { if(submitCue!=null){submitCue.release();submitCue=null;} }
    private void cancelConversationEndCue() {
        conversationEndCueGeneration++;
        conversationEndCuePlaying = false;
        android.media.MediaPlayer cue = conversationEndCue;
        conversationEndCue = null;
        if (cue != null) releasePlayer(cue);
    }
    private void finishConversationEndCue(android.media.MediaPlayer player, long generation) {
        if (!conversationEndCuePlaying || generation != conversationEndCueGeneration || conversationEndCue != player) {
            releasePlayer(player); return;
        }
        conversationEndCue = null;
        conversationEndCuePlaying = false;
        releasePlayer(player);
        startTemplateWake();
    }
    @Override public void onConversationEnded() {
        if (stopped || pttRequested || wakeTurnPending || state != SessionState.READY) return;
        cancelConversationEndCue();
        stopSubmitCue();
        stopTemplateWake();
        conversationEndCuePlaying = true;
        long generation = ++conversationEndCueGeneration;
        try {
            android.media.MediaPlayer cue = android.media.MediaPlayer.create(this, R.raw.conversation_end);
            if (cue == null) {
                if (conversationEndCuePlaying && generation == conversationEndCueGeneration) {
                    conversationEndCuePlaying = false; startTemplateWake();
                }
                return;
            }
            conversationEndCue = cue;
            cue.setOnCompletionListener(player -> finishConversationEndCue(player, generation));
            cue.setOnErrorListener((player, what, extra) -> { finishConversationEndCue(player, generation); return true; });
            cue.start();
        } catch (RuntimeException ignored) {
            if (generation == conversationEndCueGeneration) { cancelConversationEndCue(); startTemplateWake(); }
        }
    }
    @Override public void onVoiceSubmitted() {
        if(stopped || state==SessionState.SPEAKING || state==SessionState.DRAINING)return;
        stopSubmitCue();
        try { submitCue=android.media.MediaPlayer.create(this,R.raw.voice_sent); if(submitCue==null)return;
            submitCue.setOnCompletionListener(player -> { if(submitCue==player)stopSubmitCue(); });
            submitCue.setOnErrorListener((player,what,extra) -> { if(submitCue==player)stopSubmitCue();return true; });
            submitCue.start();
        } catch(RuntimeException ignored){stopSubmitCue();}
    }
    private void startTemplateWake() { if (conversationEndCuePlaying) return; boolean enabled=new WakeSettings(this).isEnabled(); if (!WakeSessionPolicy.shouldRunDetector(state, enabled, stopped, pttRequested, wakeTurnPending)) return; long delay=wakeAllowedAt-android.os.SystemClock.elapsedRealtime(); if(delay>0){if(!wakeDetectorStartPending){wakeDetectorStartPending=true;main.postDelayed(wakeDetectorStart,delay);}return;} try { // The parent is already a foreground service. The child promotes itself before capture.
            // Avoid a pending startForegroundService deadline when a state transition cancels this start.
            startService(new Intent(this, WakeForegroundService.class).setAction(WakeForegroundService.ACTION_START)); } catch (RuntimeException ignored) { } }
    private void stopTemplateWake() { main.removeCallbacks(wakeDetectorStart); wakeDetectorStartPending=false; stopService(new Intent(this,WakeForegroundService.class).setAction(WakeForegroundService.ACTION_STOP)); }
    private void acquireLocks() { if (cpuLock == null) { PowerManager power = getSystemService(PowerManager.class); cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a1260xiaozhi:voice-session"); cpuLock.setReferenceCounted(false); } if (!cpuLock.isHeld()) cpuLock.acquire(); if (wifiLock == null) { WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class); wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "a1260xiaozhi:voice-session"); wifiLock.setReferenceCounted(false); } if (!wifiLock.isHeld()) wifiLock.acquire(); }
    private void releaseLocks() { if (cpuLock != null && cpuLock.isHeld()) cpuLock.release(); if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); }
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) { super.dump(fd, writer, args); long retryIn=reconnectAt==0?0:Math.max(0,reconnectAt-android.os.SystemClock.elapsedRealtime()); writer.println(binder.diagnosticStatus() + " state=" + state + " wakeEnabled=" + new WakeSettings(this).isEnabled()); writer.println("reconnect=" + reconnectReason + " attempt=" + reconnectAttempt + " retryInMs=" + retryIn + " network=" + networkUsable); writer.println("wakeRecent=" + WakeForegroundService.diagnosticHistory()); }
    @Override public void onState(SessionState state, String detail) {
        SessionState previous=this.state; this.state = state; long now=android.os.SystemClock.elapsedRealtime();
        if(state!=SessionState.READY)cancelConversationEndCue();
        if(state==SessionState.SPEAKING || state==SessionState.DRAINING)stopSubmitCue();
        if(state==SessionState.LISTENING || state==SessionState.WAITING_REPLY || state==SessionState.SPEAKING || state==SessionState.DRAINING || state==SessionState.CONNECTING || state==SessionState.HELLO_WAIT)stopTemplateWake();
        if(state==SessionState.LISTENING || state==SessionState.WAITING_REPLY || state==SessionState.SPEAKING || state==SessionState.DRAINING)wakeAllowedAt=now+WAKE_COOLDOWN_MS;
        if(state==SessionState.READY && previous!=SessionState.READY)wakeAllowedAt=Math.max(wakeAllowedAt,now+WAKE_COOLDOWN_MS);
        if (state == SessionState.READY && reconnectPolicy != null) { cancelReconnectTimer(); reconnectPolicy.onConnected(); }
        updateNotification(); SessionController.Listener active = observer; if (active != null) active.onState(state, detail);
        if (wakeTurnPending) {
            if (state == SessionState.LISTENING) cancelWakeTurn();
            else if (state == SessionState.READY) startWakeWithCue();
            else if ((state == SessionState.ERROR || state == SessionState.DISCONNECTED) && (previous == SessionState.CONNECTING || previous == SessionState.HELLO_WAIT)) { cancelWakeTurn(); startTemplateWake(); }
            return;
        }
        startTemplateWake();
    }
    @Override public void onDiagnostic(ConnectionDiagnostic diagnostic) { if(diagnostic.isError)cancelConversationEndCue(); SessionController.Listener active = observer; if (active != null) active.onDiagnostic(diagnostic); reconnectReason=diagnostic.reason.name()+" code="+diagnostic.webSocketCloseCode+" http="+diagnostic.httpStatus; if(reconnectPolicy==null||stopped)return; if(diagnostic.reason==ConnectionDiagnostic.Reason.CONNECTED){cancelReconnectTimer();cancelReconnectStability();reconnectPolicy.onConnected();main.postDelayed(reconnectStableRun,RECONNECT_STABLE_MS);return;} cancelReconnectStability();applyReconnect(reconnectPolicy.onTerminal(diagnostic.recovery)); }
    @Override public void onTranscript(String label, String text) { SessionController.Listener active = observer; if (active != null) active.onTranscript(label, text); else if ("You".equals(label) || "Assistant".equals(label)) { offlineMessages.add(new ChatMessage("You".equals(label) ? ChatMessage.Role.USER : ChatMessage.Role.ASSISTANT, ChatHistoryStore.trim(text))); while (offlineMessages.size() > 48) offlineMessages.remove(0); history.save(offlineMessages); } }
    private void notificationChannel() { getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)); }
    private void startForegroundSafely() { startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); }
    private Notification notification() { Intent stop = new Intent(this, VoiceSessionService.class).setAction(ACTION_STOP); PendingIntent action = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_wake_mic).setContentTitle(getString(R.string.app_name)).setContentText(state == SessionState.LISTENING ? getString(R.string.chat_listening) : state == SessionState.SPEAKING ? getString(R.string.chat_replying) : getString(R.string.chat_ready)).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(R.drawable.ic_wake_mic, getString(R.string.stop), action).build()).build(); }
    private void updateNotification() { getSystemService(NotificationManager.class).notify(NOTIFICATION, notification()); }
    @Override public void onDestroy() { stopSubmitCue(); cancelConversationEndCue(); cancelWakeTurn(); cancelReconnectTimer(); cancelReconnectStability(); if(reconnectPolicy!=null)reconnectPolicy.onServiceStop(); unregisterNetworkCallback(); stopTemplateWake(); if (receiverRegistered) unregisterReceiver(wakeReceiver); releaseLocks(); if (session != null) { session.close(); session = null; } super.onDestroy(); }
}
