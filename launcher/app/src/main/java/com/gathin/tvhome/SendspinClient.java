package com.gathin.tvhome;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * goathouse: a Sendspin player (github.com/Sendspin/spec) so Music Assistant can play on this TV. Client-initiated,
 * unencrypted transition wire (Music Assistant allows it on the LAN). Roles: player@v1 (16-bit PCM, scheduled by the
 * spec's time filter with sample drop/insert correction), metadata@v1 and controller@v1 for the Now Playing screen.
 */
final class SendspinClient {
    private static final String TAG = "Sendspin";

    /** When Music Assistant last sent this TV a volume command (SendspinService uses it to find its own player). */
    static volatile long lastVolumeCommandAt;

    interface Listener {
        void onConnection(boolean connected);
        void onMetadata(JSONObject metadata);       // server/state metadata object
        void onPlaybackState(String state);         // 'playing' | 'paused' | 'stopped'
        void onAudioStarted();                      // first audible chunk after a stream (re)start
        void onStreamEnded();
    }

    private final String url, name, clientId;
    private final byte[] staticPriv, staticPub;
    // Noise session (spec "Encryption"): set up per connection
    private volatile Noise.CipherState sendCs, recvCs;
    private volatile Noise.Responder handshake;
    private volatile String clientInitText;
    private java.io.ByteArrayOutputStream fragment;   // spec "Fragmentation" reassembly
    private int fragmentType;
    private final Listener listener;
    private final TimeFilter filter = new TimeFilter();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile MiniWebSocket ws;
    private volatile boolean running, stateSent, activated;
    private volatile int volume = 100;
    private volatile boolean muted;
    private final Player player = new Player();

    // clock-sync burst bookkeeping
    private int burstLeft;
    private long bestMeasurement, bestMaxError = Long.MAX_VALUE, bestTimeAdded;

    /** staticPriv: this TV's Curve25519 private key; client_id is its public key, base64url. */
    SendspinClient(String url, String name, byte[] staticPriv, int volume, boolean muted, Listener listener) throws Exception {
        this.url = url;
        this.name = name;
        this.staticPriv = staticPriv;
        this.staticPub = Noise.publicFromPrivate(staticPriv);
        this.clientId = b64u(staticPub);
        this.volume = volume;
        this.muted = muted;
        this.listener = listener;
    }

    void start() {
        running = true;
        player.start();
        timer.execute(this::connect);
    }

    void stop() {
        running = false;
        MiniWebSocket w = ws;
        if (w != null) {
            try { send(new JSONObject().put("type", "client/goodbye").put("payload", new JSONObject().put("reason", "shutdown"))); }
            catch (Exception ignored) { }
            w.close();
        }
        player.shutdown();
        timer.shutdownNow();
    }

    int volume() { return volume; }

    boolean muted() { return muted; }

    /** Controller commands from the Now Playing screen / remote: play, pause, next, previous… */
    void command(String command) {
        try {
            send(new JSONObject().put("type", "client/command").put("payload",
                    new JSONObject().put("controller", new JSONObject().put("command", command))));
        } catch (Exception e) {
            Log.w(TAG, "command " + command + ": " + e);
        }
    }

    // ---------- connection ----------

    private void connect() {
        if (!running) return;
        MiniWebSocket w = new MiniWebSocket();
        try {
            w.connect(url, new MiniWebSocket.Listener() {
                @Override public void onText(String text) { handle(text); }
                @Override public void onBinary(byte[] data, long arrivalUs) { binary(data, arrivalUs); }
                @Override public void onClosed(String why) { closed(why); }
            });
            ws = w;
            stateSent = false;
            filter.reset();
            sendCs = recvCs = null;
            activated = false;
            handshake = null;
            fragment = null;
            // Cleartext init; its exact bytes go into the Noise prologue.
            clientInitText = new JSONObject().put("type", "client/init").put("payload", new JSONObject()
                    .put("client_id", clientId).put("version", 1).put("suite", "25519_AESGCM_SHA256")).toString();
            w.sendText(clientInitText);
        } catch (Exception e) {
            Log.w(TAG, "connect " + url + ": " + e);
            w.close();
            retry();
        }
    }

    private void closed(String why) {
        Log.i(TAG, "closed: " + why);
        ws = null;
        player.clear(true);
        listener.onConnection(false);
        retry();
    }

    private void retry() {
        if (running && !timer.isShutdown()) timer.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    private JSONObject hello() throws Exception {
        JSONArray formats = new JSONArray()
                .put(new JSONObject().put("codec", "pcm").put("channels", 2).put("sample_rate", 48000).put("bit_depth", 16))
                .put(new JSONObject().put("codec", "pcm").put("channels", 2).put("sample_rate", 44100).put("bit_depth", 16));
        JSONObject payload = new JSONObject()
                .put("name", name)
                .put("device_info", new JSONObject().put("product_name", android.os.Build.MODEL)
                        .put("manufacturer", android.os.Build.MANUFACTURER).put("software_version", "goathouse-tv"))
                .put("supported_roles", new JSONArray().put("player@v1").put("metadata@v1").put("controller@v1"))
                .put("player@v1_support", new JSONObject().put("supported_formats", formats).put("buffer_capacity", 1_000_000))
                .put("unpaired_access", new JSONObject().put("enabled", true))
                .put("supported_pair_methods", new JSONObject());   // no pairing codes yet: unpaired, operator-approved
        return new JSONObject().put("type", "client/hello").put("payload", payload);
    }

    private void send(JSONObject msg) throws Exception {
        MiniWebSocket w = ws;
        if (w == null) return;
        Noise.CipherState cs = sendCs;
        if (cs == null) { w.sendText(msg.toString()); return; }   // only before the handshake (never in practice)
        byte[] json = msg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] plain = new byte[json.length + 1];
        plain[0] = 0;                                             // binary message type 0: JSON
        System.arraycopy(json, 0, plain, 1, json.length);
        byte[] ct;
        synchronized (cs) { ct = cs.encrypt(null, plain); }
        w.sendBinary(ct, ct.length);
    }

    private static String b64u(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private static byte[] unb64u(String s) {
        return android.util.Base64.decode(s, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    /** server/init then noise/handshake message 1: answer with message 2 (Sentinel PSK: unpaired). */
    private String serverInitText;

    private void handshakeText(String type, JSONObject p, String raw) throws Exception {
        if ("server/init".equals(type)) {
            serverInitText = raw;
            byte[] prologue = Noise.concat(clientInitText.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            handshake = new Noise.Responder(staticPriv, staticPub, unb64u(p.getString("server_id")), prologue);
        } else if ("noise/handshake".equals(type) && handshake != null) {
            byte[] payload = handshake.readMessage1(unb64u(p.getString("data")));
            JSONObject info = new JSONObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            Log.i(TAG, "noise: server uses psk category " + info.optString("psk_category"));
            // No pairing records on this TV yet: every lookup falls back to the Sentinel PSK (spec "Sentinel Fallback").
            byte[] msg2 = handshake.writeMessage2(Noise.SENTINEL_PSK, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ws.sendText(new JSONObject().put("type", "noise/handshake").put("payload", new JSONObject().put("data", b64u(msg2))).toString());
            recvCs = handshake.recv;
            sendCs = handshake.send;
            handshake = null;
        } else if ("server/error".equals(type)) {
            Log.w(TAG, "server/error: " + p.optString("reason"));
        }
    }

    // ---------- messages ----------

    private void handle(String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            JSONObject p = m.optJSONObject("payload");
            if (p == null) p = new JSONObject();
            if (recvCs == null) {                      // cleartext phase: init + Noise handshake only
                handshakeText(type, p, text);
                return;
            }
            switch (type) {
                case "server/hello":
                    Log.i(TAG, "connected to " + p.optString("name"));
                    send(hello());
                    break;
                case "server/activate":
                    Log.i(TAG, "activate: activities " + p.optJSONArray("activities") + " roles " + p.optJSONArray("active_roles"));
                    if (!activated) {
                        activated = true;
                        listener.onConnection(true);
                        startBurst();
                    }
                    break;
                case "server/time":
                    onServerTime(p);
                    break;
                case "stream/start": {
                    JSONObject pl = p.optJSONObject("player");
                    if (pl != null) player.configure(pl.optString("codec"), pl.optInt("sample_rate"), pl.optInt("channels"), pl.optInt("bit_depth"));
                    break;
                }
                case "stream/clear":
                    if (rolesInclude(p, "player")) player.clear(false);
                    break;
                case "stream/end":
                    if (!p.has("roles") || rolesInclude(p, "player")) {
                        player.clear(true);
                        listener.onStreamEnded();
                    }
                    break;
                case "server/command": {
                    JSONObject pl = p.optJSONObject("player");
                    if (pl == null) break;
                    if ("volume".equals(pl.optString("command"))) {
                        volume = Math.max(0, Math.min(100, pl.optInt("volume", volume)));
                        lastVolumeCommandAt = System.currentTimeMillis();
                    }
                    if ("mute".equals(pl.optString("command"))) muted = pl.optBoolean("mute", muted);
                    player.applyVolume();
                    sendState();
                    break;
                }
                case "server/state": {
                    JSONObject md = p.optJSONObject("metadata");
                    if (md != null) listener.onMetadata(md);
                    break;
                }
                case "group/update":
                    if (p.has("playback_state")) listener.onPlaybackState(p.optString("playback_state"));
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "message: " + e);
        }
    }

    private static boolean rolesInclude(JSONObject p, String role) {
        JSONArray roles = p.optJSONArray("roles");
        if (roles == null) return true;
        for (int i = 0; i < roles.length(); i++) if (roles.optString(i).startsWith(role)) return true;
        return false;
    }

    private void sendState() {
        try {
            JSONObject pl = new JSONObject().put("volume", volume).put("muted", muted).put("output_delay_ms", 0)
                    .put("required_lead_time_ms", 400).put("min_buffer_ms", 300)
                    .put("supported_commands", new JSONArray().put("volume").put("mute"));
            send(new JSONObject().put("type", "client/state").put("payload", new JSONObject().put("available", true).put("player", pl)));
            stateSent = true;
        } catch (Exception e) {
            Log.w(TAG, "state: " + e);
        }
    }

    // ---------- clock sync: bursts of 8, best (lowest max_error) fed to the filter ----------

    private synchronized void startBurst() {
        burstLeft = 8;
        bestMaxError = Long.MAX_VALUE;
        sendTime();
    }

    private void sendTime() {
        try {
            send(new JSONObject().put("type", "client/time").put("payload", new JSONObject().put("client_transmitted", TimeFilter.nowUs())));
        } catch (Exception ignored) { }
    }

    private synchronized void onServerTime(JSONObject p) {
        long t4 = TimeFilter.nowUs();
        long t1 = p.optLong("client_transmitted"), t2 = p.optLong("server_received"), t3 = p.optLong("server_transmitted");
        long measurement = ((t2 - t1) + (t3 - t4)) / 2;
        long maxError = ((t4 - t1) - (t3 - t2)) / 2;
        if (maxError > 0 && maxError < bestMaxError) {
            bestMaxError = maxError;
            bestMeasurement = measurement;
            bestTimeAdded = t4;
        }
        if (--burstLeft > 0) {
            sendTime();
            return;
        }
        if (bestMaxError != Long.MAX_VALUE) filter.update(bestMeasurement, bestMaxError, bestTimeAdded);
        if (!stateSent && filter.count() >= 1) sendState();
        // Quick bursts until the filter has settled, then every 5 s (spec: at most 10 s apart).
        long next = filter.count() < 5 ? 300 : 5000;
        timer.schedule(this::startBurst, next, TimeUnit.MILLISECONDS);
    }

    // ---------- audio ----------

    private void binary(byte[] wire, long arrivalUs) {
        Noise.CipherState cs = recvCs;
        if (cs == null) return;
        byte[] data;
        try {
            synchronized (cs) { data = cs.decrypt(null, wire); }
        } catch (Exception e) {                        // AEAD failure: silent close (spec "Failure Handling")
            Log.w(TAG, "decrypt failed: " + e);
            MiniWebSocket w = ws;
            if (w != null) w.close();
            return;
        }
        if (data.length == 0) return;
        int type = data[0] & 0xFF;
        if (type == 1) {                               // fragment: [1][flags][orig_type?][data]
            int flags = data[1] & 0xFF;
            boolean first = (flags & 2) != 0, last = (flags & 1) != 0;
            int start = first ? 3 : 2;
            if (first) { fragment = new java.io.ByteArrayOutputStream(); fragmentType = data[2] & 0xFF; }
            if (fragment == null) return;
            fragment.write(data, start, data.length - start);
            if (!last) return;
            byte[] body = fragment.toByteArray();
            fragment = null;
            data = new byte[body.length + 1];
            data[0] = (byte) fragmentType;
            System.arraycopy(body, 0, data, 1, body.length);
            type = fragmentType;
        }
        if (type == 0) {
            handle(new String(data, 1, data.length - 1, java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        if (type != 4) return;                         // only player audio chunks are used
        if (data.length < 13) { MiniWebSocket w = ws; if (w != null) w.close(); return; }
        long ts = 0;
        for (int i = 1; i <= 8; i++) ts = (ts << 8) | (data[i] & 0xFF);
        player.enqueue(ts, data);
    }

    /** Scheduled PCM output: each chunk starts at filter.clientTime(timestamp); small errors are corrected by
     *  dropping or repeating single frames (spec "Suggested correction strategy"), large ones by snapping. */
    private final class Player implements Runnable {
        private final LinkedBlockingQueue<Object[]> queue = new LinkedBlockingQueue<>();
        private volatile AudioTrack track;
        private volatile int rate, channels, frameBytes;
        private volatile boolean alive, started, announced;
        private long framesWritten;
        private Thread thread;
        private final AudioTimestamp stamp = new AudioTimestamp();
        private final Object lock = new Object();

        void start() {
            alive = true;
            thread = new Thread(this, "sendspin-audio");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }

        void shutdown() {
            alive = false;
            if (thread != null) thread.interrupt();
            release();
        }

        void configure(String codec, int sampleRate, int ch, int bits) {
            if (!"pcm".equals(codec) || bits != 16) { Log.w(TAG, "unsupported format " + codec + "/" + bits); return; }
            synchronized (lock) {
                if (track != null && sampleRate == rate && ch == channels) return;   // same format: keep going
                release();
                rate = sampleRate;
                channels = ch;
                frameBytes = 2 * ch;
                int min = AudioTrack.getMinBufferSize(rate, ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                                .setChannelMask(ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(min * 2, rate * frameBytes / 5))   // ~200 ms
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                applyVolume();
                started = false;
                announced = false;
                framesWritten = 0;
            }
        }

        void applyVolume() {
            AudioTrack t = track;
            if (t != null) t.setVolume(muted ? 0f : (float) Math.pow(volume / 100.0, 1.5));
        }

        void enqueue(long ts, byte[] data) { queue.offer(new Object[]{ts, data}); }

        /** stream/clear (keep the track) or stream/end / disconnect (stop it). */
        void clear(boolean end) {
            queue.clear();
            synchronized (lock) {
                AudioTrack t = track;
                if (t != null) {
                    try { t.pause(); t.flush(); } catch (Exception ignored) { }
                }
                started = false;
                announced = false;
                framesWritten = 0;
                if (end) release();
            }
        }

        private void release() {
            AudioTrack t = track;
            track = null;
            if (t != null) {
                try { t.stop(); } catch (Exception ignored) { }
                t.release();
            }
        }

        @Override public void run() {
            while (alive) {
                Object[] chunk;
                try { chunk = queue.poll(500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return; }
                if (chunk == null) continue;
                synchronized (lock) {
                    AudioTrack t = track;
                    if (t == null || filter.count() < 1) continue;
                    play(t, (long) chunk[0], (byte[]) chunk[1]);
                }
            }
        }

        private void play(AudioTrack t, long serverTs, byte[] data) {
            int offset = 13, length = data.length - 13;
            int frames = length / frameBytes;
            if (frames <= 0) return;
            long target = filter.clientTime(serverTs);                  // local µs the first frame must be heard
            long now = TimeFilter.nowUs();
            long chunkUs = frames * 1_000_000L / rate;
            if (target + chunkUs < now) return;                           // entirely late: drop (spec SHOULD)
            if (!started) {
                // Snap: lead with silence so this chunk lands on time, then start the track.
                // Start first: a stream-mode track blocks writes until it plays.
                t.play();
                started = true;
                long leadFrames = Math.max(0, (target - now) * rate / 1_000_000L);
                if (leadFrames > 0) writeSilence(t, (int) Math.min(leadFrames, rate * 5L));
            } else {
                long predicted = predictedOutputUs(t, now);
                long errorUs = predicted - target;                        // > 0: we are late
                long errFrames = errorUs * rate / 1_000_000L;
                if (Math.abs(errorUs) > 30_000) {                         // too far for soft correction: snap
                    if (errFrames > 0) {
                        int drop = (int) Math.min(errFrames, frames);
                        offset += drop * frameBytes;
                        length -= drop * frameBytes;
                        frames -= drop;
                        if (frames <= 0) return;
                    } else {
                        writeSilence(t, (int) Math.min(-errFrames, rate));
                    }
                } else if (Math.abs(errorUs) > 1_000) {                  // soft: one frame per chunk (~21 µs @48k)
                    int n = (int) Math.max(1, Math.min(Math.abs(errFrames), frames / 200));   // ≤0.5%
                    if (errFrames > 0) {
                        offset += n * frameBytes;
                        length -= n * frameBytes;
                        frames -= n;
                    } else {
                        byte[] dup = new byte[n * frameBytes];
                        for (int i = 0; i < n; i++) System.arraycopy(data, 13, dup, i * frameBytes, frameBytes);
                        write(t, dup, 0, dup.length);
                    }
                }
            }
            write(t, data, offset, length);
            if (!announced) {
                announced = true;
                listener.onAudioStarted();
            }
        }

        /** When the next frame we write will be heard, in local µs. */
        private long predictedOutputUs(AudioTrack t, long nowUs) {
            if (t.getTimestamp(stamp) && stamp.framePosition > 0) {
                return stamp.nanoTime / 1000L + (framesWritten - stamp.framePosition) * 1_000_000L / rate;
            }
            long played = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            return nowUs + (framesWritten - played) * 1_000_000L / rate;
        }

        private void writeSilence(AudioTrack t, int frames) {
            byte[] z = new byte[Math.min(frames, rate) * frameBytes];
            int left = frames;
            while (left > 0 && alive) {
                int n = Math.min(left, z.length / frameBytes);
                write(t, z, 0, n * frameBytes);
                left -= n;
            }
        }

        private void write(AudioTrack t, byte[] b, int off, int len) {
            int done = 0;
            while (done < len && alive) {
                int n = t.write(b, off + done, len - done);   // blocking
                if (n <= 0) break;
                done += n;
            }
            framesWritten += done / frameBytes;
        }
    }
}
