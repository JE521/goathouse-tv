package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.List;

/** goathouse: the picture Home is showing, darkened, behind the settings screens (tvOS style). */
final class SettingsBackdrop {
    private SettingsBackdrop() { }

    /** The wallpaper file Home shows right now, or null. */
    static File current(Context ctx) {
        try {
            Config cfg = Config.load(ctx);
            List<File> all = cfg.wallpapers;
            if (all == null || all.isEmpty()) return cfg.wallpaper;
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE).getString("image", "");
            File f = WallpaperRotation.find(all, shown);
            return f != null ? f : all.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Decodes the current wallpaper (about 960px wide) into view off the main thread. */
    static void load(Activity a, ImageView view) {
        File f = current(a);
        if (f == null) return;
        new Thread(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 960) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
                if (b != null) a.runOnUiThread(() -> view.setImageBitmap(b));
            } catch (Exception ignored) { }
        }, "settings-backdrop").start();
    }

    /** Wraps a programmatic page in wallpaper + scrim. */
    static View wrap(Activity a, View content) {
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xFF0B0D12);
        root.setClipChildren(false);
        ImageView wall = new ImageView(a);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(a);
        scrim.setBackgroundResource(R.drawable.settings_scrim);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        if (content.getParent() != null) ((ViewGroup) content.getParent()).removeView(content);
        content.setBackground(null);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        load(a, wall);
        return root;
    }
}
