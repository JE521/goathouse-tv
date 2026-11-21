package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * goathouse: "Who's watching?" — Family (the merged household rows) or one Jellyfin person. Hidden Jellyfin users
 * are not listed; "Other…" takes a typed name for them. Opened by the remote's profile button or from Settings.
 */
public final class ProfileActivity extends Activity {
    private FeedClient feed;
    private Accounts acc;
    private float dp;
    private LinearLayout row;
    private HorizontalScrollView scroll;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        acc = Accounts.of(this);
        feed = FeedClient.forSetup(this);
        setContentView(SettingsBackdrop.wrap(this, build()));
        row.addView(avatar("Family", null, "👪", acc.profile().isEmpty(), v -> choose("")));
        if (!acc.hasJellyfin()) {
            status.setText("Set up Jellyfin in Settings → Accounts to pick a person.");
            focusFirst();
            return;
        }
        feed.people(people -> {
            if (people != null) for (int i = 0; i < people.length(); i++) {
                JSONObject p = people.optJSONObject(i);
                if (p == null || p.optBoolean("hidden")) continue;
                String name = p.optString("name");
                row.addView(avatar(name, p.optString("avatar"), null, name.equalsIgnoreCase(acc.profile()), v -> choose(name)));
            }
            String cur = acc.profile();
            boolean hiddenActive = !cur.isEmpty() && people != null && isHidden(people, cur);
            row.addView(avatar(hiddenActive ? cur : "Other…", null, "+", hiddenActive, v -> askName()));
            focusFirst();
        });
    }

    private static boolean isHidden(JSONArray people, String name) {
        for (int i = 0; i < people.length(); i++) {
            JSONObject p = people.optJSONObject(i);
            if (p != null && p.optString("name").equalsIgnoreCase(name)) return p.optBoolean("hidden");
        }
        return false;
    }

    private void focusFirst() {
        row.post(() -> {
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (Boolean.TRUE.equals(c.getTag())) { c.requestFocus(); return; }
            }
            if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
        });
    }

    private void choose(String name) {
        acc.setProfile(name);
        SharedPreferences c = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        c.edit().putLong(Config.SETTINGS_REVISION_KEY, c.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        finish();
        overridePendingTransition(0, android.R.anim.fade_out);
    }

    private void askName() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Jellyfin username");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        int pad = (int) (20 * dp);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Who's watching?")
                .setView(box)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> {
            input.requestFocus();
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                String typed = input.getText().toString();
                if (typed.trim().isEmpty()) return;
                new Thread(() -> {
                    String found = feed.findUserNow(typed);
                    runOnUiThread(() -> {
                        if (found == null) { input.setError("No Jellyfin user called that"); return; }
                        d.dismiss();
                        choose(found);
                    });
                }).start();
            });
        });
        d.show();
    }

    // ---------- views ----------

    private View build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        TextView title = text(34, Color.WHITE, true);
        title.setText("Who's watching?");
        title.setGravity(Gravity.CENTER);
        page.addView(title);
        status = text(16, 0x99FFFFFF, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, (int) (8 * dp), 0, 0);
        page.addView(status);
        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        // Room on every side for the focused avatar's zoom and ring; the ends can scroll to the middle.
        int side = getResources().getDisplayMetrics().widthPixels / 2 - (int) (85 * dp);
        row.setPadding(side, (int) (56 * dp), side, (int) (36 * dp));
        scroll.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(scroll, lp);
        return page;
    }

    private View avatar(String name, String imageKey, String glyph, boolean current, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setFocusable(true);
        item.setClipChildren(false);
        item.setTag(current);
        int size = (int) (124 * dp);
        item.setPadding(0, (int) (12 * dp), 0, (int) (24 * dp));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams((int) (170 * dp), -2);
        item.setLayoutParams(ilp);

        FrameLayout circle = new FrameLayout(this);
        circle.setClipToOutline(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colorsFor(name));
        bg.setShape(GradientDrawable.OVAL);
        circle.setBackground(bg);
        TextView letter = text(glyph != null ? 46 : 52, Color.WHITE, true);
        letter.setText(glyph != null ? glyph : name.substring(0, 1).toUpperCase());
        letter.setGravity(Gravity.CENTER);
        circle.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (imageKey != null && !imageKey.isEmpty()) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            circle.addView(img, new FrameLayout.LayoutParams(-1, -1));
            feed.image(img, imageKey, size, size);
        }
        View ring = new View(this);
        GradientDrawable rg = new GradientDrawable();
        rg.setShape(GradientDrawable.OVAL);
        rg.setStroke((int) (4 * dp), Color.WHITE);
        ring.setBackground(rg);
        ring.setAlpha(0f);
        circle.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        item.addView(circle, new LinearLayout.LayoutParams(size, size));

        TextView label = text(18, 0xCCFFFFFF, true);
        label.setText(current ? "✓ " + name : name);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, (int) (14 * dp), 0, 0);
        label.setSingleLine(true);
        item.addView(label, new LinearLayout.LayoutParams(-1, -2));

        item.setOnFocusChangeListener((v, f) -> {
            float s = f ? 1.12f : 1f;
            // Zoom the whole avatar (circle + name): zooming the clipped circle itself shaves its top off.
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(circle.getTop() + circle.getHeight() / 2f);
            v.animate().scaleX(s).scaleY(s).setDuration(150).start();
            ring.animate().alpha(f ? 1f : 0f).setDuration(150).start();
            label.setTextColor(f ? Color.WHITE : 0xCCFFFFFF);
            // Keep the focused person in the middle of the screen (tvOS carousel), never at a clipped edge.
            if (f) v.post(() -> scroll.smoothScrollTo(v.getLeft() + v.getWidth() / 2 - scroll.getWidth() / 2, 0));
        });
        item.setOnClickListener(click);
        return item;
    }

    private static int[] colorsFor(String name) {
        int[][] palette = {{0xFF5B7CFF, 0xFF9AD4FF}, {0xFFFF7A59, 0xFFFFC371}, {0xFF7B5BFF, 0xFFE39BFF},
                {0xFF2BB673, 0xFF9BE15D}, {0xFFE0457B, 0xFFFF9A8B}, {0xFF1FA2FF, 0xFF12D8FA}};
        return palette[Math.abs(name.hashCode()) % palette.length];
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }
}
