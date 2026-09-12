package io.github.choumax.a1260xiaozhi;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    private EditText assistantName, endpoint, token; private CheckBox halfDuplex, fullDuplex, customServer; private SeekBar speechPauseWait, followUpWait;
    @Override public void onCreate(Bundle state) { super.onCreate(state); setContentView(build()); }
    private View build() {
        SettingsStore store = new SettingsStore(this); ConnectionConfig current = store.load();
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,28,28,28);
        TextView title = new TextView(this); title.setText(R.string.connection_settings); title.setTextSize(24); root.addView(title);
        assistantName = field(root, getString(R.string.assistant_display_name_label), getString(R.string.assistant_display_name_hint), store.assistantDisplayName(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        assistantName.setSingleLine(true);
        customServer = new CheckBox(this); customServer.setText(R.string.custom_server); customServer.setChecked(store.isCustomServer()); root.addView(customServer);
        endpoint = field(root, getString(R.string.endpoint_label), getString(R.string.endpoint_hint), current.endpoint, InputType.TYPE_TEXT_VARIATION_URI);
        token = field(root, getString(R.string.token_label), getString(R.string.token_hint), store.isCustomServer() ? current.token : "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        halfDuplex = new CheckBox(this); halfDuplex.setText(R.string.continuous_half_duplex); halfDuplex.setChecked(current.continuousHalfDuplex); root.addView(halfDuplex);
        fullDuplex = new CheckBox(this); fullDuplex.setText(R.string.full_duplex); fullDuplex.setChecked(current.fullDuplex); root.addView(fullDuplex);
        TextView warning = new TextView(this); warning.setText(R.string.full_duplex_warning); warning.setPadding(0, 0, 0, 12); root.addView(warning);
        TextView speechPauseTitle = new TextView(this); speechPauseTitle.setText("说完后多久发送"); speechPauseTitle.setTextSize(18); root.addView(speechPauseTitle);
        TextView speechPauseRange = new TextView(this); speechPauseRange.setText("可设置 0.4–2 秒，每次 0.1 秒"); root.addView(speechPauseRange);
        TextView speechPauseValue = new TextView(this); speechPauseValue.setText(SpeechPauseSettings.secondsLabel(store.speechPauseMs())); root.addView(speechPauseValue);
        speechPauseWait = new SeekBar(this); speechPauseWait.setMax(SpeechPauseSettings.MAX_PROGRESS); speechPauseWait.setProgress(SpeechPauseSettings.toProgress(store.speechPauseMs()));
        speechPauseWait.setContentDescription("说完后多久发送，0.4 到 2 秒"); root.addView(speechPauseWait);
        speechPauseWait.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { speechPauseValue.setText(SpeechPauseSettings.secondsLabel(SpeechPauseSettings.fromProgress(progress))); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        TextView speechPauseHelp = new TextView(this); speechPauseHelp.setText("短停顿响应快，句中停顿可能提前发送。保存后下一轮生效。"); speechPauseHelp.setPadding(0, 0, 0, 12); root.addView(speechPauseHelp);
        TextView followUpTitle = new TextView(this); followUpTitle.setText("回答后等待追问"); followUpTitle.setTextSize(18); root.addView(followUpTitle);
        TextView followUpRange = new TextView(this); followUpRange.setText("可设置 0.5–10 秒，每次 0.1 秒"); root.addView(followUpRange);
        TextView followUpValue = new TextView(this); followUpValue.setText(FollowUpSettings.secondsLabel(store.followUpWaitMs())); root.addView(followUpValue);
        followUpWait = new SeekBar(this); followUpWait.setMax(FollowUpSettings.MAX_PROGRESS); followUpWait.setProgress(FollowUpSettings.toProgress(store.followUpWaitMs()));
        followUpWait.setContentDescription("回答后等待追问，0.5 到 10 秒"); root.addView(followUpWait);
        followUpWait.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { followUpValue.setText(FollowUpSettings.secondsLabel(FollowUpSettings.fromProgress(progress))); }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        TextView followUpHelp = new TextView(this); followUpHelp.setText("通过唤醒词开始对话，回答播完后可直接追问。等待期间没有说话，会播放结束提示音并回到唤醒。按住说话和全双工模式不适用。保存后从下一轮生效。"); followUpHelp.setPadding(0, 0, 0, 12); root.addView(followUpHelp);
        customServer.setOnCheckedChangeListener((button, checked) -> { endpoint.setEnabled(checked); token.setEnabled(checked); }); endpoint.setEnabled(customServer.isChecked()); token.setEnabled(customServer.isChecked());
        TextView help = new TextView(this); help.setText(R.string.settings_help); help.setPadding(0,16,0,16); root.addView(help);
        Button save = new Button(this); save.setText(R.string.save_settings); save.setOnClickListener(v -> { store.setAssistantDisplayName(assistantName.getText().toString()); store.setSpeechPauseMs(SpeechPauseSettings.fromProgress(speechPauseWait.getProgress())); store.setFollowUpWaitMs(FollowUpSettings.fromProgress(followUpWait.getProgress())); if (customServer.isChecked()) store.saveCustom(endpoint.getText().toString(), token.getText().toString(), halfDuplex.isChecked(), fullDuplex.isChecked()); else store.saveOfficialPreferences(halfDuplex.isChecked(), fullDuplex.isChecked()); finish(); }); root.addView(save); ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root); return scroll;
    }
    private EditText field(LinearLayout root, String label, String hint, String value, int type) { TextView text = new TextView(this); text.setText(label); root.addView(text); EditText edit = new EditText(this); edit.setHint(hint); edit.setInputType(type); edit.setText(value); root.addView(edit); return edit; }
}
