package io.github.choumax.a1260xiaozhi.features;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.choumax.a1260xiaozhi.R;

/** SAF-selected local music and bounded LRC parsing; no broad storage permission. */
public final class LocalMusicActivity extends Activity {
    private static final int PICK_AUDIO = 601, PICK_LRC = 602;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor loader = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    private LocalMusicService service;
    private TextView track, status, time, lyrics, importStatus;
    private SeekBar seek;
    private Button audioButton, lyricButton, playButton, pauseButton, stopButton;
    private boolean resumed, bound, dragging, importing;
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (resumed) { refresh(); handler.postDelayed(this, 250); } }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((LocalMusicService.LocalBinder) binder).service(); refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; refresh(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setTitle(R.string.music_title);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(22, 22, 22, 22); scroll.addView(root);
        label(root, R.string.music_help);
        track = label(root, R.string.music_no_track); track.setTextSize(22);
        status = label(root, R.string.music_no_track);
        audioButton = button(root, R.string.music_select_audio, () -> choose(PICK_AUDIO, "audio/*"));
        lyricButton = button(root, R.string.music_select_lyrics, () -> choose(PICK_LRC, "*/*"));
        importStatus = label(root, R.string.music_lyrics_help);
        playButton = button(root, R.string.music_play, () -> {
            if (service != null && service.hasTrack()) {
                try { startForegroundService(new Intent(this, LocalMusicService.class).setAction(LocalMusicService.PLAY)); }
                catch (RuntimeException failure) { status.setText(R.string.music_failed); }
            }
        });
        pauseButton = button(root, R.string.music_pause, () -> { if (service != null) service.pausePlayback(); refresh(); });
        stopButton = button(root, R.string.music_stop, () -> { if (service != null) service.stopPlayback(); refresh(); });
        time = label(root, R.string.music_zero_time);
        seek = new SeekBar(this); seek.setContentDescription(getString(R.string.music_seek)); root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { if (service != null) service.seekTo(bar.getProgress()); dragging = false; }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) { }
        });
        lyrics = label(root, R.string.music_no_lyrics); lyrics.setTextSize(22); lyrics.setTextIsSelectable(true);
        track.setId(R.id.music_track); status.setId(R.id.music_status); lyrics.setId(R.id.music_lyrics);
        audioButton.setId(R.id.music_select_audio); lyricButton.setId(R.id.music_select_lyrics);
        playButton.setId(R.id.music_play); pauseButton.setId(R.id.music_pause); stopButton.setId(R.id.music_stop); seek.setId(R.id.music_seek);
        button(root, R.string.feature_close, this::finish);
        setContentView(scroll);
        bound = bindService(new Intent(this, LocalMusicService.class), connection, Context.BIND_AUTO_CREATE);
        refresh();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; handler.post(tick); }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { loader.shutdownNow(); if (bound) unbindService(connection); bound = false; service = null; super.onDestroy(); }

    private void choose(int request, String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, request); }
        catch (RuntimeException unavailable) { importStatus.setText(R.string.music_picker_unavailable); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || service == null) return;
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { importStatus.setText(R.string.music_import_failed); return; }
        if (request == PICK_AUDIO) {
            try {
                if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                service.selectTrack(uri, displayName(uri));
                importStatus.setText(R.string.music_lyrics_help); refresh();
            } catch (RuntimeException failure) { importStatus.setText(R.string.music_import_failed); }
        } else if (request == PICK_LRC) loadLyrics(uri);
    }
    private void loadLyrics(Uri uri) {
        if (importing || service == null) return;
        importing = true; refresh(); importStatus.setText(R.string.music_lyrics_loading);
        LocalMusicService target = service;
        int selection = target.selectionVersion();
        loader.execute(() -> {
            LrcLyrics parsed = null;
            try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException("No readable document");
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || bytes.size() + n > LrcLyrics.MAX_CHARS) throw new IllegalArgumentException("LRC exceeds limit");
                    bytes.write(buffer, 0, n);
                }
                parsed = LrcLyrics.parse(decode(bytes.toByteArray()));
                if (parsed.lines.isEmpty()) parsed = null;
            } catch (Exception invalid) { parsed = null; }
            LrcLyrics ready = parsed;
            handler.post(() -> {
                importing = false;
                if (isDestroyed() || isFinishing()) return;
                if (service == target && target.selectionVersion() == selection && ready != null) {
                    target.setLyrics(ready, selection); importStatus.setText(getString(R.string.music_lyrics_loaded, ready.lines.size()));
                } else importStatus.setText(R.string.music_import_failed);
                refresh();
            });
        });
    }
    private static String decode(byte[] bytes) throws Exception {
        Charset charset = StandardCharsets.UTF_8;
        if (bytes.length > 1 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) charset = StandardCharsets.UTF_16;
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        return getString(R.string.music_local_audio);
    }
    private void refresh() {
        if (track == null) return;
        boolean ready = service != null;
        audioButton.setEnabled(ready && !importing); lyricButton.setEnabled(ready && service.hasTrack() && !importing);
        playButton.setEnabled(ready && service.hasTrack()); pauseButton.setEnabled(ready && service.hasTrack()); stopButton.setEnabled(ready && service.hasTrack());
        seek.setEnabled(ready && service.duration() > 0);
        if (!ready) return;
        track.setText(service.title().isEmpty() ? getString(R.string.music_no_track) : service.title()); status.setText(service.stateText());
        int position = service.position(), duration = service.duration();
        time.setText(String.format(Locale.ROOT, "%02d:%02d / %02d:%02d", position / 60000, position / 1000 % 60, duration / 60000, duration / 1000 % 60));
        if (!dragging) { seek.setMax(Math.max(1, duration)); seek.setProgress(position); }
        String current = service.lyrics().textAt(position);
        lyrics.setText(current.isEmpty() ? getString(service.lyrics().lines.isEmpty() ? R.string.music_no_lyrics : R.string.music_waiting_lyrics) : current);
    }
    private TextView label(LinearLayout root, int text) { TextView view = new TextView(this); view.setText(text); view.setTextSize(16); view.setPadding(0, 12, 0, 12); root.addView(view); return view; }
    private Button button(LinearLayout root, int text, Runnable callback) { Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density)); view.setOnClickListener(v -> callback.run()); root.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view; }
}
