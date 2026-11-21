#!/usr/bin/env bash
# Push a theme to a TV and restart Home. Accounts (servers, keys) are not in the theme: set them on the TV,
# Settings > Accounts, from your phone.
# Usage: goathouse/push-theme.sh <tv name|address> [theme folder]      (default theme: themes/goathouse)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); . "$here/lib.sh"
tv=$(resolve_tv "${1:?usage: push-theme.sh <tv name|address> [theme folder]}")
theme=$(cd "${2:-$here/../themes/goathouse}" && pwd)
TV_HOST=${tv%:5555} "$here/../tools/theme.sh" "$theme"
