#!/usr/bin/env bash
# Put your own app tiles on a TV: <package>.png files (e.g. org.jellyfin.androidtv.png, 16:9) override that app's
# banner. Keep other companies' artwork out of git - goathouse/art/ is git-ignored.
# Usage: goathouse/push-art.sh <tv name|address> [art folder]           (default: goathouse/art)
# Remove one: adb shell content delete --uri content://com.gathin.tvhome.theme/art/<package>.png
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); . "$here/lib.sh"
T=$(resolve_tv "${1:?usage: push-art.sh <tv name|address> [art folder]}")
dir=${2:-$here/art}
adb connect "$T" >/dev/null
n=0
for f in "$dir"/*.png; do
  [ -e "$f" ] || { echo "no .png files in $dir" >&2; exit 2; }
  name=$(basename "$f")
  staged="/data/local/tmp/art-$name"
  adb -s "$T" push "$f" "$staged" >/dev/null
  out=$(adb -s "$T" shell "content write --uri content://com.gathin.tvhome.theme/art/$name < $staged; rm -f $staged" 2>&1)
  if [ -n "$out" ]; then echo "$name: $out" >&2; else n=$((n+1)); fi
done
# Restart Home so the tiles reload; a force-stop also switches the remote-button helper off, so turn it back on.
adb -s "$T" shell am force-stop com.gathin.tvhome
adb -s "$T" shell settings put secure enabled_accessibility_services com.gathin.tvhome/.InputKeyService
adb -s "$T" shell settings put secure accessibility_enabled 1
# No jump to Home: if someone is in an app they stay there, and Home reloads the next time it opens.
echo "$n pictures on $T"
