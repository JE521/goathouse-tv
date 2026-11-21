# Goathouse TV

A calm, tvOS-style home screen for Android TV / Google TV boxes, built for a **self-hosted house**:
Jellyfin, Navidrome, Immich, Home Assistant and Music Assistant, set up from your phone, with no Google UI
anywhere.

It is a fork of [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT) by Gathin, which does the hard part
of replacing the stock launcher and debloating the TV safely. Goathouse TV adds the media and smart-home layer.

**Project site:** https://je521.github.io/goathouse-tv/

## What you get

- **Rows above the dock**: Continue Watching and Next Up for whoever is watching, Recently Added, playlists, new
  music and recently played. The app dock tucks under the bottom edge until you want it.
- **Who's watching**: a carousel of your Jellyfin users with their avatars, plus a Family mode. The rows and the
  music follow the person.
- **Music screen**: playlists, albums, artists, recent, Up Next and search, with an A–Z jump strip, from
  **Music Assistant** (through Home Assistant) or **Navidrome** (per-person logins). There's a player bar that
  peeks up from the bottom, and a Now Playing screen with blurred artwork.
- **The TV is a Music Assistant speaker** (Sendspin, in sync with the rest of the house), and also a
  Navidrome player with gapless playback, media keys and streaming-quality choice.
- **Voice**: the remote's mic button runs your Home Assistant Assist pipeline. It also becomes the system speech
  recognizer, so in-app mic buttons use your Whisper.
- **Photos**: Immich albums as the background and screensaver.
- **Remote buttons are detected, not hard-coded**: press a button and it shows up in Settings → Remote. Map any
  button to rows, profile, music, settings, play/pause, or any app. Branded buttons (YouTube, Netflix…) open
  whichever installed app handles that site.
- **Setup from your phone**: Settings → Accounts shows a QR code and a PIN. Fill in your servers on your phone,
  and each one is tested live. Nothing is typed with the remote, and nothing secret is ever in this repo or a
  theme.

## Install

You need a PC with `adb`, JDK 17 and the Android SDK (platform 34). Turn on Developer options and network
debugging on the TV.

```bash
git clone https://github.com/JE521/goathouse-tv && cd goathouse-tv
cp goathouse/hosts.env.example goathouse/hosts.env      # name your TVs: living=192.168.1.50
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt

cd launcher && ./gradlew assembleRelease && cd ..
INPUT_KEY_SERVICE=1 SKIP_BUILD=1 TV_HOST=192.168.1.50 launcher/build-and-install.sh   # becomes Home
goathouse/push-theme.sh living                          # the Goathouse look (themes/goathouse)
goathouse/debloat.sh living                             # optional: disable Google TV's own apps (undo: tools/restore.sh)
```

Then on the TV, go to Settings → **Accounts**, scan the code, and fill in only the services you run.

The full guide covers per-person Jellyfin, remote buttons, Obtainium app installs and your own tile art:
[`goathouse/README.md`](../goathouse/README.md). The architecture for contributors is in
[`GOATHOUSE.md`](../GOATHOUSE.md).

## Keep your values private

Everything personal lives on the TV (private app storage) or in git-ignored files. That covers server addresses,
API keys, passwords, tokens, your TVs' addresses (`goathouse/hosts.env`) and your tile art (`goathouse/art/`).
The defaults in the code are placeholders. If you keep your own fork, keep it that way.

## Credits and license

MIT, like upstream: see [`LICENSE`](../LICENSE). Launcher, debloat lists, tools and themes come from
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) and its contributors; its README is
[`README.md`](../README.md). The wallpapers are free-licensed photos credited in each theme's `CREDITS.md`. App
names and logos belong to their owners, and none ship here.
