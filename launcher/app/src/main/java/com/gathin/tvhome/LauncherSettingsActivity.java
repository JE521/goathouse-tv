package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Remote-friendly settings owned by the launcher, laid out the tvOS way (goathouse): a list of categories, each its
 * own page; rows show their current value on the right and open a checkmark list instead of rows of buttons. The
 * left half names the page and describes the focused row. Back goes up a level.
 */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};

    private SharedPreferences choices;
    private Accounts acc;
    private Config config;
    private float dp;
    private ImageView heroIcon;
    private TextView heroTitle, heroDescription;
    private LinearLayout list;
    private ScrollView scroll;

    private static final class Page {
        final String title;
        final int icon;
        final Runnable build;
        int focus;
        Page(String title, int icon, Runnable build) { this.title = title; this.icon = icon; this.build = build; }
    }

    private final ArrayDeque<Page> stack = new ArrayDeque<>();
    private int rowIndex;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        acc = Accounts.of(this);
        config = Config.load(this);
        setContentView(SettingsBackdrop.wrap(this, buildFrame()));
        push(new Page(getString(R.string.launcher_settings), R.drawable.ic_settings, this::mainPage));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!stack.isEmpty()) rebuild();   // values may have changed in Accounts, Photos, Who's watching…
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            stack.pop();
            rebuild();
            return;
        }
        super.onBackPressed();
    }

    // =============================== pages ===============================

    private void mainPage() {
        nav("Accounts", serverSummary(), "Servers, music logins and linked accounts.", R.drawable.ic_person, this::accountsPage);
        if (acc.hasJellyfin()) nav("People", acc.profile().isEmpty() ? "Family" : acc.profile(),
                "Who's watching, and who is part of Family.", R.drawable.ic_people, this::peoplePage);
        nav("Home Screen", "", "Apps per row, the dock, rows and motion.", R.drawable.ic_apps, this::homePage);
        nav("Music", "ma".equals(MusicSource.chosen(this)) ? "Music Assistant" : "Navidrome",
                "Where the Music screen plays from, and what Family uses.", R.drawable.ic_music, this::musicPage);
        nav("Photos & Clock", "", "Background and screensaver pictures, and the clock.", R.drawable.ic_picture, this::photosPage);
        nav("Remote", "", "What each button on the remote does.", R.drawable.ic_remote, this::remotePage);
        nav("System", "", "TV settings, the default home app and start-up.", R.drawable.ic_home_settings, this::systemPage);
    }

    private void accountsPage() {
        header("Servers");
        row("Set up from your phone", serverSummary(),
                "Scan a code with your phone to add Jellyfin, Navidrome, Immich, Home Assistant and Music Assistant.",
                true, v -> startActivity(new Intent(this, AccountsActivity.class)));
        header("Music logins");
        List<String[]> logins = acc.musicLogins();
        String serverUser = acc.get(Accounts.ND_USER);
        for (String[] l : logins) {
            boolean shared = l[0].equalsIgnoreCase(serverUser);
            row(l[0], shared ? "Home screen" : "Remove",
                    shared ? "The shared login the Home screen's music rows use. Select to change it from your phone."
                            : "A Navidrome login added on this TV. Select to remove it.",
                    false, shared ? v -> startActivity(new Intent(this, AccountsActivity.class)) : v -> confirm("Remove " + l[0] + "?", () -> { acc.removeMusicLogin(l[0]); rebuild(); }));
        }
        row("Add a music login…", "", "Sign someone into Navidrome on this TV. People are also asked the first time they open Music.",
                false, v -> MusicLogin.show(this, "", null, u -> rebuild()));
        if (acc.hasJellyfin()) {
            header("People");
            nav("Linked accounts", "", "Match each person to their music login when the names differ.", R.drawable.ic_people, this::linksPage);
        }
    }

    private JSONArray cachedPeople;

    private void linksPage() {
        if (cachedPeople == null) {
            row("Loading…", "", "", false, null);
            FeedClient.forSetup(this).people(p -> { cachedPeople = p == null ? new JSONArray() : p; rebuild(); });
            return;
        }
        List<String[]> logins = acc.musicLogins();
        for (int i = 0; i < cachedPeople.length(); i++) {
            JSONObject p = cachedPeople.optJSONObject(i);
            if (p == null) continue;
            String person = p.optString("name");
            if (p.optBoolean("hidden") && !acc.list(Accounts.JF_USERS).contains(person) && acc.linkedMusicUser(person).isEmpty()) continue;
            String linked = acc.linkedMusicUser(person);
            String same = acc.musicLogin(person) != null ? acc.musicLogin(person)[0] : null;
            String value = !linked.isEmpty() ? linked : same != null ? same + " (same name)" : "Not linked";
            String[] options = new String[logins.size() + 1];
            options[0] = "Same name as on Jellyfin";
            int current = 0;
            for (int j = 0; j < logins.size(); j++) {
                options[j + 1] = logins.get(j)[0];
                if (logins.get(j)[0].equalsIgnoreCase(linked)) current = j + 1;
            }
            choice(person, value, "Whose music plays when " + person + " is picked on Home.", options, current,
                    k -> acc.linkMusicUser(person, k == 0 ? "" : logins.get(k - 1)[0]));
        }
    }

    private void peoplePage() {
        row("Who's watching", acc.profile().isEmpty() ? "Family" : acc.profile(),
                "Pick a person: the Home rows and Music follow them. The remote's profile button opens this too.",
                true, v -> startActivity(new Intent(this, ProfileActivity.class)));
        row("Family members", acc.list(Accounts.JF_USERS).isEmpty() ? "Everyone" : acc.list(Accounts.JF_USERS).size() + " people",
                "Whose shows Family mode merges on the Home rows.", true, v -> startActivity(new Intent(this, PeopleActivity.class)));
        boolean asks = MediaShelf.jellyfinAsks(this);
        toggle("Jellyfin asks who's watching", asks,
                "Turn on after setting Jellyfin's own Settings → Authentication → Login behavior to \"Show login screen\". "
                        + "Jellyfin can't be told who is watching, so opening someone's card reminds you which account to pick.",
                on -> choices.edit().putBoolean(MediaShelf.JELLYFIN_ASKS_KEY, on).apply());
    }

    private void homePage() {
        if (config.launcherSettingsButton) row(getString(R.string.reorder), "", "Move apps around the dock and grid.", false, v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        int columns = Config.load(this).columns;
        choice(getString(R.string.columns_label), String.valueOf(columns), "Apps in the dock and in each row of the grid.",
                new String[]{"4", "5", "6", "7"}, columns - 4, k -> { choices.edit().putInt("columns", k + 4).apply(); changed(); });
        int rows = Config.load(this).rowsBelowDock;
        choice(getString(R.string.rows_below_dock_label), String.valueOf(rows), "Rows of apps that peek out under the dock.",
                new String[]{"0", "1", "2", "3"}, rows, k -> { choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, k).apply(); changed(); });
        int dock = Config.load(this).dockOpacity;
        int dockIdx = 0;
        for (int i = 0; i < DOCK_OPACITIES.length; i++) if (DOCK_OPACITIES[i] == dock) dockIdx = i;
        choice(getString(R.string.dock_opacity_label), dock + "%", "How solid the band behind the dock is.",
                new String[]{"100%", "70%", "50%", "30%"}, dockIdx,
                k -> { choices.edit().putInt(Config.DOCK_OPACITY_KEY, DOCK_OPACITIES[k]).apply(); changed(); });
        boolean leftOff = "at".equals(choices.getString(MediaShelf.CW_TIME_KEY, "left"));
        choice("Continue Watching shows", leftOff ? "Where you left off" : "Time left",
                "The little label in the corner of each Continue Watching card.",
                new String[]{"Time left", "Where you left off"}, leftOff ? 1 : 0,
                k -> { choices.edit().putString(MediaShelf.CW_TIME_KEY, k == 1 ? "at" : "left").apply(); changed(); });
        header("Motion");
        Config c = Config.load(this);
        boolean animations = c.animations, advanced = c.animations && c.advancedEffects;
        toggle("Animations", animations, "Tiles glide and the highlight springs between them.", on -> {
            choices.edit().putBoolean("animations", on).putBoolean("advanced_effects", on && advanced).apply();
            changed();
        });
        if (animations) toggle(getString(R.string.advanced_effects), advanced, getString(R.string.advanced_effects_warning), on -> {
            if (!on) { choices.edit().putBoolean("advanced_effects", false).apply(); changed(); return; }
            new AlertDialog.Builder(this).setTitle(R.string.advanced_effects).setMessage(R.string.advanced_effects_warning)
                    .setPositiveButton(R.string.enable, (d, w) -> { choices.edit().putBoolean("advanced_effects", true).apply(); changed(); rebuild(); })
                    .setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private void musicPage() {
        boolean nd = "nd".equals(MusicSource.chosen(this));
        choice("Music screen plays from", nd ? "Navidrome" : "Music Assistant",
                "Music Assistant plays through this TV's speaker player; Navidrome plays straight from your library on the TV.",
                new String[]{"Music Assistant", "Navidrome"}, nd ? 1 : 0,
                k -> choices.edit().putString(MusicSource.PREF, k == 1 ? "nd" : "ma").apply());
        List<String[]> logins = acc.musicLogins();
        if (!logins.isEmpty()) {
            String family = acc.familyMusicUser();
            String[] names = new String[logins.size()];
            int cur = 0;
            for (int i = 0; i < logins.size(); i++) { names[i] = logins.get(i)[0]; if (names[i].equalsIgnoreCase(family)) cur = i; }
            choice("Family's music account", family.isEmpty() ? "None" : family,
                    "Whose Navidrome library plays when Home is on Family.", names, cur, k -> acc.setFamilyMusicUser(names[k]));
        }
        if (nd) {
            String q = choices.getString(LocalPlayer.QUALITY_PREF, "0");
            int qi = 0;
            for (int i = 0; i < LocalPlayer.QUALITY_VALUES.length; i++) if (LocalPlayer.QUALITY_VALUES[i].equals(q)) qi = i;
            choice("Streaming quality", LocalPlayer.QUALITY_LABELS[qi],
                    "Original plays your files as they are. Lower settings ask Navidrome for a smaller MP3, for slow Wi-Fi.",
                    LocalPlayer.QUALITY_LABELS, qi, k -> choices.edit().putString(LocalPlayer.QUALITY_PREF, LocalPlayer.QUALITY_VALUES[k]).apply());
        }
        boolean takeover = choices.getBoolean(SendspinService.TAKEOVER_KEY, true);
        toggle("Show Now Playing when music starts", takeover,
                "When something is cast to this TV from Music Assistant, its Now Playing screen comes up over whatever is on.",
                on -> choices.edit().putBoolean(SendspinService.TAKEOVER_KEY, on).apply());
        row("Speaker name", SendspinService.playerName(this), "How this TV appears in Music Assistant.", false, v -> renameSpeaker());
    }

    /** Rename this TV's Music Assistant player; it reconnects under the new name (same player, same history). */
    private void renameSpeaker() {
        android.widget.EditText name = new android.widget.EditText(this);
        name.setSingleLine(true);
        name.setText(SendspinService.playerName(this));
        name.setSelectAllOnFocus(true);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(name);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Speaker name")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    SharedPreferences.Editor e = acc.edit();
                    acc.put(e, Accounts.PLAYER_NAME, n);
                    acc.bump(e);
                    e.apply();
                    stopService(new Intent(this, SendspinService.class));
                    SendspinService.startIfConfigured(this);
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void photosPage() {
        if (acc.hasImmich()) row("Background & screensaver albums", "", "Pick Immich albums for the Home background and the screensaver.",
                true, v -> startActivity(new Intent(this, PhotosActivity.class)));
        wallpaperChoice();
        String clock = Config.load(this).clockFormat;
        int ci = 0;
        for (int i = 0; i < CLOCK_FORMATS.length; i++) if (CLOCK_FORMATS[i].equals(clock)) ci = i;
        String[] clockLabels = {getString(R.string.clock_format_system), getString(R.string.clock_format_12), getString(R.string.clock_format_24)};
        choice(getString(R.string.dream_name), clockLabels[ci], "12- or 24-hour time on Home and the screensaver.", clockLabels, ci,
                k -> { choices.edit().putString(Config.CLOCK_FORMAT_KEY, CLOCK_FORMATS[k]).apply(); changed(); });
    }

    private void wallpaperChoice() {
        File dir = Config.dir(this);
        List<File> images = new ArrayList<>(WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers")));
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        String[] labels = new String[images.size() + 2];
        labels[0] = getString(R.string.wallpaper_theme_default);
        for (int i = 0; i < images.size(); i++) labels[i + 1] = images.get(i).getName();
        labels[labels.length - 1] = getString(R.string.wallpaper_none);
        int idx = current == null ? 0 : "none".equals(current) ? labels.length - 1 : 0;
        for (int i = 0; i < images.size(); i++) if (images.get(i).getName().equals(current)) idx = i + 1;
        choice(getString(R.string.wallpaper_label), labels[idx], "One fixed picture instead of the rotating albums or theme.", labels, idx, k -> {
            if (k == 0) choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
            else if (k == labels.length - 1) choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
            else choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, images.get(k - 1).getName()).apply();
            changed();
        });
    }

    private void remotePage() {
        java.util.Map<Integer, String> themeApps = RemoteKeys.parseAppKeys(Config.load(this).appKeys);
        // Installed TV apps, for "open an app" choices.
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        row("Remote", ButtonMap.remoteNames(), "The remote this TV is paired with. Its buttons are listed below; press one to"
                + " move it to the top, or use Learn a button for any that are missing.", false, null);
        boolean pressedHeader = false, otherHeader = false;
        for (String key : ButtonMap.buttons(this)) {
            boolean pressed = ButtonMap.wasSeen(this, key);
            if (pressed && !pressedHeader) { header("Buttons you've pressed"); pressedHeader = true; }
            if (!pressed && !otherHeader) { header(pressedHeader ? "Other buttons this remote may have" : "Buttons this remote may have"); otherHeader = true; }
            int code = android.view.KeyEvent.keyCodeFromString(key);
            String themeApp = themeApps.get(code);
            String action = ButtonMap.action(this, key);
            String[] options = new String[ButtonMap.ACTIONS.length + 1 + apps.size()];
            String[] values = new String[options.length];
            String builtIn = ButtonMap.defaultAction(this, key);
            String fallback = builtIn != null ? ButtonMap.actionLabel(this, builtIn, themeApp, key) : ButtonMap.actionLabel(this, null, themeApp, key);
            options[0] = "Default (" + fallback + ")";
            values[0] = "";
            for (int i = 0; i < ButtonMap.ACTIONS.length; i++) { options[i + 1] = ButtonMap.ACTION_LABELS[i]; values[i + 1] = ButtonMap.ACTIONS[i]; }
            for (int i = 0; i < apps.size(); i++) {
                options[ButtonMap.ACTIONS.length + 1 + i] = "Open " + apps.get(i)[0];
                values[ButtonMap.ACTIONS.length + 1 + i] = "app:" + apps.get(i)[1];
            }
            int current = 0;
            if (ButtonMap.isCustom(this, key)) for (int i = 1; i < values.length; i++) if (values[i].equals(action)) current = i;
            choice(ButtonMap.buttonLabel(key), ButtonMap.actionLabel(this, action, themeApp, key),
                    "What the " + ButtonMap.buttonLabel(key) + " button does.", options, current,
                    k -> ButtonMap.set(this, key, values[k].isEmpty() ? null : values[k]));
        }
        row("Learn a button…", "", "Press any other button on your remote to add it here, then choose what it does.", false, v -> {
            AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Press a button on the remote")
                    .setMessage("Any button except the arrows, OK, Back, Home, volume and power.")
                    .setNegativeButton(android.R.string.cancel, (x, w) -> InputKeyService.learn = null)
                    .create();
            d.setOnDismissListener(x -> InputKeyService.learn = null);
            InputKeyService.learn = code -> {
                String key = android.view.KeyEvent.keyCodeToString(code);
                if (!ButtonMap.buttons(this).contains(key)) ButtonMap.set(this, key, "none");
                d.dismiss();
                Toast.makeText(this, ButtonMap.buttonLabel(key) + " added", Toast.LENGTH_SHORT).show();
                rebuild();
            };
            d.show();
        });
    }

    private void systemPage() {
        row(getString(R.string.tv_settings), "", "Android TV's own settings.", true, v -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        row(getString(R.string.home_settings), "", getString(R.string.home_settings_help), true, v -> open(new Intent(this, HomeSettingsActivity.class)));
        if (config.openOnStartChoice) openOnStartChoice();
        boolean custom = hasOnTvChoices();
        row(getString(R.string.follow_theme), custom ? "" : "In use", getString(R.string.follow_theme_hint), false, !custom ? null : v -> {
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations").remove("advanced_effects")
                    .remove(Config.WALLPAPER_CHOICE_KEY).remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY)
                    .remove(OpenOnStart.KEY).commit();
            changed();
            rebuild();
        });
    }

    private void openOnStartChoice() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String current = choices.getString(OpenOnStart.KEY, "");
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        choice(getString(R.string.open_on_start_label), labels[checked], "An app to open by itself when the TV starts.", labels, checked, k -> {
            if (k == 0) choices.edit().remove(OpenOnStart.KEY).apply();
            else {
                OpenOnStart.skipThisBoot(this);
                choices.edit().putString(OpenOnStart.KEY, apps.get(k - 1)[1]).apply();
            }
        });
    }

    private String serverSummary() {
        int n = (acc.hasJellyfin() ? 1 : 0) + (acc.hasNavidrome() ? 1 : 0) + (acc.hasImmich() ? 1 : 0)
                + (acc.hasHomeAssistant() ? 1 : 0) + (acc.hasMusicAssistant() ? 1 : 0);
        return n == 0 ? "Not set up" : n + " connected";
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(OpenOnStart.KEY);
    }

    /** A choice was saved: Home rebuilds when it next resumes because the change count moved. */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private void confirm(String question, Runnable yes) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(question)
                .setPositiveButton(android.R.string.ok, (d, w) -> yes.run()).setNegativeButton(android.R.string.cancel, null).show();
    }

    // =============================== page stack ===============================

    private void push(Page p) {
        if (!stack.isEmpty()) stack.peek().focus = focusedIndex();
        stack.push(p);
        p.focus = 0;
        rebuild();
    }

    private void rebuild() {
        Page p = stack.peek();
        if (p == null) return;
        list.removeAllViews();
        rowIndex = 0;
        heroIcon.setImageResource(p.icon);
        heroTitle.setText(p.title);
        heroDescription.setText("");
        p.build.run();
        int target = Math.min(p.focus, Math.max(0, focusableCount() - 1));
        list.post(() -> focusRow(target));
    }

    private int focusableCount() {
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) n++;
        return n;
    }

    private void focusRow(int index) {
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (!v.isFocusable()) continue;
            if (n++ == index) { v.requestFocus(); return; }
        }
    }

    private int focusedIndex() {
        int n = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (!v.isFocusable()) continue;
            if (v.hasFocus()) return n;
            n++;
        }
        return 0;
    }

    // =============================== rows ===============================

    private interface IntPick { void pick(int index); }
    private interface BoolSet { void set(boolean on); }

    private void nav(String label, String value, String description, int icon, Runnable page) {
        row(label, value, description, true, v -> push(new Page(label, icon, page)));
    }

    private void choice(String label, String value, String description, String[] options, int current, IntPick pick) {
        row(label, value, description, true, v -> push(new Page(label, stack.peek().icon, () -> {
            for (int i = 0; i < options.length; i++) {
                int idx = i;
                row(options[i], i == current ? "✓" : "", description, false, x -> {
                    pick.pick(idx);
                    stack.pop();
                    rebuild();
                });
            }
            stack.peek().focus = current;
        })));
    }

    private void toggle(String label, boolean on, String description, BoolSet set) {
        row(label, on ? getString(R.string.on) : getString(R.string.off), description, false, v -> { set.set(!on); rebuild(); });
    }

    private void header(String text) {
        TextView h = text(14, 0x99EBEBF5, true);
        h.setText(text);
        h.setPadding((int) (10 * dp), (int) (22 * dp), 0, (int) (6 * dp));
        list.addView(h);
    }

    private void row(String label, String value, String description, boolean chevron, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(click != null);
        r.setBackgroundResource(R.drawable.settings_choice_bg);
        r.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.settings_lift));
        r.setPadding((int) (24 * dp), 0, (int) (22 * dp), 0);
        TextView l = text(19, Color.WHITE, true);
        l.setText(label);
        l.setDuplicateParentStateEnabled(true);
        l.setTextColor(getColorStateList(R.color.settings_text));
        l.setSingleLine(true);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        ColorStateList valueColors = new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF5A5D66, 0x99FFFFFF});
        TextView v = text(17, Color.WHITE, false);
        v.setText(value + (chevron ? "   ›" : ""));
        v.setDuplicateParentStateEnabled(true);
        v.setTextColor(valueColors);
        v.setSingleLine(true);
        r.addView(v);
        if (click != null) r.setOnClickListener(click);
        r.setOnFocusChangeListener((x, f) -> { if (f) heroDescription.setText(description); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (62 * dp));
        lp.topMargin = (int) (10 * dp);
        list.addView(r, lp);
        rowIndex++;
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private View buildFrame() {
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        left.setPadding((int) (56 * dp), 0, (int) (40 * dp), 0);
        heroIcon = new ImageView(this);
        heroIcon.setAlpha(0.92f);
        left.addView(heroIcon, new LinearLayout.LayoutParams((int) (170 * dp), (int) (170 * dp)));
        heroTitle = text(30, Color.WHITE, true);
        heroTitle.setGravity(Gravity.CENTER);
        heroTitle.setPadding(0, (int) (18 * dp), 0, 0);
        left.addView(heroTitle);
        heroDescription = text(15, 0x99FFFFFF, false);
        heroDescription.setGravity(Gravity.CENTER);
        heroDescription.setPadding(0, (int) (12 * dp), 0, 0);
        heroDescription.setMinLines(3);
        left.addView(heroDescription);
        split.addView(left, new LinearLayout.LayoutParams(0, -1, 0.4f));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        FrameLayout pad = new FrameLayout(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setPadding((int) (12 * dp), (int) (56 * dp), (int) (84 * dp), (int) (56 * dp));
        pad.setClipChildren(false);
        pad.addView(list, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        scroll.addView(pad);
        split.addView(scroll, new LinearLayout.LayoutParams(0, -1, 0.6f));
        return split;
    }
}
