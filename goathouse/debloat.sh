#!/usr/bin/env bash
# Disable (never uninstall) the Google TV packages in goathouse/debloat/*.txt, batch by batch, using upstream's
# tools/disable.sh (it records each package's state so tools/restore.sh <same list> can undo it).
# Install the launcher first (launcher/build-and-install.sh) so the TV still has a Home.
# Usage: goathouse/debloat.sh <tv name|address> [batch file...]          (default: all four batches)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); . "$here/lib.sh"
T=$(resolve_tv "${1:?usage: debloat.sh <tv name|address> [batch file...]}"); shift
[ $# -gt 0 ] || set -- "$here"/debloat/*.txt
for list in "$@"; do
  echo "== $(basename "$list")"
  TV_HOST=${T%:5555} "$here/../tools/disable.sh" "$(cd "$(dirname "$list")" && pwd)/$(basename "$list")"
done
