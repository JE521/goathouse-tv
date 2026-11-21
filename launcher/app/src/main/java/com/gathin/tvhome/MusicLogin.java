package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * goathouse: sign a person into Navidrome on this TV. The login is checked against the server before it is saved,
 * and (when a person is given) linked to them so the Music screen follows the Home profile.
 */
final class MusicLogin {
    interface Done { void signedIn(String user); }

    private MusicLogin() { }

    static void show(Activity a, String suggestedUser, String linkPerson, Done done) {
        Accounts acc = Accounts.of(a);
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * dp);
        box.setPadding(pad, (int) (8 * dp), pad, 0);
        EditText user = new EditText(a);
        user.setHint("Navidrome username");
        user.setSingleLine(true);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (suggestedUser != null) user.setText(suggestedUser);
        EditText pass = new EditText(a);
        pass.setHint("Password");
        pass.setSingleLine(true);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(user);
        box.addView(pass);
        String title = linkPerson == null || linkPerson.isEmpty() ? "Add a music login" : "Sign in to music as " + linkPerson;
        AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage("Your Navidrome account. It stays on this TV.")
                .setView(box)
                .setPositiveButton("Sign in", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> {
            (user.getText().length() > 0 ? pass : user).requestFocus();
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                String u = user.getText().toString().trim(), p = pass.getText().toString();
                if (u.isEmpty() || p.isEmpty()) return;
                d.setMessage("Checking…");
                new Thread(() -> {
                    String err = null;
                    try {
                        new MusicSource.Navidrome(a, u, p).ping();
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                    String msg = err;
                    a.runOnUiThread(() -> {
                        if (msg != null) {
                            d.setMessage("That didn't work: " + (msg.contains("40") ? "wrong username or password" : msg));
                            return;
                        }
                        acc.addMusicLogin(u, p);
                        if (linkPerson != null && !linkPerson.isEmpty() && !linkPerson.equalsIgnoreCase(u)) acc.linkMusicUser(linkPerson, u);
                        d.dismiss();
                        if (done != null) done.signedIn(u);
                    });
                }).start();
            });
        });
        d.show();
    }
}
