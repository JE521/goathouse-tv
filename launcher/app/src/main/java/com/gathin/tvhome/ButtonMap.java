package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * goathouse: what each remote button does, set in Settings → Remote. Nothing here is specific to one house: the
 * buttons listed are the ones the connected remote reports (InputDevice.hasKeys) or that someone has pressed, the
 * app a branded button opens is found among the installed apps, and a TV without inputs uses its Input button for
 * Music. Actions: "shelf" (rows ↔ dock), "profile", "music" (Home ↔ Music), "settings", "home", "music_play_pause",
 * "music_next", "music_previous", "none", or "app:<package>". A button with no action keeps its default (the
 * theme's app_keys, then the detected app, then Android's own behaviour).
 */
final class ButtonMap {
    static final String PREF = "button_map";
    static final String SEEN_PREF = "button_seen";   // buttons pressed on this TV's remotes, newest last

    static final String[] ACTIONS = {"shelf", "profile", "music", "settings", "home", "music_play_pause", "music_next",
            "music_previous", "none"};
    static final String[] ACTION_LABELS = {"Rows ↔ dock", "Who's watching", "Music screen", "Settings", "Home",
            "Play / pause music", "Next song", "Previous song", "Do nothing"};

    /** Special buttons a TV remote can have, with plain names. Arrows, OK, Back, Home, volume and power are never remapped. */
    private static final LinkedHashMap<Integer, String> CANDIDATES = new LinkedHashMap<>();
    static {
        c(KeyEvent.KEYCODE_GUIDE, "Guide");
        c(KeyEvent.KEYCODE_PROFILE_SWITCH, "Profile");
        c(KeyEvent.KEYCODE_SETTINGS, "Settings");
        c(KeyEvent.KEYCODE_NOTIFICATION, "Notifications");
        c(KeyEvent.KEYCODE_TV_INPUT, "Input");
        c(KeyEvent.KEYCODE_MENU, "Menu");
        c(KeyEvent.KEYCODE_INFO, "Info");
        c(KeyEvent.KEYCODE_ALL_APPS, "Apps");
        c(KeyEvent.KEYCODE_BOOKMARK, "Bookmark");
        c(KeyEvent.KEYCODE_CAPTIONS, "Captions");
        c(KeyEvent.KEYCODE_DVR, "DVR");
        c(KeyEvent.KEYCODE_TV, "TV");
        c(KeyEvent.KEYCODE_LAST_CHANNEL, "Last channel");
        c(KeyEvent.KEYCODE_CHANNEL_UP, "Channel up");
        c(KeyEvent.KEYCODE_CHANNEL_DOWN, "Channel down");
        c(KeyEvent.KEYCODE_PROG_RED, "Red");
        c(KeyEvent.KEYCODE_PROG_GREEN, "Green");
        c(KeyEvent.KEYCODE_PROG_YELLOW, "Yellow");
        c(KeyEvent.KEYCODE_PROG_BLUE, "Blue");
        c(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Play / pause");
        c(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "Fast forward");
        c(KeyEvent.KEYCODE_MEDIA_REWIND, "Rewind");
        for (int i = 0; i < 4; i++) c(KeyEvent.KEYCODE_MACRO_1 + i, "Macro " + (i + 1));
        for (int i = 0; i < 4; i++) c(KeyEvent.KEYCODE_FEATURED_APP_1 + i, "App button " + (i + 1));
        for (int i = 0; i < 16; i++) c(KeyEvent.KEYCODE_BUTTON_1 + i, "Button " + (i + 1));
    }

    private static void c(int code, String label) { CANDIDATES.put(code, label); }

    /** What a remote family prints on its buttons, keyed by Bluetooth vendor id. Add a family here to name its keys. */
    private static final Map<Integer, Map<String, String>> FAMILY_LABELS = new LinkedHashMap<>();
    /** The kind of app a branded button stands for: "youtube" or "netflix". */
    private static final Map<Integer, Map<String, String>> FAMILY_ROLES = new LinkedHashMap<>();
    static {
        Map<String, String> onn = new LinkedHashMap<>();   // Walmart onn. (vendor 0x0957)
        onn.put("KEYCODE_GUIDE", "Guide");
        onn.put("KEYCODE_PROFILE_SWITCH", "Profile");
        onn.put("KEYCODE_MACRO_1", "Music");
        onn.put("KEYCODE_NOTIFICATION", "Settings (gear)");
        onn.put("KEYCODE_TV_INPUT", "Input");
        onn.put("KEYCODE_FEATURED_APP_1", "Free TV");
        onn.put("KEYCODE_BUTTON_3", "YouTube");
        onn.put("KEYCODE_BUTTON_4", "Netflix");
        FAMILY_LABELS.put(0x0957, onn);
        Map<String, String> onnRoles = new LinkedHashMap<>();
        onnRoles.put("KEYCODE_BUTTON_3", "youtube");
        onnRoles.put("KEYCODE_BUTTON_4", "netflix");
        FAMILY_ROLES.put(0x0957, onnRoles);
    }

    static String defaultAction(Context ctx, String key) { return builtInDefault(ctx, key); }

    static boolean isCustom(Context ctx, String key) { return stored(ctx).has(key); }

    /** Built-in defaults that do not depend on apps. */
    private static String builtInDefault(Context ctx, String key) {
        switch (key) {
            case "KEYCODE_GUIDE": return "shelf";
            case "KEYCODE_PROFILE_SWITCH": return "profile";
            case "KEYCODE_SETTINGS": case "KEYCODE_NOTIFICATION": return "settings";
            case "KEYCODE_MACRO_1": return "music";
            // A streaming box has nothing to switch to, so its Input button is more use as the Music button.
            case "KEYCODE_TV_INPUT": return hasTvInputs(ctx) ? null : "music";
            default: return null;
        }
    }

    private static Boolean tvInputs;

    private static boolean hasTvInputs(Context ctx) {
        if (tvInputs == null) {
            try {
                android.media.tv.TvInputManager tim = ctx.getSystemService(android.media.tv.TvInputManager.class);
                tvInputs = tim != null && !tim.getTvInputList().isEmpty();
            } catch (Exception e) {
                tvInputs = true;   // unknown: leave Input alone
            }
        }
        return tvInputs;
    }

    /** Buttons that must keep working as themselves: never learned or remapped. */
    static boolean reserved(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_VOICE_ASSIST: case KeyEvent.KEYCODE_SEARCH: case KeyEvent.KEYCODE_UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    private static JSONObject stored(Context ctx) {
        try {
            return new JSONObject(prefs(ctx).getString(PREF, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The action chosen for a button, or its built-in default; null leaves it to the theme, a detected app or Android. */
    static String action(Context ctx, String keyName) {
        JSONObject m = stored(ctx);
        if (m.has(keyName)) {
            String a = m.optString(keyName);
            return a.isEmpty() ? null : a;
        }
        return builtInDefault(ctx, keyName);
    }

    /** null (or "") puts the button back to its default. */
    static void set(Context ctx, String keyName, String action) {
        JSONObject m = stored(ctx);
        try {
            if (action == null) m.remove(keyName); else m.put(keyName, action);
        } catch (Exception ignored) { }
        prefs(ctx).edit().putString(PREF, m.toString()).apply();
    }

    /** Called for every real button press: the Remote page lists what this TV's remotes actually have. */
    static void seen(Context ctx, int code, InputDevice device) {
        if (reserved(code) || device == null || device.isVirtual()) return;
        String key = KeyEvent.keyCodeToString(code);
        SharedPreferences p = prefs(ctx);
        String all = p.getString(SEEN_PREF, "");
        if (("\n" + all + "\n").contains("\n" + key + "\n")) return;
        p.edit().putString(SEEN_PREF, all.isEmpty() ? key : all + "\n" + key).apply();
    }

    private static List<String> seenKeys(Context ctx) {
        String all = prefs(ctx).getString(SEEN_PREF, "");
        List<String> out = new ArrayList<>();
        if (!all.isEmpty()) for (String k : all.split("\n")) out.add(k);
        return out;
    }

    static boolean wasSeen(Context ctx, String key) { return seenKeys(ctx).contains(key); }

    /** Remotes and keyboards plugged in or paired now (not the TV's own virtual and front-panel devices). */
    static List<InputDevice> remotes() {
        List<InputDevice> out = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            if ((d.getSources() & InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD) continue;
            if (android.os.Build.VERSION.SDK_INT >= 29 && !d.isExternal()) continue;
            String n = d.getName().toLowerCase(Locale.ROOT);
            if (n.contains("virtual") || n.contains("gpio") || n.contains("cec") || n.contains("keypad")) continue;
            out.add(d);
        }
        return out;
    }

    /** "Onn-Remote Keyboard" etc., for the Remote page. */
    static String remoteNames() {
        List<String> names = new ArrayList<>();
        for (InputDevice d : remotes()) if (!names.contains(d.getName())) names.add(d.getName());
        return names.isEmpty() ? "No remote found" : String.join(", ", names);
    }

    /** Buttons pressed so far, then the rest the connected remotes report, then any others with a setting. */
    static List<String> buttons(Context ctx) {
        LinkedHashSet<String> out = new LinkedHashSet<>(seenKeys(ctx));
        List<InputDevice> remotes = remotes();
        int[] codes = new int[CANDIDATES.size()];
        int i = 0;
        for (int code : CANDIDATES.keySet()) codes[i++] = code;
        for (InputDevice d : remotes) {
            boolean[] has = d.hasKeys(codes);
            for (int k = 0; k < codes.length; k++) if (has[k]) out.add(KeyEvent.keyCodeToString(codes[k]));
        }
        java.util.Iterator<String> it = stored(ctx).keys();
        while (it.hasNext()) out.add(it.next());
        return new ArrayList<>(out);
    }

    private static Integer family() {
        for (InputDevice d : remotes()) if (FAMILY_LABELS.containsKey(d.getVendorId())) return d.getVendorId();
        return null;
    }

    static String buttonLabel(String keyName) {
        Integer f = family();
        if (f != null && FAMILY_LABELS.get(f).containsKey(keyName)) return FAMILY_LABELS.get(f).get(keyName);
        int code = KeyEvent.keyCodeFromString(keyName);
        if (CANDIDATES.containsKey(code)) return CANDIDATES.get(code);
        String s = keyName.replace("KEYCODE_", "").replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isEmpty() ? keyName : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------- apps found on this TV ----------

    private static final Map<String, String> ROLE_APPS = new LinkedHashMap<>();
    private static long roleAppsAt;

    /** The installed app a branded button (YouTube, Netflix…) should open, or null. */
    static String detectedApp(Context ctx, String keyName) {
        Integer f = family();
        String role = f == null || !FAMILY_ROLES.containsKey(f) ? null : FAMILY_ROLES.get(f).get(keyName);
        if (role == null) return null;
        synchronized (ROLE_APPS) {
            if (System.currentTimeMillis() - roleAppsAt > 60_000L) { ROLE_APPS.clear(); roleAppsAt = System.currentTimeMillis(); }
            if (!ROLE_APPS.containsKey(role)) ROLE_APPS.put(role, findRoleApp(ctx, role));
            return ROLE_APPS.get(role);
        }
    }

    private static String findRoleApp(Context ctx, String role) {
        String link = "youtube".equals(role) ? "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                : "netflix".equals(role) ? "https://www.netflix.com/title/80057281" : null;
        if (link == null) return null;
        PackageManager pm = ctx.getPackageManager();
        // Apps that open that site's links, minus anything that opens every web link (browsers, stubs).
        java.util.Set<String> web = new java.util.HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")), 0)) {
            web.add(r.activityInfo.packageName);
        }
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse(link)), 0)) {
            String pkg = r.activityInfo.packageName;
            if (web.contains(pkg) || pkg.equals(ctx.getPackageName())) continue;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null) return pkg;
        }
        return null;
    }

    static String actionLabel(Context ctx, String action, String themeApp, String keyName) {
        if (action == null) {
            if (themeApp != null) return appLabel(ctx, themeApp);
            String app = detectedApp(ctx, keyName);
            return app != null ? appLabel(ctx, app) : "Android default";
        }
        if (action.startsWith("app:")) return appLabel(ctx, action.substring(4));
        for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(action)) return ACTION_LABELS[i];
        return action;
    }

    static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** Run an action; true when it was handled. */
    static boolean run(Context ctx, String action) {
        if (action == null) return false;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        switch (action) {
            case "none":
                return true;
            case "home":
                ctx.startActivity(home);
                return true;
            case "shelf": {
                MainActivity h = MainActivity.live.get();
                if (h == null || !h.toggleShelfDock()) ctx.startActivity(home);
                return true;
            }
            case "music":
                if (MusicActivity.visible) ctx.startActivity(home);
                else ctx.startActivity(new Intent(ctx, MusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            case "settings":
                ctx.startActivity(new Intent(ctx, LauncherSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            case "profile":
                ctx.startActivity(new Intent(ctx, ProfileActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            case "music_play_pause": case "music_next": case "music_previous": {
                String c = action.substring(6);
                new Thread(() -> {
                    MusicSource s = MusicSource.current(ctx);
                    if (s != null) s.control(c);
                }).start();
                return true;
            }
            default:
                if (action.startsWith("app:")) {
                    launch(ctx, action.substring(4));
                    return true;
                }
                return false;
        }
    }

    static void launch(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch != null) ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
