package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * The mic button (VOICE_ASSIST) and apps asking for dictation (RECOGNIZE_SPEECH) land here: a glass card over
 * whatever is on screen, talking to the Home Assistant Assist pipeline.
 */
public final class VoiceActivity extends Activity implements HaPipeline.Events {
    private HaPipeline pipeline;
    private boolean dictation;
    private TextView status, heard, reply;
    private View orb, orbHalo;
    private MediaPlayer player;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable closeSoon = this::finishCard;
    private float dp;
    private String lastHeard = "";
    private Intent pendingIntent;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        Intent again = pendingIntent;
        pendingIntent = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRun(again);
        else onError("No microphone access");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        setContentView(buildCard());
        startRun(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Mic pressed again while the card is up: start over.
        startRun(intent);
    }

    private void startRun(Intent intent) {
        main.removeCallbacks(closeSoon);
        if (pipeline != null) pipeline.cancel();
        stopPlayer();
        String action = intent == null ? null : intent.getAction();
        dictation = RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(action);
        heard.setText("");
        reply.setText("");
        reply.setVisibility(View.GONE);
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("Voice");
            heard.setText("Allow the microphone to talk to Home Assistant");
            pendingIntent = intent;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        status.setText("Connecting…");
        pipeline = new HaPipeline(HaPipeline.Settings.load(this), dictation, this);
        pipeline.start();
    }

    // ---------- pipeline events ----------

    @Override public void onListening() { status.setText(dictation ? "Listening for text…" : "Listening…"); pulse(true); }

    @Override public void onLevel(float level) {
        float s = 1f + level * 0.55f;
        orbHalo.animate().scaleX(s).scaleY(s).setDuration(90).start();
    }

    @Override public void onThinking() { status.setText("Thinking…"); pulse(false); }

    @Override public void onHeard(String text) {
        lastHeard = text;
        heard.setText(text.isEmpty() ? "…" : "“" + text + "”");
        if (dictation) {
            pulse(false);
            Intent data = new Intent();
            ArrayList<String> results = new ArrayList<>();
            results.add(text);
            data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
            data.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1f});
            setResult(text.isEmpty() ? RESULT_CANCELED : RESULT_OK, data);
            main.postDelayed(closeSoon, 700);
        }
    }

    @Override public void onReply(String text) {
        status.setText("Home Assistant");
        reply.setText(text);
        reply.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override public void onSpeech(String url) {
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(url);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> main.postDelayed(closeSoon, 2500));
            player.setOnErrorListener((mp, w, e) -> { main.postDelayed(closeSoon, 4000); return true; });
            player.prepareAsync();
        } catch (Exception e) {
            main.postDelayed(closeSoon, 4000);
        }
    }

    @Override public void onError(String message) {
        pulse(false);
        status.setText("Voice");
        heard.setText(message);
        main.postDelayed(closeSoon, 3500);
    }

    @Override public void onDone() {
        pulse(false);
        // No spoken reply coming (dictation, or TTS off): leave the text up a moment.
        if (player == null && !dictation) main.postDelayed(closeSoon, 5000);
    }

    // ---------- keys ----------

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Releasing the mic ends the utterance right away instead of waiting for HA's silence detection.
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (pipeline != null && event.getEventTime() - event.getDownTime() > 600) pipeline.endSpeech();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() { finishCard(); }

    private void finishCard() {
        if (isFinishing()) return;
        if (pipeline != null) pipeline.cancel();
        stopPlayer();
        View card = findViewById(android.R.id.content);
        card.animate().alpha(0f).translationY(30 * dp).setDuration(180).withEndAction(() -> {
            finish();
            overridePendingTransition(0, 0);
        }).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing() && pendingIntent == null) finishCard();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (pipeline != null) pipeline.cancel();
        stopPlayer();
        super.onDestroy();
    }

    private void stopPlayer() {
        if (player == null) return;
        try { player.release(); } catch (Exception ignored) { }
        player = null;
    }

    // ---------- view ----------

    private View buildCard() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (22 * dp);
        card.setPadding(pad, pad, (int) (30 * dp), pad);
        GradientDrawable glass = new GradientDrawable();
        glass.setColor(0xF5121620);
        glass.setCornerRadius(28 * dp);
        glass.setStroke((int) (1 * dp), 0x33FFFFFF);
        card.setBackground(glass);
        card.setElevation(24 * dp);

        FrameLayout orbBox = new FrameLayout(this);
        orbHalo = new View(this);
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor(0x447CC4FF);
        orbHalo.setBackground(halo);
        orb = new View(this);
        GradientDrawable core = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF9AD4FF, 0xFF5B7CFF});
        core.setShape(GradientDrawable.OVAL);
        orb.setBackground(core);
        int o = (int) (46 * dp);
        orbBox.addView(orbHalo, new FrameLayout.LayoutParams(o, o, Gravity.CENTER));
        orbBox.addView(orb, new FrameLayout.LayoutParams((int) (30 * dp), (int) (30 * dp), Gravity.CENTER));
        card.addView(orbBox, new LinearLayout.LayoutParams((int) (64 * dp), (int) (64 * dp)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (18 * dp), 0, 0, 0);
        status = text(11, 0xAAFFFFFF, true);
        status.setLetterSpacing(0.12f);
        heard = text(22, 0xFFFFFFFF, false);
        reply = text(17, 0xDDBFD9FF, false);
        reply.setPadding(0, (int) (6 * dp), 0, 0);
        col.addView(status);
        col.addView(heard);
        col.addView(reply);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (620 * dp), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (48 * dp);
        root.addView(card, lp);
        card.setAlpha(0f);
        card.setTranslationY(40 * dp);
        card.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new DecelerateInterpolator(2f)).start();
        return root;
    }

    private TextView text(float sp, int color, boolean caps) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setAllCaps(caps);
        t.setTypeface(Typeface.create(caps ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setMaxLines(3);
        return t;
    }

    private void pulse(boolean on) {
        orb.animate().cancel();
        if (on) {
            orb.animate().scaleX(1.12f).scaleY(1.12f).setDuration(600).withEndAction(() ->
                    orb.animate().scaleX(1f).scaleY(1f).setDuration(600).withEndAction(() -> {
                        if (status.getText().toString().startsWith("Listening")) pulse(true);
                    }).start()).start();
        } else {
            orb.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
            orbHalo.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
        }
    }
}
