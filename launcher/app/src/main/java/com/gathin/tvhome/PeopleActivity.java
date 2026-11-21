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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * goathouse: who is in Family mode on this TV. Lists the Jellyfin people shown on Jellyfin's login screen (plus any
 * hidden ones already added); "Add someone hidden…" takes a typed username.
 */
public final class PeopleActivity extends Activity {
    private FeedClient feed;
    private Accounts acc;
    private float dp;
    private LinearLayout list;
    private TextView status;
    private final Set<String> family = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        acc = Accounts.of(this);
        feed = FeedClient.forSetup(this);
        family.addAll(acc.list(Accounts.JF_USERS));
        setContentView(SettingsBackdrop.wrap(this, build()));
        if (!acc.hasJellyfin()) { status.setText("Set up Jellyfin in Settings → Accounts first."); return; }
        status.setText("Loading…");
        feed.people(this::show);
    }

    private void show(JSONArray people) {
        list.removeAllViews();
        if (people == null) { status.setText("Can't reach Jellyfin."); return; }
        status.setText("Family mode shows everyone ticked here. None ticked = everyone.");
        View first = null;
        for (int i = 0; i < people.length(); i++) {
            JSONObject p = people.optJSONObject(i);
            if (p == null) continue;
            String name = p.optString("name");
            boolean hidden = p.optBoolean("hidden");
            if (hidden && !family.contains(name)) continue;   // hidden people only appear once added by name
            View r = personRow(name, p.optString("avatar"), hidden);
            list.addView(r);
            if (first == null) first = r.findViewWithTag("toggle");
        }
        TextView add = new TextView(this);
        add.setText("Add someone hidden…");
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        add.setTextColor(getColorStateList(R.color.settings_text));
        add.setBackgroundResource(R.drawable.settings_choice_bg);
        add.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.settings_lift));
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding((int) (24 * dp), 0, (int) (24 * dp), 0);
        add.setFocusable(true);
        add.setOnClickListener(v -> askName());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, (int) (60 * dp));
        alp.topMargin = (int) (18 * dp);
        list.addView(add, alp);
        if (first != null) first.requestFocus();
        else add.requestFocus();
    }

    private View personRow(String name, String avatarKey, boolean hidden) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
        row.setClipChildren(false);

        int size = (int) (56 * dp);
        FrameLayout circle = new FrameLayout(this);
        circle.setClipToOutline(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFF3A4252);
        circle.setBackground(bg);
        TextView letter = text(24, Color.WHITE, true);
        letter.setText(name.substring(0, 1).toUpperCase());
        letter.setGravity(Gravity.CENTER);
        circle.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        if (avatarKey != null && !avatarKey.isEmpty()) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            circle.addView(img, new FrameLayout.LayoutParams(-1, -1));
            feed.image(img, avatarKey, size, size);
        }
        row.addView(circle, new LinearLayout.LayoutParams(size, size));

        TextView label = text(19, Color.WHITE, true);
        label.setText(hidden ? name + "  (hidden)" : name);
        label.setPadding((int) (18 * dp), 0, (int) (18 * dp), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView toggle = text(16, Color.WHITE, true);
        toggle.setTag("toggle");
        toggle.setFocusable(true);
        toggle.setGravity(Gravity.CENTER);
        toggle.setMinWidth((int) (170 * dp));
        toggle.setPadding((int) (20 * dp), (int) (10 * dp), (int) (20 * dp), (int) (10 * dp));
        paint(toggle, family.contains(name), false);
        toggle.setOnFocusChangeListener((v, f) -> {
            paint(toggle, family.contains(name), f);
            v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(120).start();
        });
        toggle.setOnClickListener(v -> {
            if (!family.remove(name)) family.add(name);
            paint(toggle, family.contains(name), toggle.isFocused());
            save();
        });
        row.addView(toggle);
        return row;
    }

    private void paint(TextView t, boolean on, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(24 * dp);
        if (on) {
            g.setColor(focused ? 0xFFFFFFFF : 0xFFE6E9F0);
            t.setTextColor(0xFF0B0D12);
            t.setText("✓  In Family");
        } else {
            g.setColor(focused ? 0x40FFFFFF : 0x14FFFFFF);
            g.setStroke((int) (1.5f * dp), focused ? 0xCCFFFFFF : 0x44FFFFFF);
            t.setTextColor(focused ? Color.WHITE : 0xBBFFFFFF);
            t.setText("Add to Family");
        }
        t.setBackground(g);
    }

    private void save() {
        List<String> names = new ArrayList<>(family);
        acc.putList(Accounts.JF_USERS, names);
        SharedPreferences c = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        c.edit().putLong(Config.SETTINGS_REVISION_KEY, c.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void askName() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Jellyfin username");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = (int) (20 * dp);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add someone hidden")
                .setView(box)
                .setPositiveButton("Add", null)
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
                        family.add(found);
                        save();
                        feed.people(this::show);
                    });
                }).start();
            });
        });
        d.show();
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setPadding((int) (96 * dp), (int) (44 * dp), (int) (124 * dp), (int) (44 * dp));
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        TextView title = text(32, Color.WHITE, true);
        title.setText("People");
        status = text(15, 0x99FFFFFF, false);
        status.setPadding(0, (int) (6 * dp), 0, (int) (20 * dp));
        page.addView(title);
        page.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        page.addView(list);
        scroll.addView(page);
        return scroll;
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }
}
