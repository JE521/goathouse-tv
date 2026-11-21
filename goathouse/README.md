# Goathouse TV setup kit

Everything here is generic. Your own values go in git-ignored files (`hosts.env`, `art/`) or on the TV itself.

| File | What |
|---|---|
| `hosts.env.example` | Name your TVs (`living=192.168.1.50`). Copy to `hosts.env`; every script takes a name or an address. |
| `push-theme.sh` | Push a theme (default `themes/goathouse`: glass dock, wallpapers, clock on the wallpaper) and restart Home. |
| `push-art.sh` | Push your own app tiles: `art/<package>.png` replaces that app's banner. |
| `debloat.sh` + `debloat/` | Disable Google TV's own apps in four batches with upstream's `tools/disable.sh`. Undo a batch with `tools/restore.sh goathouse/debloat/<batch>.txt`. |
| `obtainium/apps.json` | An [Obtainium](https://github.com/ImranR98/Obtainium) list of official open-source TV builds (Jellyfin, SmartTube, Moonlight, LocalSend, Key Mapper, Aurora Store). |

## 1. Install the launcher

```bash
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk PATH=$JAVA_HOME/bin:$PATH
cp goathouse/hosts.env.example goathouse/hosts.env && $EDITOR goathouse/hosts.env
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
(cd launcher && ./gradlew assembleRelease lintRelease)
INPUT_KEY_SERVICE=1 SKIP_BUILD=1 TV_HOST=192.168.1.50 launcher/build-and-install.sh
goathouse/push-theme.sh living
```

`build-and-install.sh` only disables the stock launcher once the new one is confirmed as Home. A reinstall or a
force-stop switches off the remote-button helper. `INPUT_KEY_SERVICE=1` turns it back on.

## 2. Connect your servers (from your phone)

On the TV, open Settings → **Accounts**. Scan the QR code and enter the PIN, then fill in only what you run:

| Service | What it enables | What to give it |
|---|---|---|
| Jellyfin | Rows, Who's watching, avatars | Server address + an API key (Dashboard → API Keys) |
| Navidrome | Music screen, on-TV playback | Server address + a login per person, or one shared login |
| Immich | Background and screensaver albums | Server address + an API key |
| Home Assistant | Voice (Assist), Music Assistant library | Server address + a long-lived token. Make a **non-admin** user for the TVs. |
| Music Assistant | The TV as a synced speaker (Sendspin) | Server address; approve the TV once in MA |

The values stay in the launcher's private storage on that TV. They never go in a theme, a script or git.

## 3. People

The rows and music follow whoever picks themselves in **Who's watching** (the Profile button, or the avatar
at the top). Family mode shows everyone in the room. Hide a user (Settings → People) and they're still
reachable by typing their name.

The Jellyfin TV app can only be opened as whoever it's signed in as. To make a card open on the right
person's account:
1. In the Jellyfin app, sign everyone in once (Quick Connect is easiest).
2. Set Settings → Authentication → login behaviour to **show the login screen**.
3. Leave the theme key `"jellyfin_asks_who": true` on (or Settings → People → *Jellyfin asks who's watching*).
   The launcher then says "Pick <name> in Jellyfin" as it opens the show.

## 4. Remote buttons

Settings → **Remote** shows the remote that's connected and every button it reports. A button you press joins
the list. Each one can be set to rows ↔ dock, profile, music, settings, Home, play/pause, next, previous,
nothing, or any app. *Learn a button…* catches buttons the remote doesn't report.

The defaults are worked out from what's installed:
- Branded buttons open whatever app handles that site's links.
- On a box with no TV inputs, Input opens Music.

Theme key `app_keys` (`"KEYCODE_FEATURED_APP_1:org.jellyfin.androidtv"`) sets a default for everyone using
that theme.

Test with real presses: `adb shell input keyevent` goes around the button helper.

## 5. Apps without the Play Store

Install Obtainium, then import `obtainium/apps.json`. Google TV has no file picker, so send each app as an
`obtainium://app/<json>` link:

```bash
python3 - <<'PY' | while read -r link; do adb -s 192.168.1.50:5555 shell am start -a android.intent.action.VIEW -d "'$link'"; done
import json, urllib.parse
for a in json.load(open("goathouse/obtainium/apps.json"))["apps"]:
    print("obtainium://app/" + urllib.parse.quote(json.dumps(a)))
PY
```

## 6. Debloat (optional, reversible)

```bash
goathouse/debloat.sh living goathouse/debloat/1-telemetry.txt    # one batch at a time; check the TV between them
```

The batches are telemetry/ads, preloaded apps, Google Assistant, and the Google TV setup wizard. Read each list
first: if you use YouTube, Netflix or Chromecast, comment those lines out. Undo any batch with
`TV_HOST=<tv ip> tools/restore.sh goathouse/debloat/<batch>.txt`.

## 7. When you're done

Network ADB on port 5555 is plaintext. Switch it off in Developer options and check with
`tools/adb-closed.py <tv ip>`.
