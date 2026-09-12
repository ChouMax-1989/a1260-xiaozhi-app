package io.github.choumax.a1260xiaozhi.features;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import io.github.choumax.a1260xiaozhi.R;

/** Single-track SAF player; session and focus stay inactive until an explicit play command. */
public final class LocalMusicService extends Service {
    public static final String PLAY = "local_music.PLAY", PAUSE = "local_music.PAUSE", STOP = "local_music.STOP";
    private static final int NOTIFICATION_ID = 208;
    private static final String CHANNEL = "local_music";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private Uri selected;
    private String title = "";
    private boolean prepared, preparing, focused, foreground;
    private int stateText = R.string.music_no_track;
    private LrcLyrics lyrics = LrcLyrics.empty();
    private int selectionVersion;

    public final class LocalBinder extends Binder { public LocalMusicService service() { return LocalMusicService.this; } }

    @Override public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        session = new MediaSession(this, "A1260LocalMusic");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                try { startForegroundService(new Intent(LocalMusicService.this, LocalMusicService.class).setAction(PLAY)); }
                catch (RuntimeException denied) { fail(R.string.music_failed); }
            }
            @Override public void onPause() { pausePlayback(); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long position) { seekTo(position); }
        }, handler);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes()).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pausePlayback();
                }, handler).build();
        String stored = getSharedPreferences("local_music", MODE_PRIVATE).getString("uri", "");
        if (!stored.isEmpty()) {
            Uri candidate = Uri.parse(stored);
            if ("content".equals(candidate.getScheme())) {
                selected = candidate;
                title = getSharedPreferences("local_music", MODE_PRIVATE).getString("title", getString(R.string.music_local_audio));
                stateText = R.string.music_ready;
            }
        }
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.music_notification_channel), NotificationManager.IMPORTANCE_LOW));
        // Do not activate the session, request audio focus, or post a notification here.
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (PLAY.equals(action)) {
            // Required promptly even if a previously granted content URI has become unreadable.
            showForeground();
            play();
        } else if (PAUSE.equals(action)) pausePlayback();
        else if (STOP.equals(action)) stopPlayback();
        return START_NOT_STICKY;
    }

    public void selectTrack(Uri uri, String displayName) {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalArgumentException("Choose audio through the document picker");
        stopPlayback();
        selected = uri;
        selectionVersion++;
        lyrics = LrcLyrics.empty();
        title = displayName == null || displayName.isEmpty() ? getString(R.string.music_local_audio) : displayName.substring(0, Math.min(displayName.length(), 160));
        stateText = R.string.music_ready;
        getSharedPreferences("local_music", MODE_PRIVATE).edit().putString("uri", uri.toString()).putString("title", title).apply();
    }

    public String title() { return title; }
    public int stateText() { return stateText; }
    public boolean hasTrack() { return selected != null; }
    public int selectionVersion() { return selectionVersion; }
    public LrcLyrics lyrics() { return lyrics; }
    public void setLyrics(LrcLyrics value, int expectedSelection) { if (expectedSelection == selectionVersion) lyrics = value; }
    public boolean isPlaying() { try { return player != null && prepared && player.isPlaying(); } catch (IllegalStateException ignored) { return false; } }
    public int position() { try { return player != null && prepared ? player.getCurrentPosition() : 0; } catch (IllegalStateException ignored) { return 0; } }
    public int duration() { try { return player != null && prepared ? player.getDuration() : 0; } catch (IllegalStateException ignored) { return 0; } }

    private void play() {
        if (selected == null) { fail(R.string.music_no_track); return; }
        if (preparing || isPlaying()) return;
        if (player != null && prepared) { startPrepared(); return; }
        releasePlayer();
        MediaPlayer current = new MediaPlayer();
        player = current;
        preparing = true;
        stateText = R.string.music_preparing;
        try {
            current.setAudioAttributes(attributes());
            current.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            current.setDataSource(this, selected);
            current.setOnPreparedListener(ready -> {
                if (player != ready) return;
                preparing = false; prepared = true;
                startPrepared();
            });
            current.setOnCompletionListener(done -> {
                if (player != done) return;
                stopPlayback(); stateText = R.string.music_completed;
            });
            current.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) fail(R.string.music_failed);
                return true;
            });
            current.prepareAsync();
            handler.removeCallbacks(preparationTimeout);
            handler.postDelayed(preparationTimeout, 15000);
            updateSession(PlaybackState.STATE_BUFFERING);
            updateNotification();
        } catch (Exception failure) { fail(R.string.music_failed); }
    }

    private final Runnable preparationTimeout = () -> { if (preparing) fail(R.string.music_prepare_timeout); };

    private void startPrepared() {
        handler.removeCallbacks(preparationTimeout);
        if (audio == null || audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(R.string.music_focus_denied); return;
        }
        focused = true;
        try {
            showForeground();
            player.start();
            stateText = R.string.music_playing;
            session.setActive(true);
            session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
            updateSession(PlaybackState.STATE_PLAYING);
            updateNotification();
        } catch (RuntimeException failure) { fail(R.string.music_failed); }
    }

    public void pausePlayback() {
        if (preparing) { releasePlayer(); }
        else if (player != null && prepared) { try { player.pause(); } catch (IllegalStateException ignored) { releasePlayer(); } }
        abandonFocus();
        stateText = selected == null ? R.string.music_no_track : R.string.music_paused;
        updateSession(PlaybackState.STATE_PAUSED);
        if (foreground) { stopForeground(false); foreground = false; updateNotification(); }
    }

    public void stopPlayback() {
        releasePlayer();
        abandonFocus();
        if (session != null) { updateSession(PlaybackState.STATE_STOPPED); session.setActive(false); }
        stateText = selected == null ? R.string.music_no_track : R.string.music_stopped;
        stopForeground(true); foreground = false;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    public void seekTo(long position) {
        if (player != null && prepared) {
            try {
                player.seekTo((int) Math.max(0, Math.min(duration(), position)));
                updateSession(isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            } catch (IllegalStateException failure) { fail(R.string.music_failed); }
        }
    }

    private void fail(int reason) { stopPlayback(); stateText = reason; }
    private void abandonFocus() { if (focused && audio != null) audio.abandonAudioFocusRequest(focusRequest); focused = false; }
    private void releasePlayer() {
        handler.removeCallbacks(preparationTimeout);
        prepared = false; preparing = false;
        MediaPlayer previous = player; player = null;
        if (previous != null) { previous.setOnPreparedListener(null); previous.setOnErrorListener(null); previous.setOnCompletionListener(null); previous.release(); }
    }
    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }
    private void updateSession(int state) {
        if (session == null) return;
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
                .setState(state, position(), state == PlaybackState.STATE_PLAYING ? 1f : 0f, SystemClock.elapsedRealtime()).build());
    }
    private void showForeground() { startForeground(NOTIFICATION_ID, notification()); foreground = true; }
    private void updateNotification() { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification()); }
    private Notification notification() {
        PendingIntent content = PendingIntent.getActivity(this, 208, new Intent(this, LocalMusicActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean playing = isPlaying() || preparing;
        PendingIntent toggle = playing ? PendingIntent.getService(this, 209, new Intent(this, LocalMusicService.class).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getForegroundService(this, 210, new Intent(this, LocalMusicService.class).setAction(PLAY), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 211, new Intent(this, LocalMusicService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty() ? getString(R.string.music_local_audio) : title).setContentText(getString(stateText))
                .setContentIntent(content).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(playing)
                .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        getString(playing ? R.string.music_pause : R.string.music_play), toggle).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.music_stop), stop).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1)).build();
    }
    @Override public void onDestroy() {
        releasePlayer(); abandonFocus();
        if (session != null) { session.setActive(false); session.release(); }
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        super.onDestroy();
    }
}
