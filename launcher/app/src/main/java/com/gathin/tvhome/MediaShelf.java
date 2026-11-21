package com.gathin.tvhome;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * goathouse: rows of Jellyfin / Navidrome content from the tv-feed service, floating between the header and the dock.
 * Plain views, no extra libraries. Rows sit in a ScrollView so more rows than fit can be reached with Down; Down from
 * the last row and Up from the dock are ordinary focus searches, so the grid's own navigation is untouched.
 */
final class MediaShelf {
    private static final String TAG = "MediaShelf";
    private static final long REFRESH_MS = 5 * 60_000L;
    private static final long PHOTO_SYNC_MS = 30 * 60_000L;

    interface Host {
        void onShelfFocused();
        void onPhotosChanged();
        void onShelfShown(boolean firstTime);
        void focusDock();
    }

    private final Context ctx;
    private final ScrollView scroll;
    private final LinearLayout rows;
    private final FeedClient client;
    private final Host host;
    private final float dp;
    private long lastFeed, lastPhotos;
    private String lastJson = "";
    private boolean shownOnce;
    private View lastCard;

    MediaShelf(Context ctx, ScrollView scroll, FeedClient client, Host host) {
        this.ctx = ctx;
        this.scroll = scroll;
        this.client = client;
        this.host = host;
        this.dp = ctx.getResources().getDisplayMetrics().density;
        rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(0, 0, 0, (int) (8 * dp));
        scroll.addView(rows, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setVisibility(View.GONE);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> padForBottomAnchor());
        rows.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> padForBottomAnchor());
    }

    /** Empty space above the first row, so the focused row always sits at the bottom of the shelf. */
    private void padForBottomAnchor() {
        if (rows.getChildCount() == 0 || scroll.getHeight() == 0) return;
        int first = rows.getChildAt(0).getHeight();
        if (first == 0) return;
        int want = Math.max(0, scroll.getHeight() - first - rows.getPaddingBottom());
        if (rows.getPaddingTop() != want) {
            rows.setPadding(rows.getPaddingLeft(), want, rows.getPaddingRight(), rows.getPaddingBottom());
            scroll.post(() -> scroll.scrollTo(0, targetFor(focusedRow())));
        }
    }

    private View focusedRow() {
        View f = scroll.findFocus();
        if (f == null || f.getParent() == null || f.getParent().getParent() == null) return rows.getChildCount() > 0 ? rows.getChildAt(0) : null;
        return (View) f.getParent().getParent();
    }

    /** Scroll that puts row at the bottom of the shelf (the row above it then fills the top half). */
    private int targetFor(View row) {
        if (row == null) return 0;
        return Math.max(0, row.getBottom() + rows.getPaddingBottom() - scroll.getHeight());
    }

    boolean isShowing() { return scroll.getVisibility() != View.GONE && rows.getChildCount() > 0; }

    boolean hasFocus() { return scroll.hasFocus(); }

    /** First card of the first row (after it is laid out). */
    void focusFirst() { focusFirst(12); }

    /** Retries frame by frame: right after bind the rows have no laid-out cards yet. */
    private void focusFirst(int tries) {
        scroll.postOnAnimation(() -> {
            if (rows.getChildCount() == 0) return;
            ViewGroup row = (ViewGroup) rows.getChildAt(0);
            RecyclerView list = (RecyclerView) row.getChildAt(1);
            View card = list.getLayoutManager() == null ? null : list.getLayoutManager().findViewByPosition(0);
            if (card == null && tries == 12) list.scrollToPosition(0);   // scrolled far right: bring card 0 back
            if (card != null && card.getWidth() > 0) {
                card.requestFocus();
                list.smoothScrollToPosition(0);
            }
            else if (tries > 0) focusFirst(tries - 1);
        });
    }

    /** Called from onResume and a periodic tick: refetches when stale. Cheap when not. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastFeed > REFRESH_MS) {
            lastFeed = now;
            client.feed(json -> {
                if (json != null) bind(json);
            });
        }
        if (now - lastPhotos > PHOTO_SYNC_MS) {
            lastPhotos = now;
            client.syncPhotos(ctx, changed -> {
                if (changed) host.onPhotosChanged();
            });
        }
    }

    private void bind(JSONObject feed) {
        JSONArray data = feed.optJSONArray("rows");
        String sig = data == null ? "" : data.toString();
        if (sig.equals(lastJson)) return;          // nothing new: keep focus and scroll exactly where they are
        boolean hadFocus = scroll.hasFocus();
        lastJson = sig;
        rows.removeAllViews();
        if (data == null || data.length() == 0) {
            scroll.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) continue;
            JSONArray items = row.optJSONArray("items");
            if (items == null || items.length() == 0) continue;
            rows.addView(buildRow(row.optString("title"), row.optString("id"), items));
        }
        boolean show = rows.getChildCount() > 0;
        scroll.setVisibility(show ? View.VISIBLE : View.GONE);
        Log.i(TAG, "bound " + rows.getChildCount() + " rows");
        if (hadFocus && show) focusFirst();
        if (show) {
            boolean first = !shownOnce;
            shownOnce = true;
            host.onShelfShown(first);
        }
    }

    private View buildRow(String title, String id, JSONArray items) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextColor(0xEEFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setShadowLayer(8 * dp, 0, 1 * dp, 0xAA000000);
        label.setPadding((int) (4 * dp), (int) (4 * dp), 0, (int) (6 * dp));
        row.addView(label);

        boolean square = "music".equals(id) || "playlists".equals(id) || "recentmusic".equals(id);
        RecyclerView list = new RecyclerView(ctx);
        list.setLayoutManager(new LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false));
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding((int) (4 * dp), (int) (4 * dp), (int) (40 * dp), (int) (8 * dp));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setItemAnimator(null);
        list.setAdapter(new CardAdapter(items, square));
        row.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private final JSONArray items;
        private final boolean square;

        CardAdapter(JSONArray items, boolean square) {
            this.items = items;
            this.square = square;
        }

        @Override public int getItemCount() { return items.length(); }

        @NonNull @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(square);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            JSONObject it = items.optJSONObject(position);
            if (it == null) return;
            h.bind(it, position == items.length() - 1);
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final ImageView art;
        final TextView title, sub, who, time;
        final View progressTrack, progressFill;
        final int w, h;
        JSONObject item;

        CardHolder(boolean square) {
            super(new FrameLayout(ctx));
            h = (int) (96 * dp);
            w = square ? h : (int) (171 * dp);
            card = (FrameLayout) itemView;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, h);
            lp.setMarginEnd((int) (14 * dp));
            card.setLayoutParams(lp);
            card.setFocusable(true);
            card.setClipToOutline(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1A1D24);
            bg.setCornerRadius(12 * dp);
            card.setBackground(bg);

            art = new ImageView(ctx);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(-1, -1));

            View scrim = new View(ctx);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x00000000, 0xD0000000}));
            card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout text = new LinearLayout(ctx);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding((int) (9 * dp), 0, (int) (9 * dp), (int) (8 * dp));
            title = line(13, 0xFFFFFFFF, true);
            sub = line(10.5f, 0xBBFFFFFF, false);
            text.addView(title);
            text.addView(sub);
            card.addView(text, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            who = new TextView(ctx);
            who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            who.setTextColor(0xFFFFFFFF);
            who.setPadding((int) (7 * dp), (int) (2 * dp), (int) (7 * dp), (int) (2 * dp));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(0x99000000);
            pill.setCornerRadius(10 * dp);
            who.setBackground(pill);
            FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            wl.setMargins((int) (7 * dp), (int) (7 * dp), 0, 0);
            card.addView(who, wl);

            time = new TextView(ctx);
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            time.setTextColor(0xFFFFFFFF);
            time.setPadding((int) (7 * dp), (int) (2 * dp), (int) (7 * dp), (int) (2 * dp));
            GradientDrawable tpill = new GradientDrawable();
            tpill.setColor(0x99000000);
            tpill.setCornerRadius(10 * dp);
            time.setBackground(tpill);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            tl.setMargins(0, (int) (7 * dp), (int) (7 * dp), 0);
            card.addView(time, tl);

            progressTrack = new View(ctx);
            progressTrack.setBackgroundColor(0x55FFFFFF);
            card.addView(progressTrack, new FrameLayout.LayoutParams(-1, (int) (3 * dp), Gravity.BOTTOM));
            progressFill = new View(ctx);
            progressFill.setBackgroundColor(0xFF7CC4FF);
            card.addView(progressFill, new FrameLayout.LayoutParams(0, (int) (3 * dp), Gravity.BOTTOM | Gravity.START));

            card.setOnFocusChangeListener((v, focused) -> {
                float s = focused ? 1.09f : 1f;
                v.animate().scaleX(s).scaleY(s).translationZ(focused ? 8 * dp : 0).setDuration(140).start();
                GradientDrawable b = (GradientDrawable) v.getBackground();
                b.setStroke(focused ? (int) (2.5f * dp) : 0, 0xF0FFFFFF);
                if (focused) {
                    lastCard = v;
                    host.onShelfFocused();
                    // Keep the focused row's title in view: scroll its row (card -> list -> row) to the top.
                    View row = (View) v.getParent().getParent();
                    if (row != null) {
                        int y = targetFor(row);
                        if (scroll instanceof ShelfScrollView) ((ShelfScrollView) scroll).glideTo(y);
                        else scroll.smoothScrollTo(0, y);
                    }
                }
            });
            card.setOnClickListener(v -> open(item));
        }

        private TextView line(float sp, int color, boolean bold) {
            TextView t = new TextView(ctx);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setShadowLayer(4 * dp, 0, 1 * dp, 0xCC000000);
            return t;
        }

        void bind(JSONObject it, boolean last) {
            item = it;
            title.setText(it.optString("title"));
            String s = it.optString("subtitle");
            sub.setText(s);
            sub.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
            JSONArray w2 = it.optJSONArray("who");
            if (w2 != null && w2.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < w2.length(); i++) {
                    if (i > 0) sb.append(" · ");
                    sb.append(w2.optString(i));
                }
                who.setText(sb);
                who.setVisibility(View.VISIBLE);
            } else who.setVisibility(View.GONE);
            long runtime = it.optLong("runtimeSec", 0), position = it.optLong("positionSec", 0);
            if (runtime > 0 && position > 0 && position < runtime) {
                boolean leftOff = "at".equals(ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                        .getString(CW_TIME_KEY, "left"));
                time.setText(leftOff ? "Left off at " + clock(position) : minutes(runtime - position) + " left");
                time.setVisibility(View.VISIBLE);
            } else time.setVisibility(View.GONE);
            double p = it.optDouble("progress", 0);
            boolean showP = p > 0.01 && p < 0.99;
            progressTrack.setVisibility(showP ? View.VISIBLE : View.GONE);
            progressFill.setVisibility(showP ? View.VISIBLE : View.GONE);
            if (showP) {
                ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
                lp.width = (int) (w * p);
                progressFill.setLayoutParams(lp);
            }
            // Landscape cards want the backdrop; square music cards the cover.
            String path = w > h ? it.optString("backdrop", it.optString("image")) : it.optString("image");
            client.image(art, path, w, h);
            // The row's last card: Right stops here instead of jumping to whatever is to the right on screen.
            card.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (last && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
                // Down from the bottom row goes straight to the dock (it is tucked, so a focus search may miss it).
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && isLastRow(v)) {
                    host.focusDock();
                    return true;
                }
                return false;
            });
        }
    }

    /** Launcher settings key: "left" (time left, default) or "at" (where you left off). */
    static final String CW_TIME_KEY = "cw_time";

    private static String minutes(long sec) {
        long m = Math.max(1, Math.round(sec / 60.0));
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
    }

    private static String clock(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }

    private boolean isLastRow(View card) {
        View row = (View) card.getParent().getParent();
        return rows.indexOfChild(row) == rows.getChildCount() - 1;
    }

    /** Back from the dock: the card that had focus last, or the first one. */
    void focusRemembered() {
        if (rows.getChildCount() > 0) scroll.setVisibility(View.VISIBLE);   // never leave the rows unreachable
        if (lastCard != null && lastCard.isAttachedToWindow() && lastCard.getVisibility() == View.VISIBLE) lastCard.requestFocus();
        else focusFirst();
    }

    // ---------- opening items ----------

    static final String JELLYFIN_ASKS_KEY = "jellyfin_asks_who";

    /** The Settings switch; until someone flips it, the theme's "jellyfin_asks_who" (config.json) decides. */
    static boolean jellyfinAsks(Context ctx) {
        android.content.SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        if (p.contains(JELLYFIN_ASKS_KEY)) return p.getBoolean(JELLYFIN_ASKS_KEY, false);
        java.io.File dir = Config.dir(ctx);
        java.io.File f = dir == null ? null : new java.io.File(dir, Config.FILE_NAME);
        if (f == null || !f.isFile()) return false;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 1 << 20)];
            int n = 0;
            for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
            return new org.json.JSONObject(new String(b, 0, n, "UTF-8")).optBoolean("jellyfin_asks_who", false);
        } catch (Exception e) {
            return false;
        }
    }

    private void open(JSONObject item) {
        if (item == null) return;
        JSONObject action = item.optJSONObject("action");
        if (action == null) return;
        String type = action.optString("type");
        try {
            if ("jellyfin".equals(type)) {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(new ComponentName("org.jellyfin.androidtv", "org.jellyfin.androidtv.ui.startup.StartupActivity"));
                i.putExtra("ItemId", action.optString("itemId"));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                ctx.startActivity(i);
                // Jellyfin's app can't be told which user to open as. When it is set to ask (its Login behavior:
                // "Show login screen"), remind whoever clicked which account holds this card's progress.
                if (jellyfinAsks(ctx)) {
                    org.json.JSONArray who = item.optJSONArray("who");
                    String person = who != null && who.length() == 1 ? who.optString(0) : Accounts.of(ctx).profile();
                    if (person != null && !person.isEmpty()) {
                        android.widget.Toast.makeText(ctx, "Pick " + person + " in Jellyfin", android.widget.Toast.LENGTH_LONG).show();
                    }
                }
            } else if ("music".equals(type) || "playlist".equals(type)) {
                // Music from Home: Now Playing starts it (Music Assistant or Navidrome, per the Music screen's source).
                boolean playlist = "playlist".equals(type);
                Intent m = new Intent(ctx, NowPlayingActivity.class)
                        .putExtra(MusicActivity.EXTRA_KIND, playlist ? "playlist" : "album")
                        .putExtra(MusicActivity.EXTRA_NAME, playlist ? action.optString("playlist") : action.optString("album"))
                        .putExtra(MusicActivity.EXTRA_ARTIST, action.optString("artist"))
                        .putExtra(MusicActivity.EXTRA_ND_ID, action.optString("id"))
                        .putExtra(MusicActivity.EXTRA_URI, action.optString("uri"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(m);
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "open " + type + ": " + e);
        }
    }

    private static final String SYMFONIUM = "app.symfonik.music.player";

    private void openPlaylist(String name) {
        Intent play = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        play.setPackage(SYMFONIUM);
        play.putExtra(SearchManager.QUERY, name);
        play.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE);
        play.putExtra("android.intent.extra.playlist", name);
        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(play);
        } catch (ActivityNotFoundException e) {
            openMusic(name, "");
        }
    }

    private void openMusic(String album, String artist) {
        // Symfonium answers the standard "play from search" request (what voice assistants send) with an album focus.
        Intent play = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        play.setPackage(SYMFONIUM);
        play.putExtra(SearchManager.QUERY, album + " " + artist);
        play.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE);
        play.putExtra(MediaStore.EXTRA_MEDIA_ALBUM, album);
        play.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist);
        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(play);
            return;
        } catch (ActivityNotFoundException ignored) {
            // older or newer Symfonium without the activity: just open the app
        }
        Intent launch = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(SYMFONIUM);
        if (launch == null) launch = ctx.getPackageManager().getLaunchIntentForPackage(SYMFONIUM);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launch);
        }
    }
}
