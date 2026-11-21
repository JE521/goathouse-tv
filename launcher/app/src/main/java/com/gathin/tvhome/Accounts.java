package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * goathouse: the servers this TV talks to, entered on the Accounts page (phone form). Private app storage only:
 * never on shared storage, never in a theme. Every field may be empty; each service works on its own.
 */
final class Accounts {
    private static final String PREFS = "accounts";

    static final String JF_URL = "jellyfin_url", JF_KEY = "jellyfin_key", JF_USERS = "jellyfin_users";
    static final String ND_URL = "navidrome_url", ND_USER = "navidrome_user", ND_PASS = "navidrome_pass";
    static final String IM_URL = "immich_url", IM_KEY = "immich_key";
    static final String HA_URL = "ha_url", HA_TOKEN = "ha_token", HA_PIPELINE = "ha_pipeline";
    static final String MA_URL = "ma_url", PLAYER_NAME = "player_name";
    static final String PHOTO_WALLPAPER = "photos_wallpaper", PHOTO_SCREENSAVER = "photos_screensaver";
    /** Whose rows Home shows: empty = Family (the JF_USERS people merged), otherwise one Jellyfin user name. */
    static final String PROFILE = "profile";
    /** Bumped on every save; Home and the shelf reload when it moves. */
    static final String REVISION = "revision";

    static final String[] SECRET_FIELDS = {JF_KEY, ND_PASS, IM_KEY, HA_TOKEN};
    static final String[] TEXT_FIELDS = {JF_URL, ND_URL, ND_USER, IM_URL, HA_URL, HA_PIPELINE, MA_URL, PLAYER_NAME};

    private final SharedPreferences p;

    private Accounts(Context ctx) { p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static Accounts of(Context ctx) { return new Accounts(ctx); }

    String get(String key) { return p.getString(key, ""); }

    String url(String key) { return get(key).trim().replaceAll("/+$", ""); }

    List<String> list(String key) {
        String v = get(key);
        if (v.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(v.split("\n")));
    }

    void put(SharedPreferences.Editor e, String key, String value) { e.putString(key, value == null ? "" : value.trim()); }

    SharedPreferences.Editor edit() { return p.edit(); }

    void putList(String key, List<String> values) {
        p.edit().putString(key, String.join("\n", values)).putLong(REVISION, revision() + 1).apply();
    }

    void bump(SharedPreferences.Editor e) { e.putLong(REVISION, revision() + 1); }

    long revision() { return p.getLong(REVISION, 0); }

    boolean hasJellyfin() { return !url(JF_URL).isEmpty() && !get(JF_KEY).isEmpty(); }

    boolean hasNavidrome() { return !url(ND_URL).isEmpty() && !get(ND_USER).isEmpty() && !get(ND_PASS).isEmpty(); }

    boolean hasImmich() { return !url(IM_URL).isEmpty() && !get(IM_KEY).isEmpty(); }

    boolean hasHomeAssistant() { return !url(HA_URL).isEmpty() && !get(HA_TOKEN).isEmpty(); }

    String profile() { return get(PROFILE); }

    void setProfile(String name) {
        p.edit().putString(PROFILE, name == null ? "" : name).putLong(REVISION, revision() + 1).apply();
    }

    // ---------- Navidrome music logins (added whenever someone signs in; nothing preset) ----------

    static final String ND_ACCOUNTS = "nd_accounts", ND_LINKS = "nd_links", ND_FAMILY = "nd_family";

    /** [{user, pass}] for every Navidrome login added on this TV. The server-level ND_USER login is listed first. */
    List<String[]> musicLogins() {
        List<String[]> out = new ArrayList<>();
        if (!get(ND_USER).isEmpty() && !get(ND_PASS).isEmpty()) out.add(new String[]{get(ND_USER), get(ND_PASS)});
        try {
            org.json.JSONArray a = new org.json.JSONArray(p.getString(ND_ACCOUNTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                String u = o.optString("user");
                boolean dup = false;
                for (String[] x : out) dup |= x[0].equalsIgnoreCase(u);
                if (!dup) out.add(new String[]{u, o.optString("pass")});
            }
        } catch (Exception ignored) { }
        return out;
    }

    String[] musicLogin(String user) {
        if (user == null || user.isEmpty()) return null;
        for (String[] l : musicLogins()) if (l[0].equalsIgnoreCase(user)) return l;
        return null;
    }

    void addMusicLogin(String user, String pass) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(p.getString(ND_ACCOUNTS, "[]"));
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).optString("user").equalsIgnoreCase(user)) out.put(a.get(i));
            out.put(new org.json.JSONObject().put("user", user).put("pass", pass));
            p.edit().putString(ND_ACCOUNTS, out.toString()).putLong(REVISION, revision() + 1).apply();
        } catch (Exception ignored) { }
    }

    void removeMusicLogin(String user) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(p.getString(ND_ACCOUNTS, "[]"));
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).optString("user").equalsIgnoreCase(user)) out.put(a.get(i));
            org.json.JSONObject links = links();
            java.util.Iterator<String> it = links.keys();
            java.util.List<String> drop = new ArrayList<>();
            while (it.hasNext()) { String k = it.next(); if (links.optString(k).equalsIgnoreCase(user)) drop.add(k); }
            for (String k : drop) links.remove(k);
            p.edit().putString(ND_ACCOUNTS, out.toString()).putString(ND_LINKS, links.toString()).putLong(REVISION, revision() + 1).apply();
        } catch (Exception ignored) { }
    }

    private org.json.JSONObject links() {
        try { return new org.json.JSONObject(p.getString(ND_LINKS, "{}")); } catch (Exception e) { return new org.json.JSONObject(); }
    }

    /** The explicit link for a person, or "" (then a login with the same name is used). */
    String linkedMusicUser(String person) { return links().optString(person.toLowerCase(java.util.Locale.ROOT), ""); }

    void linkMusicUser(String person, String user) {
        org.json.JSONObject l = links();
        try {
            if (user == null || user.isEmpty()) l.remove(person.toLowerCase(java.util.Locale.ROOT));
            else l.put(person.toLowerCase(java.util.Locale.ROOT), user);
        } catch (Exception ignored) { }
        p.edit().putString(ND_LINKS, l.toString()).putLong(REVISION, revision() + 1).apply();
    }

    String familyMusicUser() {
        String f = get(ND_FAMILY);
        return f.isEmpty() ? get(ND_USER) : f;
    }

    void setFamilyMusicUser(String user) { p.edit().putString(ND_FAMILY, user).putLong(REVISION, revision() + 1).apply(); }

    /** Whose Navidrome library the Music screen uses now: the Home profile's login, Family's on Family, or null. */
    String[] musicLoginForProfile() {
        String person = profile();
        if (person.isEmpty()) return musicLogin(familyMusicUser());
        String linked = linkedMusicUser(person);
        return musicLogin(linked.isEmpty() ? person : linked);
    }

    boolean hasMusicAssistant() { return !url(MA_URL).isEmpty(); }

    boolean anyMedia() { return hasJellyfin() || hasNavidrome() || hasImmich(); }
}
