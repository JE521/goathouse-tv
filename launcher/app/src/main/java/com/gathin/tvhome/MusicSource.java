package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * goathouse: where the Music screen's library and playback come from. Two backends behind one interface:
 * Music Assistant (through Home Assistant's music_assistant services with the TV's own HA login; plays to this TV's
 * Sendspin player) and Navidrome (Subsonic API with the Accounts login; plays locally on the TV).
 *
 * Items are JSON: {kind: playlist|album|artist|track, id, name, subtitle, image, artist}.
 * "image" is an http(s) URL (Music Assistant) or "nd:<coverArt>" (Navidrome, loaded through FeedClient).
 * Every method here does network work: call it off the main thread.
 */
abstract class MusicSource {
    static final String PREF = "music_source";   // launcher settings: "ma" (default) or "nd"

    /** "ma" or "nd" as chosen on the Music screen / in Settings. */
    static String chosen(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(PREF, "ma");
    }

    /**
     * The source for the Music screen. Navidrome plays as the current person's own login (Family: the Family music
     * account); returns null for Navidrome when that person hasn't signed in yet, so the screen can ask them to.
     */
    static MusicSource current(Context ctx) {
        Accounts acc = Accounts.of(ctx);
        boolean maReady = acc.hasHomeAssistant() && acc.hasMusicAssistant();
        if ("nd".equals(chosen(ctx)) || !maReady) {
            if (acc.url(Accounts.ND_URL).isEmpty()) return maReady ? new MusicAssistant(ctx) : null;
            String[] login = acc.musicLoginForProfile();
            return login == null ? null : new Navidrome(ctx, login[0], login[1]);
        }
        return new MusicAssistant(ctx);
    }

    abstract String label();
    abstract String key();
    abstract List<JSONObject> playlists() throws Exception;
    abstract List<JSONObject> albums() throws Exception;
    /** One page of every album, A–Z (the Albums tab streams these in and keeps them on the TV). */
    abstract List<JSONObject> albumsPage(int offset, int size) throws Exception;
    abstract List<JSONObject> artists() throws Exception;
    abstract List<JSONObject> recent() throws Exception;
    abstract List<JSONObject> artistAlbums(JSONObject artist) throws Exception;
    abstract List<JSONObject> search(String q) throws Exception;
    abstract void play(JSONObject item) throws Exception;
    /** Home-screen card: by name (Music Assistant) or Navidrome id. */
    abstract void playFromHome(String kind, String name, String artist, String ndId) throws Exception;
    abstract void control(String command);   // play_pause | next | previous

    /** {title, artist, album, image, playing, positionMs, durationMs} for the player bar, or null. */
    abstract JSONObject nowPlaying();

    /** Play a music card from Home (album/playlist), on whichever source the Music screen uses. */
    static void playCard(MusicSource source, String kind, String name, String artist, String ndId, String uri) throws Exception {
        // A Music Assistant card carries MA's own reference: play it exactly when MA is the source.
        if (uri != null && !uri.isEmpty() && "ma".equals(source.key())) source.play(item(kind, uri, name, artist, "", artist));
        else source.playFromHome(kind, name, artist, ndId);
    }

    /** Up Next, as cards (kind "queued", "queueIndex"); empty when the queue lives elsewhere. */
    List<JSONObject> upNext() { return new ArrayList<>(); }

    // ---------- shared plumbing ----------

    static String http(String url, String method, String auth, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        if (method != null) c.setRequestMethod(method);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
        }
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    static JSONObject item(String kind, String id, String name, String subtitle, String image, String artist) {
        try {
            return new JSONObject().put("kind", kind).put("id", id).put("name", name).put("subtitle", subtitle == null ? "" : subtitle)
                    .put("image", image == null ? "" : image).put("artist", artist == null ? "" : artist);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // =============================== Music Assistant (via Home Assistant) ===============================

    static final class MusicAssistant extends MusicSource {
        private final Context ctx;
        private final Accounts acc;
        private static volatile String entryId;

        MusicAssistant(Context ctx) {
            this.ctx = ctx.getApplicationContext();
            acc = Accounts.of(ctx);
        }

        @Override String label() { return "Music Assistant"; }
        @Override String key() { return "ma"; }

        private String auth() { return "Bearer " + acc.get(Accounts.HA_TOKEN); }

        private String entry() throws Exception {
            if (entryId != null) return entryId;
            JSONArray entries = new JSONArray(http(acc.url(Accounts.HA_URL) + "/api/config/config_entries/entry", null, auth(), null));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                if ("music_assistant".equals(e.optString("domain")) && "loaded".equals(e.optString("state"))) {
                    entryId = e.getString("entry_id");
                    return entryId;
                }
            }
            throw new java.io.IOException("Music Assistant isn't set up in Home Assistant");
        }

        private JSONObject service(String name, JSONObject body, boolean response) throws Exception {
            String url = acc.url(Accounts.HA_URL) + "/api/services/" + name + (response ? "?return_response" : "");
            String out = http(url, "POST", auth(), body.toString());
            if (!response) return new JSONObject();
            JSONObject r = new JSONObject(out);
            return r.optJSONObject("service_response") != null ? r.getJSONObject("service_response") : r;
        }

        /** Every item, a page at a time (the albums and artists tabs need the whole library for A–Z). */
        private List<JSONObject> all(String type, boolean albumArtistsOnly) throws Exception {
            List<JSONObject> out = new ArrayList<>();
            for (int offset = 0; offset < 50_000; offset += 500) {
                List<JSONObject> page = library(type, "name", 500, offset, null, albumArtistsOnly);
                out.addAll(page);
                if (page.size() < 500) break;
            }
            return out;
        }

        private List<JSONObject> library(String type, String orderBy, int limit, String search, boolean albumArtistsOnly) throws Exception {
            return library(type, orderBy, limit, 0, search, albumArtistsOnly);
        }

        private List<JSONObject> library(String type, String orderBy, int limit, int offset, String search, boolean albumArtistsOnly) throws Exception {
            JSONObject body = new JSONObject().put("config_entry_id", entry()).put("media_type", type)
                    .put("limit", limit).put("order_by", orderBy);
            if (offset > 0) body.put("offset", offset);
            if (search != null) body.put("search", search);
            if (albumArtistsOnly) body.put("album_artists_only", true);
            return convert(service("music_assistant/get_library", body, true).optJSONArray("items"));
        }

        private static List<JSONObject> convert(JSONArray items) {
            List<JSONObject> out = new ArrayList<>();
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String kind = it.optString("media_type");
                String artist = "";
                JSONArray artists = it.optJSONArray("artists");
                if (artists != null && artists.length() > 0) artist = artists.optJSONObject(0).optString("name");
                String sub = "album".equals(kind) || "track".equals(kind) ? artist
                        : "playlist".equals(kind) ? it.optString("owner", "Playlist") : "";
                JSONObject card = item(kind, it.optString("uri"), it.optString("name"), sub, it.optString("image"), artist);
                String sort = it.optString("sort_name", "");
                if (!sort.isEmpty() && !"null".equals(sort)) try { card.put("sort", sort); } catch (Exception ignored) { }
                out.add(card);
            }
            return out;
        }

        @Override List<JSONObject> playlists() throws Exception { return library("playlist", "name", 300, null, false); }
        @Override List<JSONObject> albums() throws Exception { return all("album", false); }
        @Override List<JSONObject> albumsPage(int offset, int size) throws Exception { return library("album", "name", size, offset, null, false); }

        /** Home Assistant only tells us the current and next song, plus how many are queued. */
        @Override List<JSONObject> upNext() {
            List<JSONObject> out = new ArrayList<>();
            try {
                String entity = SendspinService.thisTvEntity(ctx);
                if (entity == null) return out;
                JSONObject r = service("music_assistant/get_queue", new JSONObject().put("entity_id", entity), true);
                JSONObject q = r.optJSONObject(entity);
                if (q == null && r.keys().hasNext()) q = r.optJSONObject(r.keys().next());
                if (q == null) return out;
                String[] keys = {"current_item", "next_item"};
                for (int i = 0; i < keys.length; i++) {
                    JSONObject qi = q.optJSONObject(keys[i]);
                    JSONObject m = qi == null ? null : qi.optJSONObject("media_item");
                    if (m == null) continue;
                    String artist = "";
                    JSONArray artists = m.optJSONArray("artists");
                    if (artists != null && artists.length() > 0) artist = artists.optJSONObject(0).optString("name");
                    Object img = m.opt("image");
                    String image = img instanceof JSONObject ? ((JSONObject) img).optString("path") : m.optString("image");
                    JSONObject c = item("maqueue", m.optString("uri"), m.optString("name"), (i == 0 ? "Now playing · " : "Next · ") + artist,
                            image.startsWith("http") ? image : "", artist);
                    c.put("offset", i).put("total", q.optInt("items")).put("index", q.optInt("current_index"));
                    out.add(c);
                }
            } catch (Exception e) {
                Log.w("MusicSource", "queue: " + e);
            }
            return out;
        }
        @Override List<JSONObject> artists() throws Exception { return all("artist", true); }
        @Override List<JSONObject> recent() throws Exception { return library("album", "last_played_desc", 60, null, false); }

        @Override List<JSONObject> artistAlbums(JSONObject artist) throws Exception {
            String name = artist.optString("name");
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject a : library("album", "year", 200, name, false)) {
                if (a.optString("artist").equalsIgnoreCase(name)) out.add(a);
            }
            return out;
        }

        @Override List<JSONObject> search(String q) throws Exception {
            JSONObject r = service("music_assistant/search", new JSONObject().put("config_entry_id", entry()).put("name", q).put("limit", 12), true);
            List<JSONObject> out = new ArrayList<>();
            for (String k : new String[]{"artists", "albums", "playlists", "tracks"}) out.addAll(convert(r.optJSONArray(k)));
            return out;
        }

        @Override void play(JSONObject item) throws Exception {
            String entity = SendspinService.thisTvEntity(ctx);
            if (entity == null) throw new java.io.IOException("This TV isn't a Music Assistant player yet");
            pauseLocal();
            fireAndForget(new JSONObject().put("entity_id", entity).put("media_id", item.optString("id")));
        }

        @Override void playFromHome(String kind, String name, String artist, String ndId) throws Exception {
            String entity = SendspinService.thisTvEntity(ctx);
            if (entity == null) throw new java.io.IOException("This TV isn't a Music Assistant player yet");
            pauseLocal();
            JSONObject body = new JSONObject().put("entity_id", entity).put("media_id", name).put("media_type", kind);
            if (artist != null && !artist.isEmpty()) body.put("artist", artist);
            fireAndForget(body);
        }

        private void fireAndForget(JSONObject body) throws Exception {
            // Home Assistant holds play_media open while Music Assistant builds the queue: don't wait for it.
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(acc.url(Accounts.HA_URL) + "/api/services/music_assistant/play_media").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(3000);
                c.setRequestMethod("POST");
                c.setRequestProperty("Authorization", auth());
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                if (code != 200) {
                    SendspinService.forgetEntity();   // e.g. the entity was renamed: look it up again next time
                    throw new java.io.IOException("HTTP " + code);
                }
            } catch (java.net.SocketTimeoutException slow) {
                // sent; Music Assistant is starting it
            }
        }

        private static void pauseLocal() { LocalPlayer.get().pause(); }

        @Override void control(String command) {
            SendspinService s = SendspinService.live;
            if (s == null) return;
            if ("play_pause".equals(command)) s.command("playing".equals(s.playbackState) ? "pause" : "play");
            else s.command(command);
        }

        @Override JSONObject nowPlaying() {
            SendspinService s = SendspinService.live;
            if (s == null || s.metadata == null) return null;
            try {
                JSONObject md = s.metadata;
                return new JSONObject().put("title", md.optString("title")).put("artist", md.optString("artist"))
                        .put("album", md.optString("album")).put("image", md.optString("artwork_url"))
                        .put("playing", "playing".equals(s.playbackState))
                        .put("positionMs", s.positionMs()).put("durationMs", s.durationMs());
            } catch (Exception e) {
                return null;
            }
        }
    }

    // =============================== Navidrome (Subsonic API, local playback) ===============================

    static final class Navidrome extends MusicSource {
        private final Accounts acc;
        private final String user, pass;

        Navidrome(Context ctx, String user, String pass) {
            acc = Accounts.of(ctx);
            LocalPlayer.get().attach(ctx);
            this.user = user;
            this.pass = pass;
        }

        void ping() throws Exception { api("ping", ""); }

        @Override String label() { return "Navidrome · " + user; }
        @Override String key() { return "nd"; }

        String query(String extra) throws Exception {
            String salt = Long.toHexString(new SecureRandom().nextLong());
            byte[] d = MessageDigest.getInstance("MD5").digest((pass + salt).getBytes(StandardCharsets.UTF_8));
            StringBuilder t = new StringBuilder();
            for (byte b : d) t.append(String.format("%02x", b));
            return "u=" + URLEncoder.encode(user, "UTF-8") + "&t=" + t + "&s=" + salt
                    + "&v=1.16.1&c=goathouse-tv&f=json" + (extra.isEmpty() ? "" : "&" + extra);
        }

        private JSONObject api(String method, String extra) throws Exception {
            JSONObject r = new JSONObject(http(acc.url(Accounts.ND_URL) + "/rest/" + method + "?" + query(extra), null, null, null))
                    .getJSONObject("subsonic-response");
            if (!"ok".equals(r.optString("status"))) throw new java.io.IOException(r.optJSONObject("error") + "");
            return r;
        }

        private static List<JSONObject> albumsFrom(JSONArray a) {
            List<JSONObject> out = new ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                JSONObject card = item("album", x.optString("id"), x.optString("name", x.optString("title")), x.optString("artist"),
                        "nd:" + x.optString("coverArt", x.optString("id")), x.optString("artist"));
                if (x.has("sortName")) try { card.put("sort", x.optString("sortName")); } catch (Exception ignored) { }
                out.add(card);
            }
            return out;
        }

        private List<JSONObject> albumList(String type, int size) throws Exception {
            JSONObject l = api("getAlbumList2", "type=" + type + "&size=" + size).optJSONObject("albumList2");
            return albumsFrom(l == null ? null : l.optJSONArray("album"));
        }

        @Override List<JSONObject> playlists() throws Exception {
            JSONObject p = api("getPlaylists", "").optJSONObject("playlists");
            JSONArray a = p == null ? null : p.optJSONArray("playlist");
            List<JSONObject> out = new ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                out.add(item("playlist", x.optString("id"), x.optString("name"), x.optString("owner") + " · " + x.optInt("songCount") + " songs",
                        "nd:" + x.optString("coverArt", "pl-" + x.optString("id")), ""));
            }
            return out;
        }

        @Override List<JSONObject> albums() throws Exception { return albumsPage(0, 500); }

        @Override List<JSONObject> albumsPage(int offset, int size) throws Exception {
            JSONObject l = api("getAlbumList2", "type=alphabeticalByName&size=" + size + "&offset=" + offset).optJSONObject("albumList2");
            return albumsFrom(l == null ? null : l.optJSONArray("album"));
        }
        @Override List<JSONObject> recent() throws Exception { return albumList("recent", 60); }

        @Override List<JSONObject> artists() throws Exception {
            JSONObject a = api("getArtists", "").optJSONObject("artists");
            JSONArray idx = a == null ? null : a.optJSONArray("index");
            List<JSONObject> out = new ArrayList<>();
            if (idx != null) for (int i = 0; i < idx.length(); i++) {
                JSONArray list = idx.optJSONObject(i).optJSONArray("artist");
                if (list != null) for (int j = 0; j < list.length(); j++) {
                    JSONObject x = list.optJSONObject(j);
                    out.add(item("artist", x.optString("id"), x.optString("name"), x.optInt("albumCount") + " albums",
                            "nd:" + x.optString("coverArt", "ar-" + x.optString("id")), x.optString("name")));
                }
            }
            return out;
        }

        @Override List<JSONObject> artistAlbums(JSONObject artist) throws Exception {
            JSONObject a = api("getArtist", "id=" + URLEncoder.encode(artist.optString("id"), "UTF-8")).optJSONObject("artist");
            return albumsFrom(a == null ? null : a.optJSONArray("album"));
        }

        @Override List<JSONObject> search(String q) throws Exception {
            JSONObject r = api("search3", "query=" + URLEncoder.encode(q, "UTF-8") + "&artistCount=8&albumCount=12&songCount=12")
                    .optJSONObject("searchResult3");
            List<JSONObject> out = new ArrayList<>();
            if (r == null) return out;
            JSONArray ar = r.optJSONArray("artist");
            if (ar != null) for (int i = 0; i < ar.length(); i++) {
                JSONObject x = ar.optJSONObject(i);
                out.add(item("artist", x.optString("id"), x.optString("name"), "Artist", "nd:" + x.optString("coverArt", "ar-" + x.optString("id")), x.optString("name")));
            }
            out.addAll(albumsFrom(r.optJSONArray("album")));
            JSONArray songs = r.optJSONArray("song");
            if (songs != null) for (int i = 0; i < songs.length(); i++) {
                JSONObject x = songs.optJSONObject(i);
                out.add(item("track", x.optString("id"), x.optString("title"), x.optString("artist"), "nd:" + x.optString("coverArt", x.optString("id")), x.optString("artist")));
            }
            return out;
        }

        private List<JSONObject> tracks(String kind, String id) throws Exception {
            JSONArray songs;
            if ("playlist".equals(kind)) {
                JSONObject p = api("getPlaylist", "id=" + URLEncoder.encode(id, "UTF-8")).optJSONObject("playlist");
                songs = p == null ? null : p.optJSONArray("entry");
            } else if ("album".equals(kind)) {
                JSONObject a = api("getAlbum", "id=" + URLEncoder.encode(id, "UTF-8")).optJSONObject("album");
                songs = a == null ? null : a.optJSONArray("song");
            } else {
                JSONObject s = api("getSong", "id=" + URLEncoder.encode(id, "UTF-8")).optJSONObject("song");
                songs = s == null ? null : new JSONArray().put(s);
            }
            List<JSONObject> out = new ArrayList<>();
            if (songs != null) for (int i = 0; i < songs.length(); i++) {
                JSONObject x = songs.optJSONObject(i);
                out.add(new JSONObject().put("title", x.optString("title")).put("artist", x.optString("artist"))
                        .put("album", x.optString("album")).put("image", "nd:" + x.optString("coverArt", x.optString("id")))
                        .put("url", acc.url(Accounts.ND_URL) + "/rest/stream?" + query("id=" + URLEncoder.encode(x.optString("id"), "UTF-8")))
                        .put("scrobble", acc.url(Accounts.ND_URL) + "/rest/scrobble?" + query("id=" + URLEncoder.encode(x.optString("id"), "UTF-8"))));
            }
            return out;
        }

        @Override void play(JSONObject item) throws Exception {
            String kind = item.optString("kind");
            if ("artist".equals(kind)) return;
            List<JSONObject> q = tracks(kind, item.optString("id"));
            if (q.isEmpty()) throw new java.io.IOException("Nothing to play");
            SendspinService s = SendspinService.live;
            if (s != null && "playing".equals(s.playbackState)) s.command("pause");   // one player at a time
            LocalPlayer.get().play(q);
        }

        @Override void playFromHome(String kind, String name, String artist, String ndId) throws Exception {
            if (ndId == null || ndId.isEmpty()) throw new java.io.IOException("No Navidrome id");
            play(item(kind, ndId, name, artist, "", artist));
        }

        @Override void control(String command) {
            LocalPlayer p = LocalPlayer.get();
            if ("play_pause".equals(command)) p.toggle();
            else if ("next".equals(command)) p.next();
            else if ("previous".equals(command)) p.previous();
        }

        @Override JSONObject nowPlaying() { return LocalPlayer.get().state(); }

        @Override List<JSONObject> upNext() {
            List<JSONObject> out = new ArrayList<>();
            for (JSONObject t : LocalPlayer.get().upNext()) {
                JSONObject c = item("queued", String.valueOf(t.optInt("queueIndex")), t.optString("title"),
                        (t.optBoolean("current") ? "Now playing · " : "") + t.optString("artist"), t.optString("image"), t.optString("artist"));
                try { c.put("queueIndex", t.optInt("queueIndex")); } catch (Exception ignored) { }
                out.add(c);
            }
            return out;
        }
    }

}
