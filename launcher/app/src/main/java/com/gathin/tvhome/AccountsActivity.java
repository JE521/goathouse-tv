package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * goathouse: "Accounts" — scan the QR code with a phone and fill in Jellyfin / Navidrome / Immich / Home Assistant
 * there. The phone form is served by SetupServer only while this screen is open.
 */
public final class AccountsActivity extends Activity implements SetupServer.Listener {
    private SetupServer server;
    private float dp;
    private LinearLayout statusList;
    private TextView urlText;
    private ImageView qr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        setContentView(SettingsBackdrop.wrap(this, build()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        server = new SetupServer(this, this);
        server.start();
        String ip = lanAddress();
        String url = ip == null ? null : "http://" + ip + ":" + SetupServer.PORT + "/?pin=" + server.pin;
        if (url == null) {
            urlText.setText("Connect the TV to the network first.");
        } else {
            urlText.setText(ip + ":" + SetupServer.PORT + "   ·   PIN " + server.pin);
            qr.setImageBitmap(qrBitmap(url, (int) (300 * dp)));
        }
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (server != null) server.stop();   // the phone form only exists while this screen is up
        server = null;
    }

    @Override
    public void onSaved() {
        runOnUiThread(() -> {
            SendspinService.startIfConfigured(this);
            refreshStatus();
            // Home rebuilds (rows, photos) when it comes back.
            SharedPreferences c = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
            c.edit().putLong(Config.SETTINGS_REVISION_KEY, c.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
        });
    }

    private void refreshStatus() {
        statusList.removeAllViews();
        String[][] services = {{"jellyfin", "Jellyfin", "Movies and shows"}, {"navidrome", "Navidrome", "Music"},
                {"immich", "Immich", "Photos"}, {"homeassistant", "Home Assistant", "Voice"},
                {"musicassistant", "Music Assistant", "This TV as a speaker"}};
        FeedClient fc = FeedClient.forSetup(this);
        for (String[] s : services) {
            TextView row = text(18, Color.WHITE, false);
            row.setText(s[1] + "  ·  checking…");
            row.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
            statusList.addView(row);
            new Thread(() -> {
                String st = fc.testNow(s[0]);
                runOnUiThread(() -> {
                    if (st.isEmpty()) { row.setText(s[1] + "  ·  not set up"); row.setTextColor(0x88FFFFFF); }
                    else if (st.startsWith("ok")) { row.setText("✓  " + s[1] + "  ·  " + st.substring(4)); row.setTextColor(0xFF9FF0BF); }
                    else { row.setText("✕  " + s[1] + "  ·  " + st.substring(7)); row.setTextColor(0xFFFF9A9A); }
                });
            }, "status-" + s[0]).start();
        }
    }

    // ---------- view ----------

    private FrameLayout build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D12);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding((int) (72 * dp), (int) (48 * dp), (int) (72 * dp), (int) (48 * dp));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(32, Color.WHITE, true);
        title.setText("Accounts");
        TextView how = text(17, 0xBBFFFFFF, false);
        how.setText("Scan the code with your phone to connect this TV to your servers.\n"
                + "Everything is typed on the phone and saved on this TV only.");
        how.setPadding(0, (int) (8 * dp), 0, (int) (24 * dp));
        statusList = new LinearLayout(this);
        statusList.setOrientation(LinearLayout.VERTICAL);
        left.addView(title);
        left.addView(how);
        left.addView(statusList);
        page.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(20 * dp);
        card.setBackground(bg);
        int pad = (int) (16 * dp);
        card.setPadding(pad, pad, pad, pad);
        qr = new ImageView(this);
        card.addView(qr, new FrameLayout.LayoutParams((int) (300 * dp), (int) (300 * dp)));
        right.addView(card);
        urlText = text(16, 0xCCFFFFFF, false);
        urlText.setGravity(Gravity.CENTER);
        urlText.setPadding(0, (int) (14 * dp), 0, 0);
        right.addView(urlText);
        page.addView(right, new LinearLayout.LayoutParams(-2, -2));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        // Nothing to focus but Back; keep a focus target so the remote has somewhere to be.
        root.setFocusable(true);
        return root;
    }

    private TextView text(float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private static Bitmap qrBitmap(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            return null;
        }
    }

    /** The TV's LAN IPv4 (Wi-Fi or Ethernet). */
    private static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
