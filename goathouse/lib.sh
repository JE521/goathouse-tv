# Shared by the goathouse/*.sh scripts: turn "living" (from hosts.env) or an address into host:port.
resolve_tv() {
  local want=$1 file=${GOATHOUSE_HOSTS:-$(dirname "${BASH_SOURCE[0]}")/hosts.env} line
  if [ -f "$file" ]; then
    while IFS= read -r line; do
      case $line in ''|'#'*) continue ;; esac
      [ "${line%%=*}" = "$want" ] && { want=${line#*=}; break; }
    done < "$file"
  fi
  case $want in *[!0-9.:a-zA-Z-]*|'') echo "unknown TV: $1 (add it to hosts.env or pass an address)" >&2; return 2 ;; esac
  case $want in *:*) echo "$want" ;; *) echo "$want:5555" ;; esac
}
