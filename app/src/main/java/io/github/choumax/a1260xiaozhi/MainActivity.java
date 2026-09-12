package io.github.choumax.a1260xiaozhi;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import io.github.choumax.a1260xiaozhi.chat.ChatBubbleAdapter;
import io.github.choumax.a1260xiaozhi.chat.ChatHistoryStore;
import io.github.choumax.a1260xiaozhi.chat.ChatMessage;
import io.github.choumax.a1260xiaozhi.features.FeatureHubActivity;
import io.github.choumax.a1260xiaozhi.features.LocalMusicActivity;
import io.github.choumax.a1260xiaozhi.wake.WakeEnrollActivity;
import io.github.choumax.a1260xiaozhi.wake.WakeForegroundService;
import io.github.choumax.a1260xiaozhi.wake.WakeSettings;
import java.util.ArrayList;
import java.util.List;

/** Compact foreground-only chat surface for the 480x728 A1260 display. */
public final class MainActivity extends Activity implements SessionController.Listener, ActivationController.Listener {
    private boolean recordFeedbackSent, fullDuplexUi;
    private int networkRetries;
    private boolean foreground;
    private static final int MIC_FOR_TALK = 7, MIC_FOR_TEST = 8, MENU_CONNECT = 1, MENU_SETTINGS = 2, MENU_FEATURES = 3, MENU_MUSIC = 4, MENU_WAKE = 5, MENU_SELF_TEST = 6, MENU_CLEAR = 7, MENU_STOP = 8;
    private TextView chatTitle, status, activationMessage, activationCode, waiting;
    private Button talk, mode, send;
    private EditText composer;
    private View activationPanel;
    private VoiceSessionService.SessionBinder controller;
    private ConnectionConfig pendingConnect;
    private ActivationController activation;
    private ActivationAudioPlayer activationAudio;
    private SessionState currentState = SessionState.DISCONNECTED;
    private boolean wakePending, receiverRegistered, sessionBound, talkHeld, textMode = true;
    private ChatHistoryStore history;
    private List<ChatMessage> messages;
    private ChatBubbleAdapter chatAdapter;
    private ListView chatList;

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!WakeForegroundService.isAuthenticWake(intent)) return;
            cancelWakeNotification();
        }
    };
    private final ServiceConnection sessionConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, android.os.IBinder value) { sessionBound = true; controller = (VoiceSessionService.SessionBinder) value; controller.attach(MainActivity.this); if (pendingConnect != null) { controller.connect(pendingConnect); pendingConnect = null; } }
        @Override public void onServiceDisconnected(ComponentName name) { sessionBound = false; controller = null; }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        cancelWakeNotification(); startForegroundService(new Intent(this, VoiceSessionService.class).setAction(VoiceSessionService.ACTION_START)); activation = new ActivationController(this, this); activationAudio = new ActivationAudioPlayer(this);
        history = new ChatHistoryStore(this); messages = new ArrayList<>(history.load()); chatAdapter = new ChatBubbleAdapter(this, messages);
        setContentView(buildUi()); render(SessionState.DISCONNECTED); scrollChatToBottom();
        SettingsStore store = new SettingsStore(this); if (StartupDecision.decide(store.isCustomServer(), store.hasIssuedConfig()) == StartupDecision.Action.ACTIVATE) activation.start(); else { activationPanel.setVisibility(View.GONE); }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(245, 247, 248));
        root.addView(topBar(), new LinearLayout.LayoutParams(-1, dp(44)));
        chatList = new ListView(this); chatList.setId(R.id.chat_message_list); chatList.setDivider(null); chatList.setDividerHeight(0); chatList.setClipToPadding(false); chatList.setPadding(dp(12), dp(8), dp(12), dp(8)); chatList.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
        activationPanel = activationPanel(); chatList.addHeaderView(activationPanel, null, false); chatList.setAdapter(chatAdapter); root.addView(chatList, new LinearLayout.LayoutParams(-1, 0, 1));
        waiting = new TextView(this); waiting.setId(R.id.chat_waiting); waiting.setText(R.string.chat_waiting); waiting.setTextSize(13); waiting.setTextColor(Color.rgb(110, 122, 132)); waiting.setPadding(dp(16), dp(2), dp(16), dp(2)); waiting.setVisibility(View.GONE); root.addView(waiting);
        root.addView(inputBar(), new LinearLayout.LayoutParams(-1, dp(54)));
        return root;
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(14), 0, dp(6), 0); bar.setBackgroundColor(Color.WHITE);
        LinearLayout titleArea = new LinearLayout(this); titleArea.setOrientation(LinearLayout.VERTICAL); titleArea.setGravity(Gravity.CENTER_VERTICAL);
        chatTitle = new TextView(this); chatTitle.setText(new SettingsStore(this).assistantDisplayName()); chatTitle.setTextSize(17); chatTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); chatTitle.setTextColor(Color.rgb(25, 31, 36)); titleArea.addView(chatTitle);
        status = new TextView(this); status.setId(R.id.chat_status); status.setTextSize(11); status.setTextColor(Color.rgb(78, 151, 97)); titleArea.addView(status); bar.addView(titleArea, new LinearLayout.LayoutParams(0, -1, 1));
        Button menu = compactButton(R.string.chat_more, R.id.chat_more); menu.setContentDescription(getString(R.string.chat_more)); menu.setOnClickListener(this::showMenu); bar.addView(menu, new LinearLayout.LayoutParams(dp(42), -1)); return bar;
    }

    private View inputBar() {
        LinearLayout bar = new LinearLayout(this); bar.setId(R.id.chat_input_panel); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(8), dp(4), dp(8), dp(4)); bar.setBackgroundColor(Color.WHITE);
        mode = compactButton(R.string.chat_mode_voice, R.id.chat_mode_toggle); mode.setOnClickListener(v -> setTextMode(!textMode)); bar.addView(mode, new LinearLayout.LayoutParams(dp(46), -1));
        composer = new EditText(this); composer.setId(R.id.chat_composer); composer.setHint(R.string.chat_hint); composer.setTextSize(16); composer.setSingleLine(true); composer.setMaxLines(1); composer.setPadding(dp(10), 0, dp(8), 0); composer.setBackground(round(Color.rgb(243, 245, 246), dp(10))); bar.addView(composer, new LinearLayout.LayoutParams(0, -1, 1));
        send = compactButton(R.string.chat_send, R.id.chat_send); send.setOnClickListener(v -> sendText()); bar.addView(send, new LinearLayout.LayoutParams(dp(48), -1));
        talk = compactButton(R.string.hold_to_talk, R.id.chat_push_to_talk); talk.setTextSize(16); talk.setBackground(round(Color.rgb(236, 240, 238), dp(10))); talk.setOnTouchListener(this::onTalkTouch); bar.addView(talk, new LinearLayout.LayoutParams(0, -1, 1));
        setTextMode(true); return bar;
    }

    private Button compactButton(int text, int id) { Button button = new Button(this); button.setId(id); button.setText(text); button.setTextSize(13); button.setTextColor(Color.rgb(38, 52, 61)); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(3), 0, dp(3), 0); button.setBackgroundColor(Color.TRANSPARENT); return button; }
    private android.graphics.drawable.GradientDrawable round(int color, int radius) { android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(radius); return drawable; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void setTextMode(boolean enabled) { if (talkHeld) cancelHeldTalk(); textMode = enabled; if (composer == null) return; composer.setVisibility(enabled ? View.VISIBLE : View.GONE); send.setVisibility(enabled ? View.VISIBLE : View.GONE); talk.setVisibility(enabled ? View.GONE : View.VISIBLE); mode.setText(enabled ? R.string.chat_mode_voice : R.string.chat_mode_text); }
    private boolean onTalkTouch(View view, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) { talkHeld = true; recordFeedbackSent = false; updateRecordFeedback(); ensureMicPermissionForHeldTalk(); return true; }
        if (event.getAction() == MotionEvent.ACTION_UP) { talkHeld = false; updateRecordFeedback(); if (controller != null) controller.stopTalking(); view.performClick(); return true; }
        if (event.getAction() == MotionEvent.ACTION_CANCEL) { cancelHeldTalk(); return true; }
        return true;
    }
    private void ensureMicPermissionForHeldTalk() { if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startHeldTalk(); else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_FOR_TALK); }
    private void startHeldTalk() { if (!talkHeld || controller == null) return; stopService(new Intent(this, WakeForegroundService.class)); controller.startPushToTalk(); }
    private void cancelHeldTalk() { talkHeld = false; updateRecordFeedback(); if (controller != null) controller.stopAll(); }
    private void updateRecordFeedback() {
        if (talk == null) return;
        boolean recording = talkHeld && currentState == SessionState.LISTENING;
        talk.setText(talkHeld ? (recording ? "正在录音 · 松开发送" : "正在开启麦克风…") : "按住说话");
        talk.setTextColor(talkHeld ? Color.WHITE : Color.rgb(38,52,61));
        talk.setBackground(round(talkHeld ? Color.rgb(20,125,84) : Color.rgb(236,240,238), dp(10)));
        talk.setScaleX(talkHeld ? 0.97f : 1f); talk.setScaleY(talkHeld ? 0.94f : 1f); talk.setElevation(talkHeld ? dp(4) : 0);
        if (recording && !recordFeedbackSent) { recordFeedbackSent = true; android.os.Vibrator vibrator = (android.os.Vibrator)getSystemService(VIBRATOR_SERVICE); if (vibrator != null && vibrator.hasVibrator()) vibrator.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE)); }
    }
    private void sendText() { String text = ChatHistoryStore.trim(composer.getText().toString()); if (text.isEmpty() || currentState != SessionState.READY || controller == null) return; composer.setText(""); waiting.setVisibility(View.VISIBLE); controller.sendText(text); }
    private void addMessage(ChatMessage.Role role, String text) { String safe = ChatHistoryStore.trim(text); if (safe.isEmpty()) return; if (role == ChatMessage.Role.ASSISTANT && !messages.isEmpty() && messages.get(messages.size() - 1).role == ChatMessage.Role.ASSISTANT) { ChatMessage last = messages.remove(messages.size() - 1); messages.add(new ChatMessage(ChatMessage.Role.ASSISTANT, ChatHistoryStore.trim(last.text + "\n" + safe))); } else messages.add(new ChatMessage(role, safe)); while (messages.size() > 48) messages.remove(0); history.save(messages); chatAdapter.notifyDataSetChanged(); scrollChatToBottom(); }
    private void clearHistory() { messages.clear(); history.clear(); chatAdapter.notifyDataSetChanged(); waiting.setVisibility(View.GONE); }
    private void reloadHistory() { messages.clear(); messages.addAll(history.load()); chatAdapter.notifyDataSetChanged(); scrollChatToBottom(); }
    private void scrollChatToBottom() { if (chatList != null) chatList.post(() -> chatList.setSelection(chatAdapter.getCount())); }

    private View activationPanel() { LinearLayout panel = new LinearLayout(this); panel.setId(R.id.chat_activation_panel); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16), dp(14), dp(16), dp(12)); panel.setBackground(round(Color.rgb(232, 244, 252), dp(12))); TextView title = new TextView(this); title.setText(R.string.activation_title); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); panel.addView(title); activationMessage = new TextView(this); activationMessage.setText(R.string.activation_initial); activationMessage.setTextSize(14); activationMessage.setPadding(0, dp(6), 0, dp(4)); panel.addView(activationMessage); activationCode = new TextView(this); activationCode.setTextSize(32); activationCode.setGravity(Gravity.CENTER); activationCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); activationCode.setLetterSpacing(0.12f); panel.addView(activationCode); LinearLayout actions = new LinearLayout(this); Button refresh = compactButton(R.string.activation_refresh, R.id.chat_activation_refresh); refresh.setOnClickListener(v -> activation.refresh()); actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(40), 1)); Button cancel = compactButton(R.string.activation_cancel, R.id.chat_activation_cancel); cancel.setOnClickListener(v -> { activation.cancel(); activationAudio.stopNow(); activationCode.setText(""); }); actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(40), 1)); Button rebind = compactButton(R.string.activation_rebind, R.id.chat_activation_rebind); rebind.setOnClickListener(v -> { activationAudio.stopNow(); activationCode.setText(""); activation.rebind(); }); actions.addView(rebind, new LinearLayout.LayoutParams(0, dp(40), 1)); panel.addView(actions); return panel; }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor); boolean disconnected = currentState == SessionState.DISCONNECTED || currentState == SessionState.ERROR;
        menu.getMenu().add(0, MENU_CONNECT, 0, disconnected ? R.string.connect : R.string.disconnect); menu.getMenu().add(0, MENU_SETTINGS, 1, R.string.settings); menu.getMenu().add(0, MENU_FEATURES, 2, R.string.feature_hub_button); menu.getMenu().add(0, MENU_MUSIC, 3, R.string.chat_music); menu.getMenu().add(0, MENU_WAKE, 4, R.string.wake_button); menu.getMenu().add(0, MENU_SELF_TEST, 5, R.string.device_self_test); menu.getMenu().add(0, MENU_CLEAR, 6, R.string.chat_clear); menu.getMenu().add(0, MENU_STOP, 7, R.string.stop);
        menu.setOnMenuItemClickListener(item -> { switch (item.getItemId()) { case MENU_CONNECT: if (disconnected) connectOrActivate(); else if (controller != null) controller.disconnect(); return true; case MENU_SETTINGS: startActivity(new Intent(this, SettingsActivity.class)); return true; case MENU_FEATURES: startActivity(new Intent(this, FeatureHubActivity.class)); return true; case MENU_MUSIC: startActivity(new Intent(this, LocalMusicActivity.class)); return true; case MENU_WAKE: startActivity(new Intent(this, WakeEnrollActivity.class)); return true; case MENU_SELF_TEST: ensureSelfTestPermission(); return true; case MENU_CLEAR: clearHistory(); return true; case MENU_STOP: if (controller != null) controller.stopAll(); return true; default: return false; } }); menu.show();
    }

    private void connectOrActivate() { SettingsStore store = new SettingsStore(this); if (!store.isCustomServer() && !store.hasIssuedConfig()) activation.start(); else if (controller != null) controller.connect(store.load()); else pendingConnect = store.load(); }
    private void startTalkingAfterWake() { wakePending = false; if (controller != null) controller.startTalking(); }
    private void ensureSelfTestPermission() { if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) runSelfTest(); else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_FOR_TEST); }
    private void runSelfTest() { new Thread(() -> { DeviceSelfTest.Result result = DeviceSelfTest.run(this); runOnUiThread(() -> status.setText(result.passed ? R.string.chat_ready : R.string.chat_error)); }, "a1260-self-test").start(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) { super.onRequestPermissionsResult(requestCode, permissions, grants); boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED; if (requestCode == MIC_FOR_TALK) { if (granted && talkHeld) startHeldTalk(); else if (!granted) status.setText(R.string.chat_mic_denied); } else if (requestCode == MIC_FOR_TEST) { if (granted) runSelfTest(); else status.setText(R.string.chat_mic_denied); } }
    @Override public void onState(SessionState state, String detail) { currentState = state; render(state); updateRecordFeedback(); if(state == SessionState.READY) networkRetries=0; else if(foreground && networkRetries<3 && ((state == SessionState.ERROR && detail.startsWith("Connection failed")) || (state == SessionState.DISCONNECTED && detail.contains("Server closed")))) { int attempt=++networkRetries; status.setText("连接断开，正在重连…"); status.postDelayed(() -> { if(foreground && networkRetries==attempt && (currentState==SessionState.ERROR || currentState==SessionState.DISCONNECTED)) connectOrActivate(); }, 2000L*attempt); } if (state == SessionState.WAITING_REPLY) waiting.setVisibility(View.VISIBLE); else if (state == SessionState.READY || state == SessionState.ERROR || state == SessionState.DISCONNECTED) waiting.setVisibility(View.GONE); }
    private void render(SessionState state) { int label; switch (state) { case CONNECTING: case HELLO_WAIT: label = R.string.chat_connecting; break; case READY: label = R.string.chat_ready; break; case LISTENING: label = R.string.chat_listening; break; case WAITING_REPLY: label = R.string.chat_waiting_short; break; case SPEAKING: case DRAINING: label = R.string.chat_replying; break; case ERROR: label = R.string.chat_error; break; default: label = R.string.chat_offline; } status.setText(label); boolean canStart = state == SessionState.READY || (fullDuplexUi && state == SessionState.SPEAKING); talk.setEnabled(canStart || (talkHeld && state == SessionState.LISTENING)); send.setEnabled(state == SessionState.READY); }
    @Override public void onTranscript(String label, String text) { if ("Binding".equals(label)) { activationPanel.setVisibility(View.VISIBLE); activationMessage.setText(R.string.chat_binding_needed); waiting.setVisibility(View.GONE); return; } if ("Emotion".equals(label)) { return; } if ("You".equals(label)) addMessage(ChatMessage.Role.USER, text); else if ("Assistant".equals(label)) { addMessage(ChatMessage.Role.ASSISTANT, text); waiting.setVisibility(View.GONE); } }
    @Override public void onActivationState(ActivationState state, String detail) { activationPanel.setVisibility(state == ActivationState.CONFIG_READY ? View.GONE : View.VISIBLE); activationMessage.setText(detail); }
    @Override public void onBindingCode(String message, String code) { activationCode.setText(code); activationAudio.announce(code); }
    @Override public void onIssuedConfig(ConnectionConfig config) { activationCode.setText(""); if (controller != null) controller.connect(config); else pendingConnect = config; }
    private void cancelWakeNotification() { try { ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(4262); } catch (RuntimeException ignored) { } }
    @Override protected void onStart() { super.onStart(); SettingsStore store=new SettingsStore(this); fullDuplexUi=store.isFullDuplexEnabled(); chatTitle.setText(store.assistantDisplayName()); reloadHistory(); bindService(new Intent(this, VoiceSessionService.class), sessionConnection, Context.BIND_AUTO_CREATE); foreground=true; cancelWakeNotification(); if (!receiverRegistered) { IntentFilter filter = new IntentFilter(WakeForegroundService.ACTION_WAKE_DETECTED); if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(wakeReceiver, filter); receiverRegistered = true; } }
    @Override protected void onStop() { foreground=false; networkRetries++; if (talkHeld) cancelHeldTalk(); if (controller != null) controller.detach(this); if (receiverRegistered) { unregisterReceiver(wakeReceiver); receiverRegistered = false; } if (sessionBound) unbindService(sessionConnection); sessionBound = false; controller = null; super.onStop(); }
    @Override protected void onDestroy() { activationAudio.close(); activation.close(); super.onDestroy(); }
}
