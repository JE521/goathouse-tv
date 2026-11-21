package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * goathouse: keeps this TV registered with Music Assistant as a Sendspin player, and brings up Now Playing when
 * music starts. Runs as a quiet foreground service so Android keeps the connection alive.
 */
public final class SendspinService extends Service implements SendspinClient.Listener {
    private static final String TAG = "Sendspin";
    private static final String CHANNEL = "sendspin";
    static final String TAKEOVER_KEY = "sendspin_takeover";   // launcher settings: open Now Playing when music starts

    interface Ui {
        void onState();
    }

    static volatile SendspinService live;
    private static final CopyOnWriteArrayList<Ui> UIS = new CopyOnWriteArrayList<>();

    private SendspinClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    volatile JSONObject metadata;
    volatile String playbackState = "stopped";
    volatile boolean connected;
    private long metadataAtUs;   // client clock when metadata arrived (for progress)

    static void startIfConfigured(Context ctx) {
        if (sendspinUrl(ctx) == null) return;
        try {
            ctx.startForegroundService(new Intent(ctx, SendspinService.class));
        } catch (Exception e) {
            Log.w(TAG, "start: " + e);
        }
    }

    static void addUi(Ui ui) { UIS.add(ui); }

    static void removeUi(Ui ui) { UIS.remove(ui); }

    /** ws://<music assistant host>:8927/sendspin from the Accounts address, or null when not set up. */
    static String sendspinUrl(Context ctx) {
        String ma = Accounts.of(ctx).url(Accounts.MA_URL);
        if (ma.isEmpty()) return null;
        try {
            String host = Uri.parse(ma).getHost();
            return host == null ? null : "ws://" + host + ":8927/sendspin";
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Music Assistant player", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(playerName(this))
                .setContentText("Ready for Music Assistant")
                .setOngoing(true)
                .build();
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(41, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String url = sendspinUrl(this);
        if (url == null) { stopSelf(); return START_NOT_STICKY; }
        if (client == null) {
            SharedPreferences p = getSharedPreferences("sendspin", MODE_PRIVATE);
            try {
                client = new SendspinClient(url, playerName(this), staticKey(p), p.getInt("volume", 100), p.getBoolean("muted", false), this);
                client.start();
            } catch (Exception e) {
                Log.w(TAG, "client: " + e);
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (client != null) {
            getSharedPreferences("sendspin", MODE_PRIVATE).edit()
                    .putInt("volume", client.volume()).putBoolean("muted", client.muted()).apply();
            client.stop();
        }
        live = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    static String playerName(Context ctx) {
        String custom = Accounts.of(ctx).get(Accounts.PLAYER_NAME);
        if (!custom.isEmpty()) return custom;
        String dev = Settings.Global.getString(ctx.getContentResolver(), "device_name");
        return dev == null || dev.isEmpty() ? "Android TV" : dev;
    }

    /** This TV's Sendspin identity: a Curve25519 private key made once and kept (client_id = its public key). */
    private static byte[] staticKey(SharedPreferences p) throws Exception {
        String k = p.getString("noise_priv", "");
        if (k.isEmpty()) {
            byte[][] kp = Noise.generateKeyPair();
            k = Base64.encodeToString(kp[0], Base64.NO_WRAP);
            p.edit().putString("noise_priv", k).apply();
        }
        return Base64.decode(k, Base64.NO_WRAP);
    }

    private static volatile String haEntity;   // this TV's Music Assistant player in Home Assistant
    private static volatile long haEntityAt;

    static void forgetEntity() { haEntity = null; }

    /**
     * Plays a playlist or album on this TV through Home Assistant's Music Assistant integration (the TV's own HA
     * login). Runs off the main thread; false when Home Assistant / Music Assistant aren't set up or it failed.
     */
    static boolean playOnThisTv(Context ctx, String mediaType, String name, String artist) {
        Accounts acc = Accounts.of(ctx);
        if (!acc.hasHomeAssistant() || live == null || !live.connected) return false;
        try {
            String entity = haEntity != null ? haEntity : findEntity(ctx, acc);
            if (entity == null) return false;
            haEntity = entity;
            JSONObject body = new JSONObject().put("entity_id", entity).put("media_id", name).put("media_type", mediaType);
            if (artist != null && !artist.isEmpty()) body.put("artist", artist);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                    acc.url(Accounts.HA_URL) + "/api/services/music_assistant/play_media").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(4000);
            c.setRequestProperty("Authorization", "Bearer " + acc.get(Accounts.HA_TOKEN));
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            try (java.io.OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes("UTF-8")); }
            int code;
            try {
                code = c.getResponseCode();
            } catch (java.net.SocketTimeoutException slow) {
                code = 200;   // HA holds the call open while Music Assistant starts the queue: the request went through
            }
            c.disconnect();
            if (code != 200) haEntity = null;
            return code == 200;
        } catch (Exception e) {
            Log.w(TAG, "play on tv: " + e);
            return false;
        }
    }

    /** This TV's Music Assistant player entity in Home Assistant (cached), or null. Network: call off the main thread. */
    static String thisTvEntity(Context ctx) {
        if (haEntity != null && System.currentTimeMillis() - haEntityAt < 10 * 60_000L) return haEntity;   // renames are picked up
        haEntityAt = System.currentTimeMillis();
        try {
            haEntity = findEntity(ctx, Accounts.of(ctx));
        } catch (Exception e) {
            Log.w(TAG, "entity: " + e);
        }
        return haEntity;
    }

    private static String findEntity(Context ctx, Accounts acc) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(acc.url(Accounts.HA_URL) + "/api/states").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + acc.get(Accounts.HA_TOKEN));
        String body;
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            body = b.toString("UTF-8");
        }
        String name = playerName(ctx);
        org.json.JSONArray states = new org.json.JSONArray(body);
        java.util.List<JSONObject> matches = new java.util.ArrayList<>();
        for (int i = 0; i < states.length(); i++) {
            JSONObject s = states.getJSONObject(i);
            if (!s.optString("entity_id").startsWith("media_player.")) continue;
            if ("unavailable".equals(s.optString("state"))) continue;   // stale entities from an old identity
            JSONObject a = s.optJSONObject("attributes");
            if (a == null || !"music_assistant".equals(a.optString("app_id")) && !a.has("mass_player_type")) continue;
            String fn = a.optString("friendly_name");
            if (fn.equalsIgnoreCase(name) || fn.equalsIgnoreCase(name + " player")) matches.add(s);
        }
        if (matches.size() <= 1) return matches.isEmpty() ? null : matches.get(0).optString("entity_id");
        // Another speaker has the same name (a Roku TV in the same room, say): ask each to keep its volume where it
        // is. Only our own player passes that on to this TV, so we learn which one is us without anything audible.
        for (JSONObject s : matches) {
            String entity = s.optString("entity_id");
            double vol = s.optJSONObject("attributes").optDouble("volume_level", -1);
            if (vol < 0) continue;
            long before = System.currentTimeMillis();
            try {
                java.net.HttpURLConnection p = (java.net.HttpURLConnection) new java.net.URL(acc.url(Accounts.HA_URL)
                        + "/api/services/media_player/volume_set").openConnection();
                p.setConnectTimeout(5000);
                p.setReadTimeout(10000);
                p.setRequestMethod("POST");
                p.setDoOutput(true);
                p.setRequestProperty("Authorization", "Bearer " + acc.get(Accounts.HA_TOKEN));
                p.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream o = p.getOutputStream()) {
                    o.write(new JSONObject().put("entity_id", entity).put("volume_level", vol).toString().getBytes("UTF-8"));
                }
                p.getResponseCode();
            } catch (Exception e) {
                Log.w(TAG, "probe " + entity + ": " + e);
                continue;
            }
            for (int w = 0; w < 20; w++) {
                if (SendspinClient.lastVolumeCommandAt >= before) {
                    Log.i(TAG, "this TV is " + entity + " (" + matches.size() + " players named " + name + ")");
                    return entity;
                }
                Thread.sleep(100);
            }
        }
        Log.w(TAG, matches.size() + " players are named " + name + " and none answered: rename this TV in Settings → Music");
        return null;
    }

    void command(String c) { if (client != null) new Thread(() -> client.command(c)).start(); }

    /** Current track position in ms, extrapolated from the last metadata (spec metadata progress). */
    long positionMs() {
        JSONObject md = metadata;
        JSONObject pr = md == null ? null : md.optJSONObject("progress");
        if (pr == null) return -1;
        long base = pr.optLong("track_progress");
        int speed = pr.optInt("playback_speed", 1000);
        long elapsedMs = (TimeFilter.nowUs() - metadataAtUs) / 1000;
        long pos = base + elapsedMs * speed / 1000;
        long dur = pr.optLong("track_duration");
        return dur > 0 ? Math.min(pos, dur) : pos;
    }

    long durationMs() {
        JSONObject md = metadata;
        JSONObject pr = md == null ? null : md.optJSONObject("progress");
        return pr == null ? 0 : pr.optLong("track_duration");
    }

    private void notifyUi() { main.post(() -> { for (Ui u : UIS) u.onState(); }); }

    // ---------- client events ----------

    @Override public void onConnection(boolean c) { connected = c; notifyUi(); }

    @Override public void onMetadata(JSONObject md) {
        metadata = md;
        metadataAtUs = TimeFilter.nowUs();
        notifyUi();
    }

    @Override public void onPlaybackState(String state) { playbackState = state; notifyUi(); }

    @Override public void onAudioStarted() {
        playbackState = "playing";
        notifyUi();
        boolean takeover = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getBoolean(TAKEOVER_KEY, true);
        if (takeover && !NowPlayingActivity.visible && !MusicActivity.visible) main.post(() -> {
            try {
                startActivity(new Intent(this, NowPlayingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.w(TAG, "now playing: " + e);
            }
        });
    }

    @Override public void onStreamEnded() { notifyUi(); }
}
