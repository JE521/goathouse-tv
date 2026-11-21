package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * goathouse: full-screen Now Playing for music Music Assistant plays on this TV (Sendspin). OK / play-pause toggles,
 * Left / Right skip, Back returns to whatever was on screen while the music keeps playing.
 */
public final class NowPlayingActivity extends Activity implements SendspinService.Ui {
    static volatile boolean visible;

    private float dp;
    private ImageView backdrop, cover;
    private TextView title, artist, album, elapsed, remaining, state;
    private View progressFill;
    private FrameLayout progressTrack;
    private String artworkUrl = "";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(build());
        playCard(getIntent());
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        playCard(intent);
    }

    /** A music card on Home lands here: start it, then this screen follows what plays. */
    private void playCard(android.content.Intent i) {
        if (i == null || !i.hasExtra(MusicActivity.EXTRA_KIND)) return;
        String kind = i.getStringExtra(MusicActivity.EXTRA_KIND), name = i.getStringExtra(MusicActivity.EXTRA_NAME),
                artist = i.getStringExtra(MusicActivity.EXTRA_ARTIST), ndId = i.getStringExtra(MusicActivity.EXTRA_ND_ID),
                uri = i.getStringExtra(MusicActivity.EXTRA_URI);
        i.removeExtra(MusicActivity.EXTRA_KIND);
        // Home's music rows are Music Assistant's: play there, and switch the Music screen to MA if it was on Navidrome.
        Accounts acc = Accounts.of(this);
        if (acc.hasHomeAssistant() && acc.hasMusicAssistant() && "nd".equals(MusicSource.chosen(this))) {
            getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putString(MusicSource.PREF, "ma").apply();
        }
        MusicSource source = MusicSource.current(this);
        if (source == null) {   // Navidrome and this person isn't signed in yet: the Music screen asks
            startActivity(new android.content.Intent(i).setClass(this, MusicActivity.class).putExtra(MusicActivity.EXTRA_KIND, kind));
            finish();
            return;
        }
        title.setText(name);
        artist_setText(artist);
        state.setText("Starting…");
        new Thread(() -> {
            String err = null;
            try { MusicSource.playCard(source, kind, name, artist, ndId, uri); } catch (Exception e) { err = e.getMessage(); }
            String msg = err;
            main.post(() -> { if (msg != null) state.setText("Couldn't play: " + msg); else onState(); });
        }).start();
    }

    private void artist_setText(String a) { artist.setText(a == null ? "" : a); }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        SendspinService.addUi(this);
        onState();
        main.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        SendspinService.removeUi(this);
        main.removeCallbacks(tick);
    }

    private FeedClient images;

    /** Navidrome playing on this TV (and Music Assistant isn't): show that instead. */
    private static JSONObject localState() {
        JSONObject st = LocalPlayer.get().state();
        SendspinService s = SendspinService.live;
        return st != null && (s == null || !"playing".equals(s.playbackState)) ? st : null;
    }

    private void showLocal(JSONObject st) {
        title.setText(st.optString("title"));
        artist.setText(st.optString("artist"));
        String al = st.optString("album");
        album.setText(al);
        album.setVisibility(al.isEmpty() ? View.GONE : View.VISIBLE);
        String art = st.optString("image");
        if (!art.equals(artworkUrl)) {
            artworkUrl = art;
            if (images == null) images = FeedClient.forSetup(this);
            images.image(cover, art, (int) (420 * dp), (int) (420 * dp));
            images.image(backdrop, art, 24, 24);   // tiny copy scaled up = soft blur
        }
        state.setText(st.optBoolean("playing") ? "▶  Playing on this TV" : "❚❚  Paused");
        long pos = st.optLong("positionMs"), dur = st.optLong("durationMs");
        showProgress(pos, dur);
    }

    @Override
    public void onState() {
        JSONObject local = localState();
        if (local != null) { showLocal(local); return; }
        SendspinService s = SendspinService.live;
        if (s == null) { title.setText("Nothing playing"); state.setText(""); return; }
        JSONObject md = s.metadata;
        if (md != null) {
            title.setText(md.optString("title", ""));
            artist.setText(md.optString("artist", ""));
            String al = md.optString("album", "");
            album.setText(al);
            album.setVisibility(al.isEmpty() ? View.GONE : View.VISIBLE);
            String art = md.optString("artwork_url", "");
            if (!art.equals(artworkUrl)) {
                artworkUrl = art;
                loadArtwork(art);
            }
        }
        boolean playing = "playing".equals(s.playbackState);
        state.setText(!s.connected ? "Reconnecting…" : playing ? "▶  Playing on " + SendspinService.playerName(this)
                : "❚❚  Paused");
        updateProgress();
    }

    private void updateProgress() {
        JSONObject local = localState();
        if (local != null) { showLocal(local); return; }   // also picks up track changes
        SendspinService s = SendspinService.live;
        if (s == null) return;
        showProgress(s.positionMs(), s.durationMs());
    }

    private void showProgress(long pos, long dur) {
        if (pos < 0 || dur <= 0) {
            progressTrack.setVisibility(View.INVISIBLE);
            elapsed.setText("");
            remaining.setText("");
            return;
        }
        progressTrack.setVisibility(View.VISIBLE);
        int w = progressTrack.getWidth();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
        lp.width = (int) (w * Math.min(1.0, pos / (double) dur));
        progressFill.setLayoutParams(lp);
        elapsed.setText(clock(pos));
        remaining.setText("-" + clock(Math.max(0, dur - pos)));
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void loadArtwork(String url) {
        if (url.isEmpty()) { cover.setImageDrawable(null); backdrop.setImageDrawable(null); return; }
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(15000);
                byte[] data;
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    data = b.toByteArray();
                }
                Bitmap full = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (full == null) return;
                // A 24 px copy scaled back up is a soft, cheap blur for the backdrop.
                Bitmap tiny = Bitmap.createScaledBitmap(full, 24, Math.max(1, 24 * full.getHeight() / full.getWidth()), true);
                runOnUiThread(() -> {
                    if (!url.equals(artworkUrl)) return;
                    cover.setImageBitmap(full);
                    backdrop.setImageBitmap(tiny);
                    backdrop.setAlpha(0f);
                    backdrop.animate().alpha(1f).setDuration(400).start();
                });
            } catch (Exception ignored) { }
        }).start();
    }

    // ---------- keys ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {   // Back always leads to the Music screen, wherever Now Playing came from
            startActivity(new android.content.Intent(this, MusicActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            finish();
            return true;
        }
        if (localState() != null) {
            LocalPlayer p = LocalPlayer.get();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    p.toggle(); onState(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_NEXT: p.next(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: p.previous(); return true;
                default: return super.onKeyDown(keyCode, event);
            }
        }
        SendspinService s = SendspinService.live;
        if (s == null) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                s.command("playing".equals(s.playbackState) ? "pause" : "play");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: s.command("play"); return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: s.command("pause"); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT: s.command("next"); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: s.command("previous"); return true;
            default: return super.onKeyDown(keyCode, event);
        }
    }

    // ---------- view ----------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x99000000, 0xB3000000, 0xD9000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding((int) (110 * dp), 0, (int) (110 * dp), 0);

        FrameLayout coverBox = new FrameLayout(this);
        coverBox.setElevation(30 * dp);
        coverBox.setClipToOutline(true);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFF1A1D24);
        cbg.setCornerRadius(18 * dp);
        coverBox.setBackground(cbg);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        int cs = (int) (330 * dp);
        page.addView(coverBox, new LinearLayout.LayoutParams(cs, cs));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding((int) (56 * dp), 0, 0, 0);
        state = text(15, 0xB3FFFFFF, true);
        state.setLetterSpacing(0.06f);
        title = text(38, Color.WHITE, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, (int) (10 * dp), 0, 0);
        artist = text(24, 0xE6FFFFFF, false);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        artist.setPadding(0, (int) (6 * dp), 0, 0);
        album = text(18, 0x99FFFFFF, false);
        album.setSingleLine(true);
        album.setEllipsize(TextUtils.TruncateAt.END);
        album.setPadding(0, (int) (4 * dp), 0, 0);
        info.addView(state);
        info.addView(title);
        info.addView(artist);
        info.addView(album);

        progressTrack = new FrameLayout(this);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(0x40FFFFFF);
        tbg.setCornerRadius(3 * dp);
        progressTrack.setBackground(tbg);
        progressFill = new View(this);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setColor(0xF2FFFFFF);
        fbg.setCornerRadius(3 * dp);
        progressFill.setBackground(fbg);
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, (int) (6 * dp));
        plp.topMargin = (int) (34 * dp);
        info.addView(progressTrack, plp);
        LinearLayout times = new LinearLayout(this);
        elapsed = text(14, 0x99FFFFFF, false);
        remaining = text(14, 0x99FFFFFF, false);
        remaining.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(remaining, new LinearLayout.LayoutParams(0, -2, 1f));
        times.setPadding(0, (int) (8 * dp), 0, 0);
        info.addView(times);
        TextView hint = text(13, 0x66FFFFFF, false);
        hint.setText("OK  play / pause     ◀ ▶  skip     Back  return");
        hint.setPadding(0, (int) (40 * dp), 0, 0);
        info.addView(hint);
        page.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }
}
