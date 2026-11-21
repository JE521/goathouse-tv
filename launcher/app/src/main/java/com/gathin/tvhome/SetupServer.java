package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * goathouse: the phone side of the Accounts page. A tiny HTTP server that only runs while the Accounts page is on
 * screen, answers only requests carrying the one-time PIN shown in the QR code, and writes what the form sends
 * into Accounts. Secrets are never sent back to the phone: their fields come back empty with a "saved" hint.
 */
final class SetupServer {
    interface Listener { void onSaved(); }

    static final int PORT = 8780;
    private final Context ctx;
    private final Listener listener;
    final String pin;
    private ServerSocket server;
    private volatile boolean running;

    SetupServer(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        pin = String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

    void start() {
        running = true;
        new Thread(() -> {
            try (ServerSocket s = new ServerSocket(PORT)) {
                server = s;
                while (running) {
                    Socket c = s.accept();
                    new Thread(() -> handle(c), "setup-client").start();
                }
            } catch (Exception e) {
                if (running) Log.w("SetupServer", "stopped: " + e);
            }
        }, "setup-server").start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    private void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(15000);
            InputStream in = sock.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0], target = req[1];
            int len = 0;
            for (String l : lines) if (l.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
            byte[] body = new byte[Math.min(len, 65536)];
            int off = 0;
            while (off < body.length) {
                int n = in.read(body, off, body.length - off);
                if (n < 0) break;
                off += n;
            }
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            Map<String, String> q = parse(target.contains("?") ? target.substring(target.indexOf('?') + 1) : "");
            Map<String, List<String>> form = parseMulti(new String(body, 0, off, StandardCharsets.UTF_8));
            String givenPin = q.containsKey("pin") ? q.get("pin") : first(form, "pin");
            OutputStream out = sock.getOutputStream();
            if (!pin.equals(givenPin)) {
                send(out, 403, page("Wrong or expired link",
                        "<p>Open <b>Accounts</b> on the TV again and scan the new code.</p>"));
                return;
            }
            if ("POST".equals(method) && "/save".equals(path)) {
                save(form);
                send(out, 200, form(true));
                listener.onSaved();
            } else {
                send(out, 200, form(false));
            }
        } catch (Exception e) {
            Log.w("SetupServer", "client: " + e);
        }
    }

    // ---------- saving ----------

    private void save(Map<String, List<String>> f) {
        Accounts acc = Accounts.of(ctx);
        SharedPreferences.Editor e = acc.edit();
        for (String k : Accounts.TEXT_FIELDS) if (f.containsKey(k)) acc.put(e, k, cleanUrl(k, first(f, k)));
        for (String k : Accounts.SECRET_FIELDS) {
            String v = first(f, k);
            if (f.containsKey("clear_" + k)) acc.put(e, k, "");
            else if (v != null && !v.trim().isEmpty()) acc.put(e, k, v);   // blank = keep what is saved
        }
        if (f.containsKey("users_present")) {
            List<String> users = f.containsKey("users") ? f.get("users") : new ArrayList<>();
            e.putString(Accounts.JF_USERS, String.join("\n", users));
        }
        acc.bump(e);
        e.commit();
    }

    private static String cleanUrl(String key, String v) {
        if (v == null) return "";
        v = v.trim();
        if (key.endsWith("_url") && !v.isEmpty() && !v.matches("^[a-zA-Z]+://.*")) v = "http://" + v;
        return v;
    }

    // ---------- page ----------

    private String form(boolean justSaved) {
        Accounts acc = Accounts.of(ctx);
        FeedClient fc = FeedClient.forSetup(ctx);
        StringBuilder b = new StringBuilder();
        if (justSaved) b.append("<div class=ok>Saved. The TV is updating.</div>");
        b.append("<form method=post action='/save'><input type=hidden name=pin value='").append(pin).append("'>");

        section(b, "Jellyfin", "Movies and shows", fc.testNow("jellyfin"));
        text(b, "Server address", Accounts.JF_URL, acc.get(Accounts.JF_URL), "http://192.168.1.10:8096");
        secret(b, "API key", Accounts.JF_KEY, acc, "Dashboard → API Keys → +");
        if (acc.hasJellyfin()) {
            try {
                List<String> picked = acc.list(Accounts.JF_USERS);
                b.append("<label>People who watch on this TV</label><div class=users><input type=hidden name=users_present value=1>");
                for (String name : fc.jellyfinUsers().keySet()) {
                    boolean on = picked.contains(name);
                    b.append("<label class=chk><input type=checkbox name=users value='").append(esc(name)).append("'")
                            .append(on ? " checked" : "").append("> ").append(esc(name)).append("</label>");
                }
                b.append("</div><p class=hint>None ticked = everyone.</p>");
            } catch (Exception ignored) { }
        }
        b.append("</section>");

        section(b, "Navidrome", "Music: playlists and new albums", fc.testNow("navidrome"));
        text(b, "Server address", Accounts.ND_URL, acc.get(Accounts.ND_URL), "http://192.168.1.10:4533");
        text(b, "Username", Accounts.ND_USER, acc.get(Accounts.ND_USER), "a shared account, e.g. tv");
        secret(b, "Password", Accounts.ND_PASS, acc, "");
        b.append("</section>");

        section(b, "Immich", "Photo albums for the background and screensaver", fc.testNow("immich"));
        text(b, "Server address", Accounts.IM_URL, acc.get(Accounts.IM_URL), "http://192.168.1.10:2283");
        secret(b, "API key", Accounts.IM_KEY, acc, "Account Settings → API Keys (read-only is enough)");
        b.append("</section>");

        section(b, "Home Assistant", "Voice: the remote's mic button", fc.testNow("homeassistant"));
        text(b, "Server address", Accounts.HA_URL, acc.get(Accounts.HA_URL), "http://192.168.1.10:8123");
        secret(b, "Long-lived token", Accounts.HA_TOKEN, acc, "Profile → Security → Long-lived access tokens");
        text(b, "Pipeline ID (optional)", Accounts.HA_PIPELINE, acc.get(Accounts.HA_PIPELINE), "blank = your preferred pipeline");
        b.append("</section>");

        section(b, "Music Assistant", "This TV as a speaker: music plays here with a Now Playing screen", fc.testNow("musicassistant"));
        text(b, "Server address", Accounts.MA_URL, acc.get(Accounts.MA_URL), "http://192.168.1.10:8095");
        text(b, "Player name (optional)", Accounts.PLAYER_NAME, acc.get(Accounts.PLAYER_NAME), "blank = this TV's name");
        b.append("</section>");

        b.append("<button type=submit>Save</button></form>");
        return page("TV setup", b.toString());
    }

    private static void section(StringBuilder b, String title, String sub, String status) {
        b.append("<section><h2>").append(title).append("</h2><p class=sub>").append(sub).append("</p>");
        if (status != null && !status.isEmpty()) {
            boolean ok = status.startsWith("ok");
            b.append("<p class='st ").append(ok ? "good" : "bad").append("'>").append(ok ? "✓ " : "✕ ")
                    .append(esc(status.replaceFirst("^(ok|error): ", ""))).append("</p>");
        }
    }

    private static void text(StringBuilder b, String label, String name, String value, String placeholder) {
        b.append("<label>").append(label).append("</label><input name='").append(name).append("' value='").append(esc(value))
                .append("' placeholder='").append(esc(placeholder)).append("' autocapitalize=off autocorrect=off spellcheck=false>");
    }

    private static void secret(StringBuilder b, String label, String name, Accounts acc, String hint) {
        boolean saved = !acc.get(name).isEmpty();
        b.append("<label>").append(label).append("</label><input type=password name='").append(name)
                .append("' placeholder='").append(saved ? "saved, leave blank to keep" : esc(hint)).append("' autocomplete=off>");
        if (saved) b.append("<label class=chk small><input type=checkbox name='clear_").append(name).append("'> remove saved ")
                .append(label.toLowerCase()).append("</label>");
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + title + "</title><style>"
                + ":root{color-scheme:dark}body{margin:0;background:#0b0d12;color:#eef;font:16px/1.4 system-ui,sans-serif}"
                + "main{max-width:560px;margin:0 auto;padding:20px 16px 60px}h1{font-weight:600;margin:8px 0 4px}"
                + "section{background:#151922;border:1px solid #232a36;border-radius:16px;padding:16px;margin:16px 0}"
                + "h2{margin:0;font-size:19px}.sub{margin:2px 0 10px;color:#9aa4b8;font-size:14px}"
                + "label{display:block;font-size:13px;color:#b8c2d6;margin:12px 0 4px}"
                + "input:not([type=checkbox]){width:100%;box-sizing:border-box;background:#0b0d12;color:#fff;border:1px solid #2c3444;"
                + "border-radius:10px;padding:12px;font-size:16px}input:focus{outline:2px solid #7cc4ff}"
                + ".chk{display:flex;gap:8px;align-items:center;color:#dfe6f3;font-size:15px;margin:8px 0}"
                + ".users{display:grid;grid-template-columns:1fr 1fr;gap:0 12px}.hint{color:#7d879b;font-size:13px;margin:4px 0}"
                + "button{width:100%;padding:15px;border:0;border-radius:14px;background:#e9eef7;color:#0b0d12;font-size:17px;font-weight:600}"
                + ".st{margin:0 0 6px;font-size:14px}.good{color:#7ee2a8}.bad{color:#ff8f8f}"
                + ".ok{background:#163a26;color:#9ff0bf;border-radius:12px;padding:12px;margin:12px 0}"
                + "</style></head><body><main><h1>" + title + "</h1>" + body + "</main></body></html>";
    }

    // ---------- HTTP plumbing ----------

    private static void send(OutputStream out, int code, String html) throws Exception {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Forbidden") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) != -1) {
            h.write(c);
            if (h.size() > 16384) return null;
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return h.toString("ISO-8859-1");
        }
        return null;
    }

    private static Map<String, String> parse(String s) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, List<String>> e : parseMulti(s).entrySet()) m.put(e.getKey(), e.getValue().get(0));
        return m;
    }

    private static Map<String, List<String>> parseMulti(String s) throws Exception {
        Map<String, List<String>> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), "UTF-8");
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), "UTF-8");
            m.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return m;
    }

    private static String first(Map<String, List<String>> m, String k) {
        List<String> v = m.get(k);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
