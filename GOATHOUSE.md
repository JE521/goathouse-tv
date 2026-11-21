# Goathouse fork of the CleanYourTV launcher

This fork ([JE521/goathouse-tv](https://github.com/JE521/goathouse-tv), branch `main`) turns the upstream CleanYourTV launcher
(package `com.gathin.tvhome`, plain Java, no AndroidX UI libs beyond RecyclerView/DynamicAnimation) into a
tvOS-style home screen for a self-hosted house: Jellyfin, Navidrome, Immich, Home Assistant and Music
Assistant, set up from a phone, with no Google UI anywhere.

Upstream rules in `AGENTS.md` (no network, no new permissions, APK size gate…) apply to **upstream PRs only**.
This fork deliberately breaks them (INTERNET, foreground services, ~730 KB APK). Never open upstream PRs from it.

## What the fork adds, by area

| Area | Files | What it does |
|---|---|---|
| Accounts | `Accounts.java`, `AccountsActivity.java`, `SetupServer.java` | All server addresses/keys live in private prefs `accounts`. `AccountsActivity` shows a QR + PIN; `SetupServer` (port 8780, only while that screen is open) serves a phone form and tests each service live. Nothing is typed on the TV. |
| Media rows | `FeedClient.java`, `MediaShelf.java`, `ShelfScrollView.java` | Rows above the dock: Continue Watching / Next Up (Jellyfin, per person), Recently Added, Playlists, New Music, Recently Played (Music Assistant). Images by key: `jf:`, `nd:`, `im:`, `jfu:`, http(s). Cards open Jellyfin by deep link (`StartupActivity`, extra `ItemId`) or music in Now Playing. |
| Dock | `MainActivity.java` (`dockTucked`, `applyTuck`, `toggleShelfDock`), `DockDecoration.java` | Dock tucks under the bottom edge while the rows have focus; Guide button swaps rows ↔ dock. |
| People | `ProfileActivity.java`, `PeopleActivity.java` | "Who's watching" carousel (Jellyfin users + avatars), Family mode (everyone in the room), hidden users reachable by typed name. The profile drives rows and music. |
| Settings | `LauncherSettingsActivity.java`, `SettingsBackdrop.java` | tvOS page stack built in code: categories → pages; rows are nav / choice (✓ list) / toggle / plain. Left pane shows hero icon + description of the focused row. |
| Remote | `InputKeyService.java` (accessibility key filter), `ButtonMap.java` | Buttons are detected: `InputDevice.hasKeys()` on paired remotes + every real press recorded (`button_seen`). Actions: shelf, profile, music, settings, home, music play/pause/next/prev, none, `app:<pkg>`. Branded buttons (onn YouTube/Netflix) open the installed app that handles that site's links. Input → Music on boxes with no TV inputs. "Learn a button…" for anything else. |
| Voice | `VoiceActivity.java`, `HaPipeline.java`, `WhisperRecognitionService.java`, `MiniWebSocket.java` | Mic button / `android.speech.action.WEB_SEARCH` → Home Assistant Assist pipeline over websocket (streams 16 kHz PCM, plays TTS). Also registered as the system speech recognizer. |
| Music Assistant player | `SendspinService.java`, `SendspinClient.java`, `Noise.java`, `TimeFilter.java` | The TV is a Sendspin player/controller/metadata client of Music Assistant: Noise_KKpsk2_25519_AESGCM_SHA256, Kalman clock sync, PCM AudioTrack with drop/dup drift correction. Foreground `mediaPlayback` service. Finds its own HA `media_player` by name; on a name clash it probes with an inaudible `volume_set` (only its own player relays it). |
| Music screen | `MusicActivity.java`, `MusicSource.java`, `LocalPlayer.java`, `MusicLogin.java`, `NowPlayingActivity.java` | Tabs Playlists/Albums/Artists/Recent/Up Next/Search; source = Music Assistant (via HA services) or Navidrome (Subsonic, per-person logins + links + Family account). Albums stream in pages and are cached in `cacheDir/albums-<source>.json` (24 h). A–Z strip. Player bar peeks when lowered (hold Guide), OK → Now Playing. `LocalPlayer`: MediaSession, audio focus, gapless (`setNextMediaPlayer`), Up Next, quality, own FGS. |
| Photos | `PhotosActivity.java`, `WallpaperPlayer.java` | Immich albums picked as background / screensaver. |
| Tile art | `AppAdapter.localArt`, `ThemeFiles.ART` | `art/<package>.png` pushed through the theme provider overrides any app's banner (kept out of git: other companies' artwork). |

## Build and install

```bash
export JAVA_HOME=~/.local/android/jdk17 ANDROID_HOME=~/.local/android/sdk PATH=$JAVA_HOME/bin:$PATH
cd launcher && ./gradlew assembleRelease lintRelease      # lint must report 0 errors
INPUT_KEY_SERVICE=1 SKIP_BUILD=1 TV_HOST=<tv ip> ./build-and-install.sh
```

`INPUT_KEY_SERVICE=1` re-enables the remote-button helper (a reinstall or force-stop turns it off).

## Testing traps

- `adb shell input keyevent` **bypasses the accessibility key filter**: remote mappings only work with a real press.
- `am force-stop com.gathin.tvhome` disables `InputKeyService`; re-enable with
  `settings put secure enabled_accessibility_services com.gathin.tvhome/.InputKeyService` and `accessibility_enabled 1`.
- Non-exported activities can't be started from the shell; `.MusicApp` (the Music tile alias) can.
- Network calls must never run on the main thread (a websocket `close()` on it crashed the whole app once).

## Setup kit and site

`goathouse/` holds the user-facing kit (TV names in a git-ignored `hosts.env`, theme/art/debloat scripts, the
Obtainium list): see `goathouse/README.md`. The look is `themes/goathouse/`. The project site is `site/`
(static, deployed to GitHub Pages by `.github/workflows/pages.yml`). The repo front page is `.github/README.md`;
the root `README.md` stays upstream's so upstream merges stay clean. Nothing personal goes in any of these.
