package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * goathouse: pick which Immich albums (from tv-feed) this TV uses for its background and its screensaver.
 * Every toggle saves to tv-feed at once and re-syncs the pictures; Home rebuilds with them when you go back.
 */
public final class PhotosActivity extends Activity {
    private FeedClient feed;
    private float dp;
    private LinearLayout list;
    private TextView status;
    private final Set<String> wallpaper = new LinkedHashSet<>(), screensaver = new LinkedHashSet<>();
    private int pendingSaves;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        feed = FeedClient.fromTheme(this);
        setContentView(SettingsBackdrop.wrap(this, buildPage()));
        if (feed == null) {
            status.setText("This TV has no photo feed configured.");
            return;
        }
        status.setText("Loading albums…");
        feed.photoChoice(choice -> {
            if (choice != null) {
                addAll(wallpaper, choice.optJSONArray("wallpaper"));
                addAll(screensaver, choice.optJSONArray("screensaver"));
            }
            feed.albums(this::showAlbums);
        });
    }

    private static void addAll(Set<String> into, JSONArray a) {
        if (a != null) for (int i = 0; i < a.length(); i++) into.add(a.optString(i));
    }

    private void showAlbums(JSONArray albums) {
        if (albums == null) { status.setText("Can't reach the photo feed."); return; }
        if (albums.length() == 0) { status.setText("No albums yet: add some to the Immich admin account."); return; }
        status.setText("Albums from Immich. Changes apply right away.");
        View first = null;
        for (int i = 0; i < albums.length(); i++) {
            JSONObject a = albums.optJSONObject(i);
            if (a == null) continue;
            View row = albumRow(a);
            list.addView(row);
            if (first == null) first = row.findViewWithTag("toggle");
        }
        if (first != null) first.requestFocus();
    }

    // ---------- views ----------

    private View buildPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B0D12);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding((int) (72 * dp), (int) (40 * dp), (int) (84 * dp), (int) (40 * dp));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        TextView title = text(30, Color.WHITE, true);
        title.setText("Photos");
        status = text(14, 0x99FFFFFF, false);
        status.setPadding(0, (int) (6 * dp), 0, (int) (22 * dp));
        page.addView(title);
        page.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        page.addView(list);
        scroll.addView(page);
        return scroll;
    }

    private View albumRow(JSONObject a) {
        String id = a.optString("id");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        row.setClipChildren(false);

        FrameLayout coverBox = new FrameLayout(this);
        coverBox.setClipToOutline(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A1D24);
        bg.setCornerRadius(10 * dp);
        coverBox.setBackground(bg);
        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        int cw = (int) (128 * dp), ch = (int) (72 * dp);
        row.addView(coverBox, new LinearLayout.LayoutParams(cw, ch));
        String coverPath = a.optString("cover", "");
        if (!coverPath.isEmpty() && !"null".equals(coverPath)) feed.image(cover, coverPath, cw, ch);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding((int) (20 * dp), 0, (int) (20 * dp), 0);
        TextView name = text(18, Color.WHITE, true);
        name.setText(a.optString("name"));
        TextView count = text(13, 0x88FFFFFF, false);
        int n = a.optInt("count");
        count.setText(n + (n == 1 ? " photo" : " photos"));
        names.addView(name);
        names.addView(count);
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(toggle("Background", id, wallpaper));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams((int) (12 * dp), 1));
        row.addView(toggle("Screensaver", id, screensaver));
        return row;
    }

    private TextView toggle(String label, String albumId, Set<String> set) {
        TextView t = text(15, Color.WHITE, true);
        t.setTag("toggle");
        t.setFocusable(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (20 * dp), (int) (10 * dp), (int) (20 * dp), (int) (10 * dp));
        t.setMinWidth((int) (150 * dp));
        paint(t, label, set.contains(albumId), false);
        t.setOnFocusChangeListener((v, f) -> {
            paint(t, label, set.contains(albumId), f);
            v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(120).start();
        });
        t.setOnClickListener(v -> {
            if (!set.remove(albumId)) set.add(albumId);
            paint(t, label, set.contains(albumId), t.isFocused());
            save();
        });
        return t;
    }

    private void paint(TextView t, String label, boolean on, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(24 * dp);
        if (on) {
            g.setColor(focused ? 0xFFFFFFFF : 0xFFE6E9F0);
            t.setTextColor(0xFF0B0D12);
            t.setText("✓  " + label);
        } else {
            g.setColor(focused ? 0x40FFFFFF : 0x14FFFFFF);
            g.setStroke((int) (1.5f * dp), focused ? 0xCCFFFFFF : 0x44FFFFFF);
            t.setTextColor(focused ? Color.WHITE : 0xBBFFFFFF);
            t.setText(label);
        }
        t.setBackground(g);
    }

    private void save() {
        try {
            JSONObject choice = new JSONObject()
                    .put("wallpaper", new JSONArray(wallpaper))
                    .put("screensaver", new JSONArray(screensaver));
            pendingSaves++;
            status.setText("Saving…");
            feed.savePhotoChoice(choice, saved -> {
                if (--pendingSaves > 0) return;
                if (saved == null) { status.setText("Couldn't save: is the photo feed up?"); return; }
                status.setText("Updating pictures…");
                feed.syncPhotos(this, changed -> {
                    // Home reads the wallpapers folder when it builds: ask it to rebuild on return.
                    SharedPreferences c = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
                    c.edit().putLong(Config.SETTINGS_REVISION_KEY, c.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
                    status.setText("Saved. Albums from Immich. Changes apply right away.");
                });
            });
        } catch (Exception e) {
            status.setText("Couldn't save.");
        }
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(false);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setLayoutParams(new ViewGroup.LayoutParams(-2, -2));
        return t;
    }
}
