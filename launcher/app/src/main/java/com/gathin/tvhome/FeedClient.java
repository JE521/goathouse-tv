package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * goathouse: talks straight to the household's Jellyfin, Navidrome and Immich with the logins from Accounts.
 * Builds the media rows (Continue Watching / Next Up merged across this TV's people, Recently Added, public
 * playlists, new albums), loads artwork, and mirrors the chosen Immich albums into wallpapers/ and screensaver/.
 * All network work runs off the main thread; callbacks come back on it.
 */
final class FeedClient {
    private static final String TAG = "FeedClient";
    static final String PURPOSE_WALLPAPER = "wallpaper";
    static final String PURPOSE_SCREENSAVER = "screensaver";

    interface Callback<T> { void done(T result); }

    private final Context app;
    private final Accounts acc;
    private final File diskCache;
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(
            (int) Math.min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 12)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private Map<String, String> jfUsers;   // name -> id, cached per client
    private final Set<String> jfHidden = new HashSet<>();      // names hidden from Jellyfin's login screen
    private final Map<String, String> jfImageTag = new HashMap<>();

    private FeedClient(Context ctx) {
        app = ctx.getApplicationContext();
        acc = Accounts.of(ctx);
        diskCache = new File(app.getCacheDir(), "feed-img");
        diskCache.mkdirs();
    }

    /** Null until at least one media server is set up on the Accounts page. */
    static FeedClient fromTheme(Context ctx) {
        return Accounts.of(ctx).anyMedia() ? new FeedClient(ctx) : null;
    }

    /** Always a client, for the setup page's tests. */
    static FeedClient forSetup(Context ctx) { return new FeedClient(ctx); }

    private <T> void async(java.util.concurrent.Callable<T> work, Callback<T> cb) {
        net.execute(() -> {
            T r = null;
            try { r = work.call(); } catch (Exception e) { Log.w(TAG, "async: " + e); }
            T out = r;
            main.post(() -> cb.done(out));
        });
    }

    // =============================== rows ===============================

    void feed(Callback<JSONObject> cb) {
        async(() -> {
            JSONArray rows = new JSONArray();
            if (acc.hasJellyfin()) {
                addRow(rows, "continue", "Continue Watching", this::continueWatching);
                addRow(rows, "nextup", "Next Up", this::nextUp);
                addRow(rows, "recent", "Recently Added", this::recentlyAdded);
            }
            if (acc.hasHomeAssistant() && acc.hasMusicAssistant()) {
                addRow(rows, "recentmusic", "Recently Played", this::recentlyPlayed);
            }
            if (acc.hasNavidrome()) {
                addRow(rows, "playlists", "Playlists", this::playlists);
                addRow(rows, "music", "New Music", this::newMusic);
            }
            return new JSONObject().put("rows", rows);
        }, cb);
    }

    private interface RowSource { JSONArray load() throws Exception; }

    private static void addRow(JSONArray rows, String id, String title, RowSource src) {
        try {
            JSONArray items = src.load();
            if (items.length() > 0) rows.put(new JSONObject().put("id", id).put("title", title).put("items", items));
        } catch (Exception e) {
            Log.w(TAG, "row " + id + ": " + e);
        }
    }

    // ---------- Jellyfin ----------

    private String jfText(String path) throws Exception {
        return new String(http(acc.url(Accounts.JF_URL) + path, jfHeaders(), null, null), StandardCharsets.UTF_8).trim();
    }

    private Map<String, String> jfHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "MediaBrowser Token=\"" + acc.get(Accounts.JF_KEY) + "\"");
        return h;
    }

    /** Every Jellyfin user (name -> id), for the room picker and the merged rows. */
    synchronized Map<String, String> jellyfinUsers() throws Exception {
        if (jfUsers != null) return jfUsers;
        JSONArray us = new JSONArray(jfText("/Users"));
        Map<String, String> m = new LinkedHashMap<>();
        jfHidden.clear();
        jfImageTag.clear();
        for (int i = 0; i < us.length(); i++) {
            JSONObject u = us.getJSONObject(i);
            m.put(u.getString("Name"), u.getString("Id"));
            JSONObject pol = u.optJSONObject("Policy");
            if (pol != null && (pol.optBoolean("IsHidden") || pol.optBoolean("IsDisabled"))) jfHidden.add(u.getString("Name"));
            if (u.has("PrimaryImageTag")) jfImageTag.put(u.getString("Name"), u.optString("PrimaryImageTag"));
        }
        jfUsers = m;
        return m;
    }

    /** [{name, hidden, avatar}] for the profile switcher and the People page (avatar: "jfu:<userId>" or ""). */
    void people(Callback<JSONArray> cb) {
        async(() -> {
            JSONArray out = new JSONArray();
            if (!acc.hasJellyfin()) return out;
            synchronized (this) { jfUsers = null; }
            for (Map.Entry<String, String> e : jellyfinUsers().entrySet()) {
                out.put(new JSONObject().put("name", e.getKey()).put("hidden", jfHidden.contains(e.getKey()))
                        .put("avatar", jfImageTag.containsKey(e.getKey()) ? "jfu:" + e.getValue() : ""));
            }
            return out;
        }, cb);
    }

    /** True when name is a Jellyfin user (hidden ones too); the exact spelling comes back, or null. */
    String findUserNow(String name) {
        try {
            for (String n : jellyfinUsers().keySet()) if (n.equalsIgnoreCase(name.trim())) return n;
        } catch (Exception ignored) { }
        return null;
    }

    /** Whose rows to build: the chosen profile, or the Family people (everyone when none were picked). */
    private List<String> roomUsers() throws Exception {
        String profile = acc.profile();
        if (!profile.isEmpty()) {
            List<String> one = new ArrayList<>();
            one.add(profile);
            return one;
        }
        List<String> picked = acc.list(Accounts.JF_USERS);
        return picked.isEmpty() ? new ArrayList<>(jellyfinUsers().keySet()) : picked;
    }

    private String userId(String name) throws Exception {
        for (Map.Entry<String, String> e : jellyfinUsers().entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    private static final String JF_FIELDS = "Fields=PrimaryImageAspectRatio,ProductionYear,DateCreated,ParentBackdropItemId"
            + "&EnableImageTypes=Primary,Backdrop,Thumb";

    private JSONArray continueWatching() throws Exception {
        return merged(uid -> "/UserItems/Resume?userId=" + uid + "&Limit=12&MediaTypes=Video&" + JF_FIELDS);
    }

    private JSONArray nextUp() throws Exception {
        return merged(uid -> "/Shows/NextUp?userId=" + uid + "&Limit=12&EnableResumable=false&EnableRewatching=false&" + JF_FIELDS);
    }

    private interface PathFor { String path(String uid); }

    private JSONArray merged(PathFor pf) throws Exception {
        Map<String, Object[]> merged = new LinkedHashMap<>();   // id -> {item, who(List), last(String)}
        for (String name : roomUsers()) {
            String uid = userId(name);
            if (uid == null) continue;
            JSONArray items = new JSONObject(jfText(pf.path(uid))).optJSONArray("Items");
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                JSONObject ud = it.optJSONObject("UserData");
                String last = ud != null && ud.has("LastPlayedDate") ? ud.optString("LastPlayedDate") : it.optString("DateCreated", "");
                Object[] cur = merged.get(it.getString("Id"));
                if (cur == null) {
                    List<String> who = new ArrayList<>();
                    who.add(name);
                    merged.put(it.getString("Id"), new Object[]{it, who, last});
                } else {
                    @SuppressWarnings("unchecked") List<String> who = (List<String>) cur[1];
                    if (!who.contains(name)) who.add(name);
                    if (last.compareTo((String) cur[2]) > 0) { cur[0] = it; cur[2] = last; }
                }
            }
        }
        List<Object[]> rows = new ArrayList<>(merged.values());
        Collections.sort(rows, (a, b) -> ((String) b[2]).compareTo((String) a[2]));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(20, rows.size()); i++) {
            @SuppressWarnings("unchecked") List<String> who = (List<String>) rows.get(i)[1];
            out.put(jfItem((JSONObject) rows.get(i)[0], who));
        }
        return out;
    }

    private JSONArray recentlyAdded() throws Exception {
        List<String> users = roomUsers();
        String uid = users.isEmpty() ? null : userId(users.get(0));
        if (uid == null) return new JSONArray();
        JSONArray items = new JSONArray(jfText("/Items/Latest?userId=" + uid
                + "&Limit=20&IncludeItemTypes=Movie,Series,Episode&GroupItems=true&" + JF_FIELDS));
        JSONArray out = new JSONArray();
        for (int i = 0; i < items.length(); i++) out.put(jfItem(items.getJSONObject(i), Collections.emptyList()));
        return out;
    }

    private static JSONObject jfItem(JSONObject it, List<String> who) throws Exception {
        String kind = it.optString("Type"), title, sub, art;
        if ("Episode".equals(kind)) {
            title = it.optString("SeriesName");
            sub = "S" + it.optInt("ParentIndexNumber") + "·E" + it.optInt("IndexNumber") + " " + it.optString("Name");
            art = it.optString("SeriesId", it.getString("Id"));
        } else {
            title = it.optString("Name");
            sub = it.has("ProductionYear") ? String.valueOf(it.optInt("ProductionYear")) : "";
            art = it.getString("Id");
        }
        String backdropId = it.optString("ParentBackdropItemId", "");
        if (backdropId.isEmpty()) backdropId = art;
        JSONObject ud = it.optJSONObject("UserData");
        double pct = ud == null ? 0 : ud.optDouble("PlayedPercentage", 0);
        JSONObject o = new JSONObject()
                .put("id", it.getString("Id")).put("kind", kind).put("title", title).put("subtitle", sub)
                .put("image", "jf:" + art + ":Primary").put("backdrop", "jf:" + backdropId + ":Backdrop")
                .put("who", new JSONArray(who))
                .put("action", new JSONObject().put("type", "jellyfin").put("itemId", it.getString("Id")));
        if (pct > 0) o.put("progress", pct / 100.0);
        long runtime = it.optLong("RunTimeTicks", 0) / 10_000_000L;          // seconds
        long position = ud == null ? 0 : ud.optLong("PlaybackPositionTicks", 0) / 10_000_000L;
        if (runtime > 0) o.put("runtimeSec", runtime);
        if (position > 0) o.put("positionSec", position);
        return o;
    }

    // ---------- Navidrome (Subsonic API) ----------

    private String ndQuery(String extra) throws Exception {
        String salt = Long.toHexString(new SecureRandom().nextLong());
        String token = md5(acc.get(Accounts.ND_PASS) + salt);
        return "u=" + enc(acc.get(Accounts.ND_USER)) + "&t=" + token + "&s=" + salt + "&v=1.16.1&c=goathouse-tv&f=json"
                + (extra.isEmpty() ? "" : "&" + extra);
    }

    private JSONObject nd(String method, String extra) throws Exception {
        String body = new String(http(acc.url(Accounts.ND_URL) + "/rest/" + method + "?" + ndQuery(extra), null, null, null),
                StandardCharsets.UTF_8);
        JSONObject r = new JSONObject(body).getJSONObject("subsonic-response");
        if (!"ok".equals(r.optString("status"))) {
            JSONObject err = r.optJSONObject("error");
            throw new java.io.IOException(err == null ? "navidrome error" : err.optString("message"));
        }
        return r;
    }

    private JSONArray playlists() throws Exception {
        JSONObject pls = nd("getPlaylists", "").optJSONObject("playlists");
        JSONArray list = pls == null ? null : pls.optJSONArray("playlist");
        List<JSONObject> keep = new ArrayList<>();
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.getJSONObject(i);
            if (p.optInt("songCount") > 0) keep.add(p);
        }
        Collections.sort(keep, (a, b) -> b.optString("changed").compareTo(a.optString("changed")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(30, keep.size()); i++) {
            JSONObject p = keep.get(i);
            out.put(new JSONObject().put("id", p.getString("id")).put("kind", "Playlist").put("title", p.optString("name"))
                    .put("subtitle", p.optString("owner") + " · " + p.optInt("songCount") + " songs")
                    .put("image", "nd:" + p.optString("coverArt", "pl-" + p.getString("id"))).put("who", new JSONArray())
                    .put("action", new JSONObject().put("type", "playlist").put("playlist", p.optString("name")).put("id", p.getString("id"))));
        }
        return out;
    }

    private JSONArray newMusic() throws Exception {
        JSONObject l = nd("getAlbumList2", "type=newest&size=20").optJSONObject("albumList2");
        JSONArray albums = l == null ? null : l.optJSONArray("album");
        JSONArray out = new JSONArray();
        if (albums != null) for (int i = 0; i < albums.length(); i++) {
            JSONObject a = albums.getJSONObject(i);
            out.put(new JSONObject().put("id", a.getString("id")).put("kind", "MusicAlbum").put("title", a.optString("name"))
                    .put("subtitle", a.optString("artist")).put("image", "nd:" + a.optString("coverArt", a.getString("id")))
                    .put("who", new JSONArray())
                    .put("action", new JSONObject().put("type", "music").put("album", a.optString("name"))
                            .put("artist", a.optString("artist")).put("id", a.getString("id"))));
        }
        return out;
    }

    // ---------- Music Assistant (through Home Assistant) ----------

    /** Albums anyone last played through Music Assistant, newest first. */
    private JSONArray recentlyPlayed() throws Exception {
        JSONArray out = new JSONArray();
        for (JSONObject a : new MusicSource.MusicAssistant(app).recent()) {
            if (out.length() >= 20) break;
            out.put(new JSONObject().put("id", a.optString("id")).put("kind", "MusicAlbum").put("title", a.optString("name"))
                    .put("subtitle", a.optString("artist")).put("image", a.optString("image")).put("who", new JSONArray())
                    .put("action", new JSONObject().put("type", "music").put("album", a.optString("name"))
                            .put("artist", a.optString("artist")).put("uri", a.optString("id"))));
        }
        return out;
    }

    // ---------- Immich ----------

    private Map<String, String> imHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("x-api-key", acc.get(Accounts.IM_KEY));
        return h;
    }

    private JSONArray imArray(String path) throws Exception {
        return new JSONArray(new String(http(acc.url(Accounts.IM_URL) + path, imHeaders(), null, null), StandardCharsets.UTF_8));
    }

    /** The Immich account's albums (own and shared): [{id, name, count, cover}]. */
    void albums(Callback<JSONArray> cb) { async(this::albumsNow, cb); }

    private JSONArray albumsNow() throws Exception {
        if (!acc.hasImmich()) return new JSONArray();
        Map<String, JSONObject> byId = new LinkedHashMap<>();
        for (String path : new String[]{"/api/albums", "/api/albums?shared=true"}) {
            JSONArray a = imArray(path);
            for (int i = 0; i < a.length(); i++) {
                JSONObject al = a.getJSONObject(i);
                if (byId.containsKey(al.getString("id"))) continue;
                String thumb = al.optString("albumThumbnailAssetId", "");
                byId.put(al.getString("id"), new JSONObject().put("id", al.getString("id")).put("name", al.optString("albumName"))
                        .put("count", al.optInt("assetCount"))
                        .put("cover", thumb.isEmpty() || "null".equals(thumb) ? "" : "im:" + thumb));
            }
        }
        List<JSONObject> list = new ArrayList<>(byId.values());
        Collections.sort(list, (x, y) -> x.optString("name").compareToIgnoreCase(y.optString("name")));
        return new JSONArray(list);
    }

    private List<String> albumAssets(String albumId) throws Exception {
        List<String> ids = new ArrayList<>();
        int page = 1;
        while (page > 0 && ids.size() < 2000) {   // Immich 3: album contents come from search, not the album object
            byte[] body = new JSONObject().put("albumIds", new JSONArray().put(albumId)).put("type", "IMAGE")
                    .put("size", 250).put("page", page).toString().getBytes(StandardCharsets.UTF_8);
            Map<String, String> h = imHeaders();
            h.put("Content-Type", "application/json");
            JSONObject r = new JSONObject(new String(http(acc.url(Accounts.IM_URL) + "/api/search/metadata", h, "POST", body),
                    StandardCharsets.UTF_8)).getJSONObject("assets");
            JSONArray items = r.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) ids.add(items.getJSONObject(i).getString("id"));
            String next = r.optString("nextPage", "");
            page = next.isEmpty() || "null".equals(next) ? 0 : Integer.parseInt(next);
        }
        return ids;
    }

    /** This TV's album choice, kept on the TV: {wallpaper: [ids], screensaver: [ids]}. */
    void photoChoice(Callback<JSONObject> cb) {
        async(() -> new JSONObject()
                .put(PURPOSE_WALLPAPER, new JSONArray(acc.list(Accounts.PHOTO_WALLPAPER)))
                .put(PURPOSE_SCREENSAVER, new JSONArray(acc.list(Accounts.PHOTO_SCREENSAVER))), cb);
    }

    void savePhotoChoice(JSONObject choice, Callback<JSONObject> cb) {
        async(() -> {
            acc.putList(Accounts.PHOTO_WALLPAPER, strings(choice.optJSONArray(PURPOSE_WALLPAPER)));
            acc.putList(Accounts.PHOTO_SCREENSAVER, strings(choice.optJSONArray(PURPOSE_SCREENSAVER)));
            return choice;
        }, cb);
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    /** Mirrors the chosen albums into the theme folder; true when files changed. */
    void syncPhotos(Context ctx, Callback<Boolean> cb) {
        async(() -> {
            File dir = Config.dir(ctx);
            if (dir == null || !acc.hasImmich()) return false;
            boolean changed = syncPurpose(acc.list(Accounts.PHOTO_WALLPAPER), new File(dir, "wallpapers"));
            changed |= syncPurpose(acc.list(Accounts.PHOTO_SCREENSAVER), new File(dir, "screensaver"));
            return changed;
        }, cb);
    }

    private boolean syncPurpose(List<String> albumIds, File target) throws Exception {
        List<String> assets = new ArrayList<>();
        for (String a : albumIds) {
            try { assets.addAll(albumAssets(a)); } catch (Exception e) { Log.w(TAG, "album " + a + ": " + e); }
        }
        Set<String> uniq = new LinkedHashSet<>(assets);
        target.mkdirs();
        boolean changed = false;
        if (uniq.isEmpty()) {
            // Nothing chosen: drop album pictures (im-*) and fall back to whatever the theme shipped.
            File[] old = target.listFiles();
            if (old != null) for (File f : old) if (f.getName().startsWith("im-")) changed |= f.delete();
            return changed;
        }
        Set<String> want = new HashSet<>();
        int n = 0;
        for (String id : uniq) {
            if (n++ >= 120) break;
            String name = "im-" + id.replaceAll("[^A-Za-z0-9-]", "") + ".jpg";
            want.add(name);
            File f = new File(target, name);
            if (f.isFile() && f.length() > 0) continue;
            writeAtomic(f, http(acc.url(Accounts.IM_URL) + "/api/assets/" + id + "/thumbnail?size=preview", imHeaders(), null, null));
            changed = true;
        }
        File[] existing = target.listFiles();
        if (existing != null) for (File f : existing) if (!want.contains(f.getName())) changed |= f.delete();
        return changed;
    }

    // =============================== artwork ===============================

    /** key: "jf:<itemId>:<Primary|Backdrop>", "nd:<coverArtId>" or "im:<assetId>". */
    void image(ImageView view, String key, int reqW, int reqH) {
        view.setTag(R.id.feed_image_tag, key);
        if (key == null || key.isEmpty()) { view.setImageDrawable(null); return; }
        String memKey = key + "@" + reqW;
        Bitmap hit = MEM.get(memKey);
        if (hit != null) { view.setImageBitmap(hit); return; }
        view.setImageDrawable(null);
        net.execute(() -> {
            Bitmap bmp = null;
            try {
                int w = Math.max(reqW, 360);
                File f = new File(diskCache, sha1(key + "@" + w));
                byte[] data;
                if (f.isFile() && System.currentTimeMillis() - f.lastModified() < 7L * 86400_000) {
                    data = readAll(new java.io.FileInputStream(f));
                } else {
                    data = fetchImage(key, w);
                    writeAtomic(f, data);
                }
                bmp = decode(data, reqW, reqH);
            } catch (Exception e) {
                Log.w(TAG, "image " + key + ": " + e);
            }
            if (bmp == null) return;
            MEM.put(memKey, bmp);
            Bitmap b = bmp;
            main.post(() -> { if (key.equals(view.getTag(R.id.feed_image_tag))) view.setImageBitmap(b); });
        });
    }

    private byte[] fetchImage(String key, int w) throws Exception {
        if (key.startsWith("http://") || key.startsWith("https://")) return http(key, null, null, null);   // Music Assistant art
        String[] k = key.split(":", 3);
        switch (k[0]) {
            case "jf": {
                String type = k.length > 2 ? k[2] : "Primary";
                int width = "Backdrop".equals(type) ? Math.max(w, 640) : w;
                return http(acc.url(Accounts.JF_URL) + "/Items/" + k[1] + "/Images/" + type + "?maxWidth=" + width + "&quality=85",
                        jfHeaders(), null, null);
            }
            case "nd":
                return http(acc.url(Accounts.ND_URL) + "/rest/getCoverArt?" + ndQuery("id=" + enc(k[1]) + "&size=" + w), null, null, null);
            case "jfu":   // a Jellyfin user's avatar
                return http(acc.url(Accounts.JF_URL) + "/Users/" + k[1] + "/Images/Primary?maxWidth=" + w + "&quality=90",
                        jfHeaders(), null, null);
            case "im":
                return http(acc.url(Accounts.IM_URL) + "/api/assets/" + k[1] + "/thumbnail?size=thumbnail", imHeaders(), null, null);
            default:
                throw new java.io.IOException("unknown image " + key);
        }
    }

    private static Bitmap decode(byte[] data, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    // =============================== setup tests ===============================

    /** One line per service for the setup page: "ok: …" or "error: …"; empty when the service is not set up. */
    String testNow(String service) {
        try {
            switch (service) {
                case "jellyfin":
                    if (!acc.hasJellyfin()) return "";
                    synchronized (this) { jfUsers = null; }
                    return "ok: " + jellyfinUsers().size() + " users";
                case "navidrome":
                    if (!acc.hasNavidrome()) return "";
                    nd("ping", "");
                    return "ok: signed in as " + acc.get(Accounts.ND_USER);
                case "immich":
                    if (!acc.hasImmich()) return "";
                    return "ok: " + albumsNow().length() + " albums";
                case "musicassistant": {
                    if (!acc.hasMusicAssistant()) return "";
                    JSONObject info = new JSONObject(new String(http(acc.url(Accounts.MA_URL) + "/info", null, null, null),
                            StandardCharsets.UTF_8));
                    SendspinService s = SendspinService.live;
                    return "ok: Music Assistant " + info.optString("server_version", "")
                            + (s != null && s.connected ? " · player connected" : "");
                }
                case "homeassistant": {
                    if (!acc.hasHomeAssistant()) return "";
                    Map<String, String> h = new HashMap<>();
                    h.put("Authorization", "Bearer " + acc.get(Accounts.HA_TOKEN));
                    http(acc.url(Accounts.HA_URL) + "/api/", h, null, null);
                    return "ok: connected";
                }
                default:
                    return "";
            }
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (m.contains("401") || m.contains("403")) m = "login rejected";
            return "error: " + m;
        }
    }

    // =============================== plumbing ===============================

    private static byte[] http(String url, Map<String, String> headers, String method, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        if (method != null) c.setRequestMethod(method);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
        }
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void writeAtomic(File f, byte[] data) throws java.io.IOException {
        File tmp = new File(f.getPath() + "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".part");
        try (OutputStream os = new FileOutputStream(tmp)) { os.write(data); }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            if (!f.isFile()) throw new java.io.IOException("rename " + f);
        }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static String md5(String s) throws Exception { return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8))); }

    private static String sha1(String s) throws Exception { return hex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))); }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
