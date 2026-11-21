package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * goathouse: the Music screen — library tabs, a cover grid and a player bar, powered by Music Assistant (via Home
 * Assistant) or Navidrome (the source pill switches). The remote's music button toggles Home <-> Music.
 */
public final class MusicActivity extends Activity implements SendspinService.Ui {
    static volatile boolean visible;
    static java.lang.ref.WeakReference<MusicActivity> live = new java.lang.ref.WeakReference<>(null);
    static final String BAR_HIDDEN_KEY = "music_bar_hidden";
    private TextView mini;   // "▶ song · artist" in the heading while the player bar is lowered
    static final String EXTRA_KIND = "play_kind", EXTRA_NAME = "play_name", EXTRA_ARTIST = "play_artist", EXTRA_ND_ID = "play_nd_id",
            EXTRA_URI = "play_uri";

    private static final String[] TABS = {"Playlists", "Albums", "Artists", "Recent", "Up Next", "Search"};
    private float dp;
    private MusicSource source;
    private FeedClient images;
    private final ExecutorService work = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<TextView> tabViews = new ArrayList<>();
    private TextView sourcePill, status, title;
    private EditText searchBox;
    private RecyclerView grid;
    private LinearLayout letters;   // A–Z jump strip for Albums / Artists
    private final List<JSONObject> items = new ArrayList<>();
    private final ArrayDeque<Object[]> backStack = new ArrayDeque<>();   // {title, items}
    private int tab = 0;
    private int loadToken;
    // player bar
    private View bar;
    private ImageView barCover, backdrop;
    private TextView barTitle, barArtist, barState;
    private View barFill;
    private FrameLayout barTrack;
    private String barImage = "";
    private String barSong = "";   // to refresh Up Next when the song changes
    private static final LruCache<String, Bitmap> COVERS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { refreshBar(); main.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        images = FeedClient.forSetup(this);
        source = MusicSource.current(this);
        live = new java.lang.ref.WeakReference<>(this);
        setContentView(build());
        placeBar(false);
        if (source == null) askLogin(() -> { selectTab(0); handlePlayIntent(getIntent()); });
        else {
            selectTab(0);
            handlePlayIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handlePlayIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        // The Home profile may have changed while we were away: follow it (Navidrome plays as that person).
        MusicSource now = MusicSource.current(this);
        if (source != null && now != null && !now.label().equals(source.label())) {
            source = now;
            sourcePill.setText(source.label());
            selectTab(tab);
        }
        SendspinService.addUi(this);
        main.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        visible = false;
        SendspinService.removeUi(this);
        main.removeCallbacks(tick);
    }

    @Override public void onState() { refreshBar(); }

    /** A music card on Home: play it here. */
    private void handlePlayIntent(Intent i) {
        if (i == null || !i.hasExtra(EXTRA_KIND)) return;
        String kind = i.getStringExtra(EXTRA_KIND), name = i.getStringExtra(EXTRA_NAME),
                artist = i.getStringExtra(EXTRA_ARTIST), ndId = i.getStringExtra(EXTRA_ND_ID), uri = i.getStringExtra(EXTRA_URI);
        i.removeExtra(EXTRA_KIND);
        status.setText("Starting “" + name + "”…");
        work.execute(() -> {
            String err = null;
            try {
                MusicSource.playCard(source, kind, name, artist, ndId, uri);
            } catch (Exception e) { err = e.getMessage(); }
            String msg = err;
            main.post(() -> { status.setText(msg == null ? "" : "Couldn't play: " + msg); bar.requestFocus(); });
        });
    }

    // ---------- tabs and loading ----------

    private void selectTab(int t) {
        tab = t;
        if (source == null) return;
        backStack.clear();
        for (int i = 0; i < tabViews.size(); i++) paintPill(tabViews.get(i), i == t, tabViews.get(i).isFocused());
        boolean isSearch = TABS[t].equals("Search");
        searchBox.setVisibility(isSearch ? View.VISIBLE : View.GONE);
        if (isSearch) {
            show(TABS[t], new ArrayList<>());
            status.setText("Type to search " + source.label());
            return;
        }
        load(TABS[t], () -> {
            switch (TABS[tab]) {
                case "Playlists": return source.playlists();
                case "Albums": loadAlbums(); return null;
                case "Artists": return source.artists();
                case "Up Next": return source.upNext();
                default: return source.recent();
            }
        });
    }

    private interface Loader { List<JSONObject> load() throws Exception; }

    private static final java.util.Set<String> FETCHING = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Albums: the whole library, which can be tens of thousands. Shown from the copy kept on the TV when there is one
     * (refreshed once a day in the background); otherwise streamed in a page at a time, with the A–Z strip filling in.
     */
    private void loadAlbums() {
        int token = loadToken;   // load() just bumped it for this tab
        MusicSource s = source;
        java.io.File f = new java.io.File(getCacheDir(), "albums-" + s.key().replaceAll("[^A-Za-z0-9]", "_") + ".json");
        new Thread(() -> {
            List<JSONObject> cached = readList(f);
            if (cached != null) main.post(() -> { if (token == loadToken) show("Albums", cached); });
            boolean fresh = cached != null && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L;
            if (fresh || !FETCHING.add(f.getName())) return;
            List<JSONObject> all = new ArrayList<>();
            try {
                for (int off = 0; off < 200_000; off += 500) {
                    List<JSONObject> page = s.albumsPage(off, 500);
                    all.addAll(page);
                    boolean more = page.size() == 500;
                    if (cached == null) {
                        boolean first = off == 0;
                        int count = all.size();
                        main.post(() -> {
                            if (token != loadToken) return;
                            if (first) show("Albums", page); else append(page);
                            status.setText(more ? "Loading… " + count + " albums so far" : "");
                        });
                    }
                    if (!more) break;
                }
                writeList(f, all);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (cached == null) main.post(() -> { if (token == loadToken) status.setText("Couldn't load all albums: " + msg); });
            } finally {
                FETCHING.remove(f.getName());
            }
        }, "albums").start();
    }

    private void append(List<JSONObject> more) {
        int start = items.size();
        items.addAll(more);
        grid.getAdapter().notifyItemRangeInserted(start, more.size());
        if (!letters.hasFocus()) updateLetters();
    }

    private static List<JSONObject> readList(java.io.File f) {
        if (!f.isFile()) return null;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            org.json.JSONArray a = new org.json.JSONArray(b.toString("UTF-8"));
            List<JSONObject> out = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeList(java.io.File f, List<JSONObject> list) {
        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
        try (java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(new org.json.JSONArray(list).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private void load(String heading, Loader loader) {
        int token = ++loadToken;
        title.setText(heading);
        status.setText("Loading…");
        items.clear();
        grid.getAdapter().notifyDataSetChanged();
        work.execute(() -> {
            List<JSONObject> got = null;
            String err = null;
            try { got = loader.load(); } catch (Exception e) { err = e.getMessage(); }
            List<JSONObject> result = got;
            String msg = err;
            main.post(() -> {
                if (token != loadToken) return;
                if (result == null && msg == null) return;   // the loader shows its own results (Albums)
                if (result == null) { status.setText("Couldn't load from " + source.label() + ": " + msg); return; }
                show(heading, result);
            });
        });
    }

    private void show(String heading, List<JSONObject> list) {
        title.setText(heading);
        items.clear();
        items.addAll(list);
        grid.getAdapter().notifyDataSetChanged();
        grid.scrollToPosition(0);
        updateLetters();
        int more = list.isEmpty() ? 0 : list.get(0).optInt("total") - list.get(0).optInt("index") - list.size();
        status.setText(TABS[tab].equals("Up Next") && more > 0 ? "+" + more + " more in the queue"
                : !list.isEmpty() || TABS[tab].equals("Search") ? ""
                : TABS[tab].equals("Up Next") ? "Nothing queued. Play an album or playlist."
                : "Nothing here yet");
    }

    private int pendingFocus = -1;

    private void focusPending() {
        if (pendingFocus < 0) return;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pendingFocus);
        if (vh != null && vh.itemView.requestFocus()) pendingFocus = -1;
    }

    /** The A–Z strip: only the letters present, in list order; other scripts and digits go under #. */
    private void updateLetters() {
        letters.removeAllViews();
        String t = TABS[tab];
        boolean on = backStack.isEmpty() && ("Albums".equals(t) || "Artists".equals(t)) && items.size() > 24;
        letters.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        java.util.LinkedHashMap<String, Integer> first = new java.util.LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject it = items.get(i);
            String key = letterOf(it.optString("sort", it.optString("name")));
            if (!first.containsKey(key)) first.put(key, i);
        }
        List<String> keys = new ArrayList<>(first.keySet());
        java.util.Collections.sort(keys, (a, b) -> rank(a) != rank(b) ? rank(a) - rank(b) : a.compareTo(b));
        for (String key : keys) {
            int target = first.get(key);
            TextView l = text(15, 0xCCFFFFFF, true);
            l.setText(key);
            l.setGravity(Gravity.CENTER);
            l.setFocusable(true);
            l.setMinWidth((int) (25 * dp));
            l.setSingleLine(true);
            l.setPadding((int) (3 * dp), (int) (5 * dp), (int) (3 * dp), (int) (5 * dp));
            l.setOnFocusChangeListener((v, f) -> {
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(14 * dp);
                g.setColor(f ? 0xFFFFFFFF : 0x00000000);
                l.setBackground(g);
                l.setTextColor(f ? 0xFF0B0D12 : 0xCCFFFFFF);
                if (f) ((GridLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(target, 0);   // jump as you move
            });
            Runnable land = () -> {
                pendingFocus = target;   // focused when its card is laid out, if it isn't on screen yet
                ((GridLayoutManager) grid.getLayoutManager()).scrollToPositionWithOffset(target, 0);
                grid.post(this::focusPending);
            };
            l.setOnClickListener(v -> land.run());
            l.setOnKeyListener((v, code, e) -> {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.getAction() == KeyEvent.ACTION_DOWN) { land.run(); return true; }
                return false;
            });
            letters.addView(l);
        }
    }

    /** # (numbers and symbols) first, then A–Z, then other alphabets under "А–Я". */
    private static int rank(String key) { return key.equals("#") ? 0 : key.length() == 1 ? 1 : 2; }

    private static String letterOf(String name) {
        String n = name.trim();
        if (n.toLowerCase(Locale.ROOT).startsWith("the ") && n.length() > 4) n = n.substring(4);
        if (n.isEmpty()) return "#";
        char c = Character.toUpperCase(n.charAt(0));   // the first character, as the server sorts: "Awaken" stays under #
        if (c >= 'A' && c <= 'Z') return String.valueOf(c);
        return Character.isLetter(c) ? "А–Я" : "#";
    }

    private void open(JSONObject item) {
        if ("maqueue".equals(item.optString("kind"))) {   // Music Assistant: the next song skips, the current one opens Now Playing
            if (item.optInt("offset") == 1) {
                control("next");
                main.postDelayed(() -> { if ("Up Next".equals(TABS[tab])) selectTab(tab); }, 1500);
            } else {
                startActivity(new Intent(this, NowPlayingActivity.class));
            }
            return;
        }
        if ("queued".equals(item.optString("kind"))) {
            LocalPlayer.get().jump(item.optInt("queueIndex"));
            main.postDelayed(() -> { refreshBar(); if ("Up Next".equals(TABS[tab])) selectTab(tab); }, 500);
            return;
        }
        if ("artist".equals(item.optString("kind"))) {
            backStack.push(new Object[]{title.getText().toString(), new ArrayList<>(items)});
            load(item.optString("name"), () -> source.artistAlbums(item));
            return;
        }
        status.setText("Playing “" + item.optString("name") + "”…");
        if (barLowered()) setBarLowered(false);
        work.execute(() -> {
            String err = null;
            try { source.play(item); } catch (Exception e) { err = e.getMessage(); }
            String msg = err;
            main.post(() -> status.setText(msg == null ? "" : "Couldn't play: " + msg));
        });
    }

    private void switchSource() {
        String next = "ma".equals(MusicSource.chosen(this)) ? "nd" : "ma";
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putString(MusicSource.PREF, next).apply();
        MusicSource s = MusicSource.current(this);
        if (s == null) { askLogin(() -> selectTab(tab)); return; }
        source = s;
        sourcePill.setText(source.label());
        selectTab(tab);
    }

    /** This person (or Family's account) hasn't signed in to Navidrome on this TV yet: ask, then carry on. */
    private void askLogin(Runnable then) {
        Accounts acc = Accounts.of(this);
        String person = acc.profile();
        status.setText("Sign in to see " + (person.isEmpty() ? "Family's" : person + "'s") + " music");
        MusicLogin.show(this, person.isEmpty() ? acc.familyMusicUser() : person, person, user -> {
            if (person.isEmpty() && !user.equalsIgnoreCase(acc.familyMusicUser())) acc.setFamilyMusicUser(user);
            source = MusicSource.current(this);
            if (source == null) return;
            sourcePill.setText(source.label());
            then.run();
        });
    }

    @Override
    public void onBackPressed() {
        // Like Home: Back from deep in a list goes to the top of that section first, then to its tab, then out.
        View f = getCurrentFocus();
        View card = f == null ? null : grid.findContainingItemView(f);
        if (card != null && grid.getChildAdapterPosition(card) >= 6) {
            pendingFocus = 0;
            grid.scrollToPosition(0);
            grid.post(this::focusPending);
            return;
        }
        if (!backStack.isEmpty()) {
            Object[] prev = backStack.pop();
            @SuppressWarnings("unchecked") List<JSONObject> list = (List<JSONObject>) prev[1];
            show((String) prev[0], list);
            return;
        }
        TextView tabPill = tabViews.isEmpty() ? null : tabViews.get(tab);
        if (tabPill != null && f != tabPill) {
            grid.scrollToPosition(0);
            tabPill.requestFocus();
            return;
        }
        super.onBackPressed();
    }

    // ---------- player bar ----------

    private static final int BAR_H = 88, BAR_GAP = 22, BAR_PEEK = 38;   // dp
    private View gridClip;

    private boolean barLowered() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getBoolean(BAR_HIDDEN_KEY, true);
    }

    /** Lowered: only the bar's top edge (the song title) peeks up, like Home's tucked dock. Focus raises it. */
    private void placeBar(boolean animate) {
        boolean down = barLowered() && !bar.hasFocus();
        float y = down ? (BAR_H + BAR_GAP - BAR_PEEK) * dp : 0;
        if (animate) bar.animate().translationY(y).alpha(down ? 0.85f : 1f).setDuration(220).start();
        else { bar.setTranslationY(y); bar.setAlpha(down ? 0.85f : 1f); }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) gridClip.getLayoutParams();
        int bottom = (int) ((barLowered() ? BAR_PEEK - BAR_GAP + 8 : BAR_H + 8) * dp);   // page padding already gives BAR_GAP
        if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; gridClip.setLayoutParams(lp); }
    }

    private void setBarLowered(boolean lowered) {
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit().putBoolean(BAR_HIDDEN_KEY, lowered).apply();
        if (lowered && bar.hasFocus()) grid.requestFocus();
        placeBar(true);
    }

    /** Hold Guide: lower or raise the player bar (remembered). */
    void toggleBar() { setBarLowered(!barLowered()); }

    private void refreshBar() {
        JSONObject np = source == null ? null : source.nowPlaying();
        if (mini != null) {
            boolean show = false;   // the peeking bar shows the song now
            mini.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) mini.setText((np.optBoolean("playing") ? "▶  " : "❚❚  ") + np.optString("title")
                    + (np.optString("artist").isEmpty() ? "" : "  ·  " + np.optString("artist")));
        }
        if (np == null) {
            barTitle.setText("Nothing playing");
            barArtist.setText(source == null ? "" : source.label());
            barState.setText("");
            barTrack.setVisibility(View.INVISIBLE);
            if (!barImage.isEmpty()) {
                barImage = "";
                barCover.setImageDrawable(null);
                backdrop.setImageDrawable(null);
            }
            return;
        }
        String song = np.optString("title") + "\n" + np.optString("artist");
        if (!song.equals(barSong)) {
            boolean changed = !barSong.isEmpty();
            barSong = song;
            if (changed && "Up Next".equals(TABS[tab]) && backStack.isEmpty() && !grid.hasFocus()) selectTab(tab);
        }
        barTitle.setText(np.optString("title"));
        String sub = np.optString("artist");
        if (!np.optString("album").isEmpty()) sub += "  ·  " + np.optString("album");
        barArtist.setText(sub);
        barState.setText(np.optBoolean("playing") ? "▶" : "❚❚");
        long pos = np.optLong("positionMs"), dur = np.optLong("durationMs");
        if (dur > 0) {
            barTrack.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) barFill.getLayoutParams();
            lp.width = (int) (barTrack.getWidth() * Math.min(1.0, pos / (double) dur));
            barFill.setLayoutParams(lp);
        } else barTrack.setVisibility(View.INVISIBLE);
        String img = np.optString("image");
        if (!img.equals(barImage)) {
            barImage = img;
            loadCover(barCover, img, (int) (72 * dp));
            loadBackdrop(img);
        }
    }

    // ---------- images ----------

    private void loadCover(ImageView view, String key, int size) {
        view.setTag(key);
        view.setImageDrawable(null);
        if (key == null || key.isEmpty()) return;
        if (key.startsWith("nd:")) { images.image(view, key, size, size); return; }
        Bitmap hit = COVERS.get(key + size);
        if (hit != null) { view.setImageBitmap(hit); return; }
        work.execute(() -> {
            Bitmap b = fetch(key, size);
            if (b == null) return;
            COVERS.put(key + size, b);
            main.post(() -> { if (key.equals(view.getTag())) view.setImageBitmap(b); });
        });
    }

    private void loadBackdrop(String key) {
        if (key == null || key.isEmpty() || key.startsWith("nd:")) { backdrop.setImageDrawable(null); return; }
        work.execute(() -> {
            Bitmap b = fetch(key, 48);
            if (b == null) return;
            Bitmap tiny = Bitmap.createScaledBitmap(b, 24, Math.max(1, 24 * b.getHeight() / Math.max(1, b.getWidth())), true);
            main.post(() -> {
                backdrop.setImageBitmap(tiny);
                backdrop.setAlpha(0f);
                backdrop.animate().alpha(1f).setDuration(500).start();
            });
        });
    }

    private static Bitmap fetch(String url, int size) {
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
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- keys ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (bar.hasFocus() && e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (source != null && source.nowPlaying() == null) status.setText("Nothing playing yet: pick something above");
                    else startActivity(new Intent(this, NowPlayingActivity.class));
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: control("next"); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: control("previous"); return true;
                default: break;
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: control("play_pause"); return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT: control("next"); return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS: control("previous"); return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void control(String c) {
        MusicSource s = source;
        if (s == null) return;
        work.execute(() -> s.control(c));
        main.postDelayed(this::refreshBar, 400);
    }

    // ---------- views ----------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0xB3000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding((int) (64 * dp), (int) (30 * dp), (int) (64 * dp), (int) (22 * dp));
        page.setClipChildren(false);
        page.setClipToPadding(false);

        // top bar: tabs + source pill
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        for (int i = 0; i < TABS.length; i++) {
            TextView t = pill(TABS[i]);
            int idx = i;
            t.setOnClickListener(v -> selectTab(idx));
            t.setOnFocusChangeListener((v, f) -> paintPill(t, idx == tab, f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = (int) (10 * dp);
            top.addView(t, lp);
            tabViews.add(t);
        }
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        sourcePill = pill(source == null ? "Navidrome" : source.label());
        sourcePill.setOnClickListener(v -> switchSource());
        sourcePill.setOnFocusChangeListener((v, f) -> paintPill(sourcePill, false, f));
        top.addView(sourcePill);
        page.addView(top);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.BOTTOM);
        heading.setPadding((int) (6 * dp), (int) (18 * dp), 0, (int) (8 * dp));
        title = text(28, Color.WHITE, true);
        status = text(15, 0x99FFFFFF, false);
        status.setPadding((int) (18 * dp), 0, 0, (int) (3 * dp));
        heading.addView(title);
        heading.addView(status);
        heading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        mini = text(15, 0xCCFFFFFF, false);
        mini.setSingleLine(true);
        mini.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mini.setMaxWidth((int) (420 * dp));
        mini.setPadding(0, 0, (int) (6 * dp), (int) (3 * dp));
        heading.addView(mini);
        page.addView(heading);

        searchBox = new EditText(this);
        searchBox.setHint("Search artists, albums, songs");
        searchBox.setHintTextColor(0x80FFFFFF);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0x26FFFFFF);
        sb.setCornerRadius(14 * dp);
        searchBox.setBackground(sb);
        searchBox.setPadding((int) (18 * dp), (int) (12 * dp), (int) (18 * dp), (int) (12 * dp));
        searchBox.setOnEditorActionListener((v, actionId, ev) -> {
            String q = searchBox.getText().toString().trim();
            if (!q.isEmpty()) load("Search", () -> source.search(q));
            return false;
        });
        searchBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.bottomMargin = (int) (10 * dp);
        page.addView(searchBox, slp);

        letters = new LinearLayout(this);
        letters.setGravity(Gravity.CENTER_VERTICAL);
        letters.setClipChildren(false);
        letters.setVisibility(View.GONE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.bottomMargin = (int) (6 * dp);
        page.addView(letters, llp);

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 6));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        int zoom = (int) (12 * dp);   // room inside the clip for the focused card's zoom
        grid.setPadding(zoom, zoom, zoom, zoom);
        grid.setItemAnimator(null);
        grid.setAdapter(new Cards());
        FrameLayout clip = new FrameLayout(this);   // keeps half-visible rows from spilling under the player bar
        clip.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.leftMargin = clp.rightMargin = -zoom;
        clp.bottomMargin = (int) (6 * dp);
        page.addView(clip, clp);
        gridClip = clip;
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, (int) (BAR_H * dp), Gravity.BOTTOM);
        blp.leftMargin = blp.rightMargin = (int) (64 * dp);
        blp.bottomMargin = (int) (BAR_GAP * dp);
        root.addView(buildBar(), blp);
        return root;
    }

    private View buildBar() {
        LinearLayout b = new LinearLayout(this);
        bar = b;
        b.setFocusable(true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding((int) (14 * dp), (int) (12 * dp), (int) (22 * dp), (int) (12 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(20 * dp);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0x4DFFFFFF : 0x26FFFFFF);
            bg.setStroke(f ? (int) (2 * dp) : 0, 0xE6FFFFFF);
            placeBar(true);   // a peeking bar rises while it has focus
        });
        FrameLayout coverBox = new FrameLayout(this);
        coverBox.setClipToOutline(true);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0xFF1A1D24);
        cbg.setCornerRadius(10 * dp);
        coverBox.setBackground(cbg);
        barCover = new ImageView(this);
        barCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(barCover, new FrameLayout.LayoutParams(-1, -1));
        int cs = (int) (64 * dp);
        b.addView(coverBox, new LinearLayout.LayoutParams(cs, cs));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding((int) (16 * dp), 0, (int) (16 * dp), 0);
        barTitle = text(18, Color.WHITE, true);
        barTitle.setSingleLine(true);
        barTitle.setEllipsize(TextUtils.TruncateAt.END);
        barArtist = text(14, 0xB3FFFFFF, false);
        barArtist.setSingleLine(true);
        barArtist.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(barTitle);
        info.addView(barArtist);
        barTrack = new FrameLayout(this);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(0x40FFFFFF);
        tbg.setCornerRadius(2 * dp);
        barTrack.setBackground(tbg);
        barFill = new View(this);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setColor(0xF2FFFFFF);
        fbg.setCornerRadius(2 * dp);
        barFill.setBackground(fbg);
        barTrack.addView(barFill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, (int) (4 * dp));
        tlp.topMargin = (int) (8 * dp);
        info.addView(barTrack, tlp);
        b.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        barState = text(22, Color.WHITE, true);
        b.addView(barState);
        TextView hint = text(12, 0x80FFFFFF, false);
        hint.setText("   OK Now Playing   ◀ ▶ skip");
        b.addView(hint);
        return b;
    }

    private TextView pill(String label) {
        TextView t = text(16, Color.WHITE, true);
        t.setText(label);
        t.setFocusable(true);
        t.setPadding((int) (18 * dp), (int) (9 * dp), (int) (18 * dp), (int) (9 * dp));
        paintPill(t, false, false);
        return t;
    }

    private void paintPill(TextView t, boolean selected, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(22 * dp);
        if (focused) { g.setColor(0xFFFFFFFF); t.setTextColor(0xFF0B0D12); }
        else if (selected) { g.setColor(0x40FFFFFF); t.setTextColor(Color.WHITE); }
        else { g.setColor(0x14FFFFFF); t.setTextColor(0xCCFFFFFF); }
        t.setBackground(g);
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    // ---------- grid ----------

    private final class Cards extends RecyclerView.Adapter<Card> {
        @Override public int getItemCount() { return items.size(); }
        @NonNull @Override public Card onCreateViewHolder(@NonNull ViewGroup p, int t) { return new Card(); }
        @Override public void onBindViewHolder(@NonNull Card h, int pos) {
            h.bind(items.get(pos));
            if (pos == pendingFocus) grid.post(MusicActivity.this::focusPending);
        }
    }

    private final class Card extends RecyclerView.ViewHolder {
        final FrameLayout box;
        final ImageView art;
        final TextView name, sub;
        JSONObject item;

        Card() {
            super(new LinearLayout(MusicActivity.this));
            LinearLayout root = (LinearLayout) itemView;
            root.setOrientation(LinearLayout.VERTICAL);
            root.setFocusable(true);
            root.setClipChildren(false);
            root.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (10 * dp));
            root.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            box = new FrameLayout(MusicActivity.this);
            box.setClipToOutline(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1A1D24);
            bg.setCornerRadius(12 * dp);
            box.setBackground(bg);
            art = new ImageView(MusicActivity.this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(art, new FrameLayout.LayoutParams(-1, -1));
            root.addView(box, new LinearLayout.LayoutParams(-1, (int) (138 * dp)));
            name = text(14, Color.WHITE, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding((int) (2 * dp), (int) (8 * dp), 0, 0);
            sub = text(12, 0x99FFFFFF, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPadding((int) (2 * dp), 0, 0, 0);
            root.addView(name);
            root.addView(sub);
            root.setOnFocusChangeListener((v, f) -> {
                float s = f ? 1.07f : 1f;
                v.animate().scaleX(s).scaleY(s).translationZ(f ? 10 * dp : 0).setDuration(130).start();
                bg.setStroke(f ? (int) (2.5f * dp) : 0, 0xF0FFFFFF);
            });
            root.setOnClickListener(v -> { if (item != null) open(item); });
        }

        void bind(JSONObject it) {
            item = it;
            String kind = it.optString("kind");
            name.setText(it.optString("name"));
            String s = it.optString("subtitle");
            sub.setText(s.isEmpty() ? cap(kind) : s);
            GradientDrawable bg = (GradientDrawable) box.getBackground();
            bg.setCornerRadius("artist".equals(kind) ? 69 * dp : 12 * dp);   // artists are round, tvOS-style
            loadCover(art, it.optString("image"), (int) (138 * dp));
        }
    }

    private static String cap(String s) { return s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }
}
