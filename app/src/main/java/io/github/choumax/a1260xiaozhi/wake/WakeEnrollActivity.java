package io.github.choumax.a1260xiaozhi.wake;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import io.github.choumax.a1260xiaozhi.R;
import java.io.IOException;
import java.util.Arrays;

/** Visible setup and three-take recognition check for the local Chinese wake keyword. */
public final class WakeEnrollActivity extends Activity {
    private static final int PERMISSION = 2401;
    private static final int REQUEST_VERIFY = 1, REQUEST_ENABLE = 2;
    private final Object takeSignal = new Object();
    private volatile boolean cancelled, takeRequested;
    private volatile WakeMicrophone microphone;
    private Thread task;
    private TextView status;
    private EditText keywordInput;
    private SeekBar sensitivity;
    private TextView sensitivityValue;
    private Button save, record, cancel, start, stop;
    private int pendingPermissionAction;
    private volatile int verifiedTakes;
    private boolean visible;

    @Override public void onCreate(Bundle state) { super.onCreate(state); setContentView(build()); }
    @Override protected void onStart() { super.onStart(); visible = true; showStoredState(); updateButtons(); }
    @Override protected void onStop() { visible = false; cancelVerification(false); super.onStop(); }
    @Override protected void onDestroy() { cancelVerification(false); super.onDestroy(); }

    private View build() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(pad, pad, pad, pad); scroll.addView(root);
        TextView title = new TextView(this); title.setText(R.string.wake_enroll_title); title.setTextSize(25); root.addView(title);
        TextView help = new TextView(this); help.setText(R.string.wake_enroll_help); help.setPadding(0, pad, 0, pad); root.addView(help);
        TextView label = new TextView(this); label.setText(R.string.wake_keyword_label); root.addView(label);
        keywordInput = new EditText(this); keywordInput.setSingleLine(true); keywordInput.setInputType(InputType.TYPE_CLASS_TEXT); keywordInput.setHint(R.string.wake_keyword_hint); keywordInput.setText(new WakeSettings(this).keyword()); root.addView(keywordInput);
        save = button(root, R.string.wake_save_keyword, () -> saveKeyword(true));
        sensitivityValue = new TextView(this); sensitivityValue.setPadding(0, pad, 0, 0); root.addView(sensitivityValue);
        sensitivity = new SeekBar(this); sensitivity.setMax(100);
        sensitivity.setContentDescription(getString(R.string.wake_sensitivity_description));
        sensitivity.setProgress(new WakeSettings(this).sensitivity());
        sensitivityValue.setText(getString(R.string.wake_sensitivity_value, sensitivity.getProgress()));
        root.addView(sensitivity);
        TextView sensitivityHelp = new TextView(this); sensitivityHelp.setText(R.string.wake_sensitivity_help); root.addView(sensitivityHelp);
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                sensitivityValue.setText(getString(R.string.wake_sensitivity_value, value));
                // Also save keyboard/accessibility adjustments that have no touch-stop callback.
                if (fromUser) new WakeSettings(WakeEnrollActivity.this).setSensitivity(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                status.setText(R.string.wake_sensitivity_saved);
            }
        });
        status = new TextView(this); status.setPadding(0, pad, 0, pad); root.addView(status);
        record = button(root, R.string.wake_record_take, () -> withPermission(REQUEST_VERIFY));
        cancel = button(root, R.string.wake_cancel_recording, () -> cancelVerification(true));
        start = button(root, R.string.wake_enable, () -> withPermission(REQUEST_ENABLE));
        stop = button(root, R.string.wake_stop, this::stopWake);
        return scroll;
    }

    private Button button(LinearLayout root, int text, Runnable click) {
        Button button = new Button(this); button.setText(text); button.setOnClickListener(v -> click.run()); root.addView(button); return button;
    }

    private void showStoredState() {
        WakeSettings settings = new WakeSettings(this);
        verifiedTakes = settings.isKeywordVerified() ? 3 : 0;
        status.setText(settings.isEnabled() && settings.isKeywordVerified()
                ? R.string.wake_enabled
                : settings.isKeywordVerified() ? R.string.wake_keyword_verified : R.string.wake_keyword_needs_verification);
    }

    private boolean saveKeyword(boolean refresh) {
        String keyword = keywordInput.getText().toString();
        WakeKeywordValidator.Result result = WakeKeywordValidator.validate(keyword);
        if (result != WakeKeywordValidator.Result.VALID) {
            status.setText(result == WakeKeywordValidator.Result.EMPTY ? R.string.wake_keyword_required
                    : result == WakeKeywordValidator.Result.TOO_SHORT || result == WakeKeywordValidator.Result.TOO_LONG
                    ? R.string.wake_keyword_length_error : R.string.wake_keyword_chinese_error);
            return false;
        }
        WakeSettings settings = new WakeSettings(this);
        String previous = settings.keyword(); boolean wasEnabled = settings.isEnabled();
        settings.setKeyword(keyword);
        boolean changed = !previous.equals(keyword);
        if (changed) {
            verifiedTakes = 0;
            if (wasEnabled) settings.setEnabled(false);
            if (visible) stopService(new Intent(this, WakeForegroundService.class));
            status.setText(R.string.wake_keyword_saved_verify);
        } else {
            verifiedTakes = settings.isKeywordVerified() ? 3 : 0;
            status.setText(R.string.wake_keyword_saved);
            if (refresh && wasEnabled && settings.isKeywordVerified() && visible) restartWakeService(settings);
        }
        updateButtons();
        return true;
    }

    private void withPermission(int action) {
        if (!visible) return;
        if (action == REQUEST_ENABLE && !saveKeyword(false)) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (action == REQUEST_VERIFY) requestVerificationTake(); else enable();
        } else {
            pendingPermissionAction = action;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != PERMISSION) return;
        int action = pendingPermissionAction; pendingPermissionAction = 0;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED && visible) withPermission(action);
        else status.setText(R.string.wake_permission_denied);
    }

    private void requestVerificationTake() {
        if (task == null) {
            if (!saveKeyword(false)) return;
            WakeSettings settings = new WakeSettings(this);
            if (settings.isEnabled()) settings.setEnabled(false);
            stopService(new Intent(this, WakeForegroundService.class));
            verifiedTakes = 0; cancelled = false;
            String keyword = settings.keyword();
            synchronized (takeSignal) { takeRequested = true; }
            task = new Thread(() -> verificationLoop(keyword), "wake-keyword-verification");
            status.setText(R.string.wake_recognizer_loading); task.start(); updateButtons();
            return;
        }
        synchronized (takeSignal) {
            if (takeRequested || microphone != null || cancelled) return;
            takeRequested = true; takeSignal.notifyAll();
        }
        status.setText(getString(R.string.wake_recording_take, verifiedTakes + 1)); updateButtons();
    }

    private void verificationLoop(String keyword) {
        Thread owner = Thread.currentThread();
        try (WakeKeywordVerifier verifier = new WakeKeywordVerifier(this, keyword)) {
            while (!cancelled && verifiedTakes < 3) {
                synchronized (takeSignal) {
                    while (!takeRequested && !cancelled) takeSignal.wait();
                    if (cancelled) break;
                    takeRequested = false;
                }
                postStatus(owner, getString(R.string.wake_recording_take, verifiedTakes + 1), false);
                short[] take;
                try { take = captureTake(); }
                catch (SecurityException e) { postStatus(owner, getString(R.string.wake_permission_denied), true); continue; }
                catch (IOException e) { postStatus(owner, getString(R.string.wake_microphone_error), true); continue; }
                if (take == null) {
                    if (!cancelled) postStatus(owner, getString(R.string.wake_record_timeout), true);
                    continue;
                }
                WakeKeywordVerifier.Result result;
                try { result = verifier.verify(take); }
                catch (RuntimeException | LinkageError e) { postStatus(owner, getString(R.string.wake_recognizer_error), false); break; }
                finally { Arrays.fill(take, (short) 0); }
                if (result == WakeKeywordVerifier.Result.MATCH) {
                    verifiedTakes++;
                    if (verifiedTakes == 3) {
                        new WakeSettings(this).markKeywordVerified();
                        postStatus(owner, getString(R.string.wake_enroll_complete), false);
                    } else postStatus(owner, getString(R.string.wake_take_saved, verifiedTakes), true);
                } else postStatus(owner, getString(R.string.wake_keyword_not_recognized, verifiedTakes + 1), true);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException | RuntimeException | LinkageError e) { if (!cancelled) postStatus(owner, getString(R.string.wake_recognizer_error), false); }
        finally {
            runOnUiThread(() -> {
                if (task != owner) return;
                task = null; takeRequested = false;
                if (cancelled && visible) status.setText(R.string.wake_record_cancelled);
                updateButtons();
            });
        }
    }

    private short[] captureTake() throws IOException, InterruptedException {
        short[] frame = new short[EnergyVad.FRAME_SAMPLES];
        try {
            WakeMicrophone opened = null;
            for (int i = 0; i < 5 && !cancelled; i++) {
                try { opened = WakeMicrophone.open(this); break; }
                catch (IOException e) { if (i == 4) throw e; Thread.sleep(100); }
            }
            if (opened == null) return null;
            microphone = opened;
            try {
                EnergyVad vad = new EnergyVad(); long deadline = SystemClock.elapsedRealtime() + 8000;
                while (!cancelled && SystemClock.elapsedRealtime() < deadline && opened.readFrame(frame)) {
                    short[] take = vad.accept(frame); if (take != null) return take;
                }
            }
            finally { opened.close(); microphone = null; }
            return null;
        } finally { Arrays.fill(frame, (short) 0); }
    }

    private void postStatus(Thread owner, String text, boolean allowNextTake) {
        runOnUiThread(() -> {
            if (task != owner || !visible) return;
            status.setText(text);
            if (allowNextTake) updateButtons();
        });
    }

    private void cancelVerification(boolean showStatus) {
        cancelled = true;
        synchronized (takeSignal) { takeRequested = true; takeSignal.notifyAll(); }
        WakeMicrophone active = microphone; if (active != null) active.cancel();
        if (showStatus && visible) status.setText(R.string.wake_record_cancelled);
        updateButtons();
    }

    private void enable() {
        WakeSettings settings = new WakeSettings(this);
        if (!settings.isKeywordVerified()) { status.setText(R.string.wake_enable_requires_verification); return; }
        settings.setEnabled(true);
        try {
            startForegroundService(new Intent(this, WakeForegroundService.class).setAction(WakeForegroundService.ACTION_START));
            status.setText(R.string.wake_start_requested);
        } catch (RuntimeException e) { settings.setEnabled(false); status.setText(R.string.wake_start_failed); }
        updateButtons();
    }

    private void stopWake() {
        cancelVerification(false);
        new WakeSettings(this).setEnabled(false);
        stopService(new Intent(this, WakeForegroundService.class));
        status.setText(R.string.wake_stopped); updateButtons();
    }

    private void restartWakeService(WakeSettings settings) {
        stopService(new Intent(this, WakeForegroundService.class));
        try { startForegroundService(new Intent(this, WakeForegroundService.class).setAction(WakeForegroundService.ACTION_START)); }
        catch (RuntimeException e) { settings.setEnabled(false); status.setText(R.string.wake_start_failed); }
    }

    private void updateButtons() {
        if (record == null) return;
        boolean active = task != null;
        keywordInput.setEnabled(!active); save.setEnabled(!active);
        sensitivity.setEnabled(!active);
        record.setEnabled(visible && (!active || (!takeRequested && microphone == null && !cancelled)));
        cancel.setEnabled(active && !cancelled);
        WakeSettings settings = new WakeSettings(this);
        start.setEnabled(!active && settings.isKeywordVerified() && !settings.isEnabled());
        stop.setEnabled(!active && settings.isEnabled());
    }
}
