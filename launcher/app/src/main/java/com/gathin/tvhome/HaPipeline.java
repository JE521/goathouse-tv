package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;


/**
 * One Assist pipeline run against Home Assistant over its WebSocket API: mic audio in (16 kHz PCM), events out.
 * sttOnly runs just speech-to-text (dictation); otherwise stt -> conversation -> tts, like the Voice PE.
 * Callbacks arrive on the main thread.
 */
final class HaPipeline {
    private static final String TAG = "GoatVoice";
    private static final int RATE = 16000;
    private static final long MAX_LISTEN_MS = 10_000;

    interface Events {
        void onListening();
        void onLevel(float level);          // 0..1 mic loudness, for the orb
        void onThinking();
        void onHeard(String text);
        void onReply(String text);
        void onSpeech(String url);          // absolute URL of the spoken reply
        void onError(String message);
        void onDone();
    }

    static final class Settings {
        String haUrl = "", token = "", pipeline = "", deviceId = "";

        static Settings load(Context ctx) {
            Settings s = new Settings();
            Accounts a = Accounts.of(ctx);
            if (!a.url(Accounts.HA_URL).isEmpty()) s.haUrl = a.url(Accounts.HA_URL);
            s.token = a.get(Accounts.HA_TOKEN);
            s.pipeline = a.get(Accounts.HA_PIPELINE);
            return s;
        }
    }

    private final Settings settings;
    private final boolean sttOnly;
    private final Events events;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MiniWebSocket ws = new MiniWebSocket();
    private volatile boolean streaming, finished;
    private volatile int handlerId = -1;
    private Thread audioThread;

    HaPipeline(Settings settings, boolean sttOnly, Events events) {
        this.settings = settings;
        this.sttOnly = sttOnly;
        this.events = events;
    }

    void start() {
        if (settings.token.isEmpty()) {
            post(() -> events.onError("Set up Home Assistant in Settings → Accounts"));
            return;
        }
        new Thread(() -> {
            try {
                ws.connect(settings.haUrl.replaceFirst("^http", "ws") + "/api/websocket", new MiniWebSocket.Listener() {
                    @Override public void onText(String text) { handle(text); }
                    @Override public void onClosed(String why) {
                        stopAudio();
                        if (!finished) fail("Connection closed");
                    }
                });
            } catch (Exception e) {
                fail("Can't reach Home Assistant");
            }
        }, "ha-connect").start();
    }

    /** Stop listening now (key released / Back): HA finishes with what it has. */
    void endSpeech() {
        if (streaming) sendEndOfAudio();
    }

    void cancel() {
        finished = true;
        stopAudio();
        new Thread(ws::close, "ha-close").start();   // a socket write: never on the main thread (it crashed the app)
    }

    private void handle(String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type");
            switch (type) {
                case "auth_required":
                    ws.sendText(new JSONObject().put("type", "auth").put("access_token", settings.token).toString());
                    break;
                case "auth_ok":
                    JSONObject run = new JSONObject().put("id", 1).put("type", "assist_pipeline/run")
                            .put("start_stage", "stt").put("end_stage", sttOnly ? "stt" : "tts")
                            .put("input", new JSONObject().put("sample_rate", RATE))
                            .put("timeout", 60);
                    if (!settings.pipeline.isEmpty()) run.put("pipeline", settings.pipeline);
                    if (!settings.deviceId.isEmpty()) run.put("device_id", settings.deviceId);
                    ws.sendText(run.toString());
                    break;
                case "auth_invalid":
                    fail("Home Assistant rejected the TV's token");
                    break;
                case "result":
                    if (!m.optBoolean("success", true)) {
                        JSONObject err = m.optJSONObject("error");
                        fail(err != null ? err.optString("message", "Pipeline error") : "Pipeline error");
                    }
                    break;
                case "event":
                    onEvent(m.getJSONObject("event"));
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "handle: " + e);
        }
    }

    private void onEvent(JSONObject e) {
        String type = e.optString("type");
        JSONObject data = e.optJSONObject("data");
        if (data == null) data = new JSONObject();
        Log.i(TAG, "event " + type);
        switch (type) {
            case "run-start":
                JSONObject rd = data.optJSONObject("runner_data");
                handlerId = rd == null ? -1 : rd.optInt("stt_binary_handler_id", -1);
                if (handlerId < 0) { fail("No audio channel from Home Assistant"); return; }
                startAudio();
                post(events::onListening);
                break;
            case "stt-vad-end":
                sendEndOfAudio();
                break;
            case "stt-end": {
                stopAudio();
                JSONObject out = data.optJSONObject("stt_output");
                String heard = out == null ? "" : out.optString("text", "").trim();
                Log.i(TAG, "heard: " + heard);
                if (isWhisperFiller(heard)) {   // what Whisper "hears" in silence or noise: never send it on
                    fail("Didn't catch that");
                    return;
                }
                post(() -> events.onHeard(heard));
                if (!sttOnly) post(events::onThinking);
                break;
            }
            case "intent-end": {
                String speech = "";
                JSONObject io = data.optJSONObject("intent_output");
                if (io != null) {
                    JSONObject r = io.optJSONObject("response");
                    JSONObject sp = r == null ? null : r.optJSONObject("speech");
                    JSONObject plain = sp == null ? null : sp.optJSONObject("plain");
                    if (plain != null) speech = plain.optString("speech", "");
                }
                String s = speech;
                Log.i(TAG, "reply: " + s);
                post(() -> events.onReply(s));
                break;
            }
            case "tts-end": {
                JSONObject to = data.optJSONObject("tts_output");
                String url = to == null ? "" : to.optString("url", "");
                if (!url.isEmpty()) {
                    String abs = url.startsWith("http") ? url : settings.haUrl + url;
                    post(() -> events.onSpeech(abs));
                }
                break;
            }
            case "error":
                fail(friendly(data.optString("code"), data.optString("message")));
                break;
            case "run-end":
                finished = true;
                stopAudio();
                new Thread(ws::close, "ha-close").start();
                post(events::onDone);
                break;
            default:
                break;
        }
    }

    private static final java.util.Set<String> FILLER = new java.util.HashSet<>(java.util.Arrays.asList(
            "", "you", "thank you", "thanks", "thank you very much", "thanks for watching", "thank you for watching",
            "bye", "okay", "ok", "so", "uh", "um", "hmm", "music", "applause", "silence"));

    static boolean isWhisperFiller(String text) {
        String t = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        return FILLER.contains(t);
    }

    private static String friendly(String code, String message) {
        if ("stt-no-text-recognized".equals(code)) return "Didn't catch that";
        if ("stt-stream-failed".equals(code)) return "Speech service unavailable";
        return message == null || message.isEmpty() ? code : message;
    }

    @SuppressLint("MissingPermission")
    private void startAudio() {
        streaming = true;
        audioThread = new Thread(() -> {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord rec;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
                rec.startRecording();
            } catch (Exception e) {
                fail("Microphone unavailable");
                return;
            }
            byte[] chunk = new byte[1 + 2048];
            long started = System.currentTimeMillis();
            try {
                while (streaming) {
                    int n = rec.read(chunk, 1, 2048);
                    if (n <= 0) continue;
                    chunk[0] = (byte) handlerId;
                    ws.sendBinary(chunk, n + 1);
                    float level = rms(chunk, 1, n);
                    post(() -> events.onLevel(level));
                    if (System.currentTimeMillis() - started > MAX_LISTEN_MS) sendEndOfAudio();
                }
            } catch (Exception e) {
                Log.w(TAG, "audio: " + e);
            } finally {
                try { rec.stop(); } catch (Exception ignored) { }
                rec.release();
            }
        }, "ha-mic");
        audioThread.start();
    }

    private static float rms(byte[] b, int off, int n) {
        long sum = 0;
        int samples = n / 2;
        for (int i = 0; i < samples; i++) {
            short s = (short) ((b[off + 2 * i] & 0xFF) | (b[off + 2 * i + 1] << 8));
            sum += (long) s * s;
        }
        double r = Math.sqrt(sum / (double) Math.max(1, samples)) / 6000.0;
        return (float) Math.min(1.0, r);
    }

    private synchronized void sendEndOfAudio() {
        if (!streaming) return;
        streaming = false;
        try { ws.sendBinary(new byte[]{(byte) handlerId}, 1); } catch (Exception ignored) { }
    }

    private void stopAudio() { streaming = false; }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        stopAudio();
        new Thread(ws::close, "ha-close").start();
        post(() -> events.onError(message));
    }

    private void post(Runnable r) { main.post(r); }
}
