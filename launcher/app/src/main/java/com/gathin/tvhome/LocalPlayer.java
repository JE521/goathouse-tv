package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * goathouse: plays Navidrome tracks on the TV itself. Keeps playing when you leave the Music screen (a small
 * foreground service keeps the app alive), answers media keys from anywhere (a MediaSession), steps aside for other
 * apps (audio focus), and buffers the next track so albums play gaplessly.
 */
final class LocalPlayer {
    private static final String TAG = "LocalPlayer";
    static final String QUALITY_PREF = "nd_quality";   // "0" = original file, else max kbps (transcoded to mp3)
    static final String[] QUALITY_VALUES = {"0", "320", "192", "128"};
    static final String[] QUALITY_LABELS = {"Original", "320 kbps", "192 kbps", "128 kbps"};

    private static final LocalPlayer INSTANCE = new LocalPlayer();
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> queue = new ArrayList<>();
    private Context app;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private MediaPlayer mp, nextMp;
    private boolean prepared, nextPrepared, resumeOnFocus;
    private int index = -1;

    static LocalPlayer get() { return INSTANCE; }

    synchronized void attach(Context ctx) {
        if (app != null) return;
        app = ctx.getApplicationContext();
        audio = app.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(ATTRS)
                .setOnAudioFocusChangeListener(this::onFocus, main).build();
        session = new MediaSession(app, "goathouse-music");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onStop() { stop(); }
            @Override public void onSeekTo(long pos) { seek(pos); }
            @Override public void onSkipToQueueItem(long id) { jump((int) id); }
        }, main);
    }

    // ---------- queue ----------

    synchronized void play(List<JSONObject> tracks) {
        queue.clear();
        queue.addAll(tracks);
        start(0);
    }

    synchronized void jump(int i) { if (i >= 0 && i < queue.size()) start(i); }

    /** The queue from the current track on, for the Up Next tab. Each entry gets "queueIndex". */
    synchronized List<JSONObject> upNext() {
        List<JSONObject> out = new ArrayList<>();
        for (int i = Math.max(index, 0); i < queue.size(); i++) {
            try { out.add(new JSONObject(queue.get(i).toString()).put("queueIndex", i).put("current", i == index)); }
            catch (Exception ignored) { }
        }
        return out;
    }

    // ---------- playback ----------

    private String url(int i) {
        String u = queue.get(i).optString("url");
        String q = app == null ? "0" : app.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(QUALITY_PREF, "0");
        return "0".equals(q) ? u : u + "&maxBitRate=" + q + "&format=mp3";
    }

    private MediaPlayer newPlayer(int i) throws Exception {
        MediaPlayer p = new MediaPlayer();
        p.setAudioAttributes(ATTRS);
        p.setDataSource(url(i));
        String scrobble = queue.get(i).optString("scrobble");
        p.setOnPreparedListener(x -> onPrepared(x, scrobble));
        p.setOnCompletionListener(x -> { ping(scrobble, true); advance(x); });   // counts as played
        p.setOnErrorListener((x, what, extra) -> { onError(x); return true; });
        return p;
    }

    private synchronized void start(int i) {
        if (i < 0 || i >= queue.size()) { stop(); return; }
        index = i;
        releaseAll();
        try {
            mp = newPlayer(i);
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "start: " + e);
            mp = null;
        }
        publish();
        PlaybackService.start(app);
    }

    private synchronized void onPrepared(MediaPlayer x, String scrobble) {
        if (x == nextMp) {   // the buffered next track: chain it so it starts the moment this one ends
            nextPrepared = true;
            if (mp != null && prepared) try { mp.setNextMediaPlayer(x); } catch (Exception e) { Log.w(TAG, "chain: " + e); }
            return;
        }
        if (x != mp) { x.release(); return; }
        prepared = true;
        if (requestFocus()) {
            x.start();
            ping(scrobble, false);   // "now playing"
        }
        publish();
        prepareNext();
    }

    private synchronized void prepareNext() {
        releaseNext();
        if (index + 1 >= queue.size()) return;
        try {
            nextMp = newPlayer(index + 1);
            nextMp.prepareAsync();
        } catch (Exception e) {
            nextMp = null;
        }
    }

    private synchronized void advance(MediaPlayer finished) {
        if (finished != mp) return;
        if (nextMp != null && nextPrepared) {   // the framework already started it: just move along
            MediaPlayer old = mp;
            mp = nextMp;
            nextMp = null;
            nextPrepared = false;
            prepared = true;
            index++;
            old.release();
            ping(queue.get(index).optString("scrobble"), false);
            publish();
            prepareNext();
        } else {
            next();
        }
    }

    private synchronized void onError(MediaPlayer x) {
        if (x == nextMp) { releaseNext(); return; }
        if (x == mp) next();
    }

    synchronized void toggle() {
        if (mp == null || !prepared) return;
        if (mp.isPlaying()) pause(); else resume();
    }

    synchronized void pause() {
        resumeOnFocus = false;
        if (mp != null && prepared && mp.isPlaying()) mp.pause();
        publish();
    }

    synchronized void resume() {
        if (mp == null || !prepared || mp.isPlaying()) return;
        if (!requestFocus()) return;
        SendspinService s = SendspinService.live;
        if (s != null && "playing".equals(s.playbackState)) s.command("pause");   // one player at a time
        mp.start();
        publish();
    }

    synchronized void next() { if (index + 1 < queue.size()) start(index + 1); else stop(); }

    synchronized void previous() {
        if (mp != null && prepared && mp.getCurrentPosition() > 3000) { mp.seekTo(0); publish(); }
        else if (index > 0) start(index - 1);
    }

    synchronized void seek(long pos) { if (mp != null && prepared) { mp.seekTo((int) pos); publish(); } }

    synchronized void stop() {
        releaseAll();
        index = -1;
        if (audio != null) audio.abandonAudioFocusRequest(focus);
        publish();
        PlaybackService.stop(app);
    }

    private void releaseAll() {
        releaseNext();
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        prepared = false;
    }

    private void releaseNext() {
        if (nextMp != null) {
            try { nextMp.release(); } catch (Exception ignored) { }
            nextMp = null;
        }
        nextPrepared = false;
    }

    // ---------- audio focus ----------

    private boolean requestFocus() {
        return audio == null || audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private synchronized void onFocus(int change) {
        if (mp == null || !prepared) return;
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:   // another app took over (Jellyfin, YouTube…)
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (mp.isPlaying()) { mp.pause(); resumeOnFocus = true; publish(); }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                mp.setVolume(0.25f, 0.25f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                mp.setVolume(1f, 1f);
                if (resumeOnFocus) { resumeOnFocus = false; mp.start(); publish(); }
                break;
            default:
                break;
        }
    }

    // ---------- session + state ----------

    private void publish() {
        if (session == null) return;
        JSONObject t = index >= 0 && index < queue.size() ? queue.get(index) : null;
        if (t == null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build());
            session.setActive(false);
            return;
        }
        boolean playing = mp != null && prepared && mp.isPlaying();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.optString("title"))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.optString("artist"))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.optString("album"))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, mp != null && prepared ? mp.getDuration() : 0)
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM)
                .setState(mp == null || !prepared ? PlaybackState.STATE_BUFFERING
                                : playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        mp != null && prepared ? mp.getCurrentPosition() : 0, playing ? 1f : 0f)
                .setActiveQueueItemId(index)
                .build());
        session.setActive(true);
        PlaybackService.update(app, t.optString("title"), t.optString("artist"));
    }

    private static void ping(String scrobble, boolean submission) {
        if (scrobble == null || scrobble.isEmpty()) return;
        new Thread(() -> {
            try { MusicSource.http(scrobble + "&submission=" + submission, null, null, null); } catch (Exception ignored) { }
        }).start();
    }

    synchronized JSONObject state() {
        if (index < 0 || index >= queue.size()) return null;
        JSONObject t = queue.get(index);
        try {
            boolean ready = mp != null && prepared;
            return new JSONObject().put("title", t.optString("title")).put("artist", t.optString("artist"))
                    .put("album", t.optString("album")).put("image", t.optString("image"))
                    .put("playing", ready && mp.isPlaying())
                    .put("positionMs", ready ? mp.getCurrentPosition() : 0)
                    .put("durationMs", ready ? mp.getDuration() : 0);
        } catch (Exception e) {
            return null;
        }
    }

    /** Keeps the app alive while there is something in the queue, with a quiet notification. */
    public static final class PlaybackService extends Service {
        private static final String CHANNEL = "music";
        private static volatile String title = "Music", artist = "";

        static void start(Context ctx) {
            if (ctx == null) return;
            try { ctx.startForegroundService(new Intent(ctx, PlaybackService.class)); }
            catch (Exception e) { Log.w(TAG, "service: " + e); }   // e.g. started from the background: playback still works
        }

        static void stop(Context ctx) {
            if (ctx != null) ctx.stopService(new Intent(ctx, PlaybackService.class));
        }

        static void update(Context ctx, String t, String a) {
            if (t.equals(title) && a.equals(artist)) return;
            title = t;
            artist = a;
            if (ctx != null) {
                NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                try { nm.notify(2, build(ctx)); } catch (Exception ignored) { }
            }
        }

        private static Notification build(Context ctx) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_MIN));
            return new Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_music)
                    .setContentTitle(title).setContentText(artist).setOngoing(true).build();
        }

        @Override public int onStartCommand(Intent intent, int flags, int startId) {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(2, build(this), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(2, build(this));
            }
            return START_NOT_STICKY;
        }

        @Override public IBinder onBind(Intent intent) { return null; }
    }
}
