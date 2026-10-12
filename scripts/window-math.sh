WINDOW_TILES=15000
REGION_TILES=64
PER_AXIS=26
CENTER_X=12
CENTER_Y=13
GENERATED_SECTORS=${GENERATED_SECTORS:-MM}
ID_BASE=${WINDOW_WORLD_ID_BASE:-2000}
PORT_BASE=${WINDOW_PORT_BASE:-44000}
HOME_ID=${WINDOW_HOME_WORLD_ID:-0}

window_origin() { echo $(($1 * WINDOW_TILES / REGION_TILES * REGION_TILES)); }
window_owner() {
  local w=$(($1 / WINDOW_TILES)); [ "$w" -lt 0 ] && w=0; [ "$w" -gt $((PER_AXIS - 1)) ] && w=$((PER_AXIS - 1))
  while [ "$w" -lt $((PER_AXIS - 1)) ] && [ "$(window_origin $((w + 1)))" -le "$1" ]; do w=$((w + 1)); done
  while [ "$w" -gt 0 ] && [ "$(window_origin "$w")" -gt "$1" ]; do w=$((w - 1)); done
  echo "$w"
}
window_name() { local letters=ABCDEFGHIJKLMNOPQRSTUVWXYZ; echo "${letters:$((PER_AXIS - 1 - $2)):1}${letters:$1:1}"; }
window_generated() {
  [ "$1" -ge 0 ] && [ "$1" -lt "$PER_AXIS" ] && [ "$2" -ge 0 ] && [ "$2" -lt "$PER_AXIS" ] || return 1
  case ",$GENERATED_SECTORS," in *",$(window_name "$1" "$2"),"*) return 0 ;; *) return 1 ;; esac
}
window_world_id() { if [ "$1" -eq "$CENTER_X" ] && [ "$2" -eq "$CENTER_Y" ] && [ "$HOME_ID" -gt 0 ]; then echo "$HOME_ID"; else echo $((ID_BASE + $2 * PER_AXIS + $1)); fi; }
window_port() { echo $((PORT_BASE + $2 * PER_AXIS + $1)); }
