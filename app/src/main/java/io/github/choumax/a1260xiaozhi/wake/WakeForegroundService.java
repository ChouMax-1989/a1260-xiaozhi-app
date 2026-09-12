package io.github.choumax.a1260xiaozhi.wake;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import io.github.choumax.a1260xiaozhi.R;
import java.io.IOException;
import java.util.Arrays;

/** Explicit opt-in foreground capture. NOT_STICKY; never records without a visible notification. */
public final class WakeForegroundService extends Service {
    public static final String ACTION_START = "io.github.choumax.a1260xiaozhi.wake.START";
    public static final String ACTION_STOP = "io.github.choumax.a1260xiaozhi.wake.STOP";
    public static final String ACTION_WAKE_DETECTED = "io.github.choumax.a1260xiaozhi.wake.DETECTED";
    public static final String ACTION_WAKE_STATUS = "io.github.choumax.a1260xiaozhi.wake.STATUS";
    public static final String EXTRA_SCORE = "score", EXTRA_DISTANCE = "distance", EXTRA_STATUS = "status";
    public static final String STATUS_LISTENING = "listening", STATUS_STOPPED = "stopped";
    public static final String ERROR_PERMISSION = "permission_required", ERROR_NO_TEMPLATES = "no_templates";
    public static final String ERROR_TEMPLATES = "invalid_templates", ERROR_MICROPHONE = "microphone_unavailable";
    public static final String ERROR_NOTIFICATION = "notification_disabled";
    private static final String CHANNEL = "local_wake_microphone";
    private static final String EXTRA_ORIGIN = "wake_origin";
    private static final String PROCESS_ORIGIN = java.util.UUID.randomUUID().toString();
    private static final int NOTIFICATION = 4261;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;
    private volatile WakeMicrophone microphone;
    private Thread worker;
    private boolean destroyed;
    private static final java.util.ArrayDeque<String> RECENT_MATCHES = new java.util.ArrayDeque<>();
    private static volatile String captureSnapshot = "not_started";
    public static synchronized String diagnosticHistory() { return captureSnapshot + " recent=" + RECENT_MATCHES.toString(); }
    private static synchronized void recordMatch(String value) {
        if (RECENT_MATCHES.size() >= 12) RECENT_MATCHES.removeFirst();
        RECENT_MATCHES.addLast(android.os.SystemClock.elapsedRealtime() + ":" + value);
    }
    private volatile long framesRead, decodeNanos, loadMillis;
    private volatile float activeThreshold;
    private volatile String captureStatus = "starting";

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        super.dump(fd, writer, args);
        writer.println("capture=" + captureStatus + " workerAlive=" + (worker != null && worker.isAlive())
                + " engine=sherpa-onnx-1.13.7 frames=" + framesRead + " loadMs=" + loadMillis
                + " inferenceMs=" + decodeNanos / 1000000
                + " keywordThreshold=" + activeThreshold);
    }


    /** Host receivers must reject spoofed broadcasts from other applications. Same app process only. */
    public static boolean isAuthenticWake(Intent intent) {
        return intent != null && ACTION_WAKE_DETECTED.equals(intent.getAction())
                && PROCESS_ORIGIN.equals(intent.getStringExtra(EXTRA_ORIGIN));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            new WakeSettings(this).setEnabled(false); stopCapture(); broadcastStatus(STATUS_STOPPED); stopSelf(); return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) { stopSelf(startId); return START_NOT_STICKY; }
        if (worker != null) return START_NOT_STICKY;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.wake_notification_channel), NotificationManager.IMPORTANCE_LOW));
        try {
            startForeground(NOTIFICATION, notification(getString(R.string.wake_notification_listening), true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (RuntimeException e) { fail(ERROR_PERMISSION, R.string.wake_start_failed); return START_NOT_STICKY; }
        if (!notificationVisible()) { fail(ERROR_NOTIFICATION, R.string.wake_notification_disabled); return START_NOT_STICKY; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(ERROR_PERMISSION, R.string.wake_permission_denied); return START_NOT_STICKY;
        }
        if (!new WakeSettings(this).isKeywordVerified()) { fail(ERROR_NO_TEMPLATES, R.string.wake_no_templates); return START_NOT_STICKY; }
        new WakeSettings(this).setEnabled(true); cancelled = false;
        worker = new Thread(this::listen, "local-wake"); worker.start();
        return START_NOT_STICKY;
    }

    private void listen() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        boolean hit = false;
        boolean reload = false;
        String error = null; int errorText = R.string.wake_microphone_error;
        short[] frame = new short[EnergyVad.FRAME_SAMPLES];
        try {
            captureStatus = "loading_model";
            long loadStart = android.os.SystemClock.elapsedRealtime();
            WakeKeywordVerifier recognizer = WakeKeywordVerifier.acquireListening(this, new WakeSettings(this).keyword());
            activeThreshold = recognizer.threshold;
            boolean healthy = false;
            try {
                loadMillis = android.os.SystemClock.elapsedRealtime() - loadStart;
                if (cancelled) return;
                WakeMicrophone opened = WakeMicrophone.open(this); microphone = opened; captureStatus = "listening";
                try {
                main.post(() -> { if (!cancelled && !destroyed) broadcastStatus(STATUS_LISTENING); });
                int visibilityFrames = 0;
                while (!cancelled && opened.readFrame(frame)) {
                    framesRead++;
                    if (++visibilityFrames >= 50) {
                        visibilityFrames = 0;
                        captureSnapshot = "engine=sherpa frames=" + framesRead + " loadMs=" + loadMillis + " inferenceMs=" + decodeNanos / 1000000;
                        if (!notificationVisible()) { error = ERROR_NOTIFICATION; errorText = R.string.wake_notification_disabled; break; }
                        if (Float.compare(recognizer.threshold, new WakeSettings(this).keywordThreshold()) != 0) {
                            reload = true; break;
                        }
                    }
                    long before = System.nanoTime();
                    hit = recognizer.acceptFrame(frame);
                    decodeNanos += System.nanoTime() - before;
                    if (hit) { recordMatch("kws_hit frames=" + framesRead); break; }
                }
                } finally { opened.close(); microphone = null; }
                healthy = true;
            } finally {
                if (healthy) WakeKeywordVerifier.recycleListening(this, recognizer);
                else recognizer.close();
            }
        } catch (SecurityException e) { error = ERROR_PERMISSION; errorText = R.string.wake_permission_denied; }
        catch (IOException | RuntimeException | LinkageError e) { error = ERROR_TEMPLATES; errorText = R.string.wake_invalid_templates; }
        finally {
            Arrays.fill(frame, (short) 0);
            boolean result = hit; boolean reloadRequested = reload; String finalError = error; int finalText = errorText;
            captureStatus = error != null ? error : hit ? "matched" : "stopped";
            main.post(() -> {
                worker = null;
                if (destroyed || cancelled) return;
                if (finalError != null) { fail(finalError, finalText); return; }
                if (reloadRequested && new WakeSettings(this).isEnabled()) {
                    // The old microphone is already closed; keep FGS ownership while replacing the model.
                    onStartCommand(new Intent(this, WakeForegroundService.class).setAction(ACTION_START), 0, 0);
                    return;
                }
                // AudioRecord has been released before the host receives DETECTED.
                if (result) {
                    Intent event = new Intent(ACTION_WAKE_DETECTED).setPackage(getPackageName());
                    event.putExtra(EXTRA_ORIGIN, PROCESS_ORIGIN);
                    sendBroadcast(event);
                    NotificationManager manager = getSystemService(NotificationManager.class);
                    Intent open = new Intent(this, io.github.choumax.a1260xiaozhi.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    PendingIntent pending = PendingIntent.getActivity(this, 42, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    manager.notify(4262, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_wake_mic).setContentTitle("小智已听到唤醒词").setContentText("点击打开聊天并开始对话").setContentIntent(pending).setAutoCancel(true).build());
                }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            });
        }
    }

    private Notification notification(String text, boolean listening) {
        PendingIntent settings = PendingIntent.getActivity(this, 0, new Intent(this, WakeEnrollActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_wake_mic)
                .setContentTitle(getString(R.string.wake_notification_title)).setContentText(text).setContentIntent(settings)
                .setOngoing(listening).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);
        if (listening) {
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, WakeForegroundService.class).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(R.drawable.ic_wake_mic, getString(R.string.wake_stop), stop).build());
        }
        return builder.build();
    }

    private void fail(String status, int text) {
        new WakeSettings(this).setEnabled(false); stopCapture(); broadcastStatus(status);
        stopForeground(STOP_FOREGROUND_REMOVE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        // Also visible when no activity is receiving the status broadcast.
        manager.notify(NOTIFICATION, notification(getString(text), false)); stopSelf();
    }
    private void broadcastStatus(String status) { sendBroadcast(new Intent(ACTION_WAKE_STATUS).setPackage(getPackageName()).putExtra(EXTRA_STATUS, status)); }
    private boolean notificationVisible() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }
    private void stopCapture() { cancelled = true; WakeMicrophone active = microphone; if (active != null) active.cancel(); }
    @Override public void onDestroy() { destroyed = true; stopCapture(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy(); }
}
