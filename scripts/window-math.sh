# Window arithmetic shared by window-world.sh and window.sh; mirrors org.darkan.core.map.WorldWindow.
WORLD_TILES=1000000
WINDOW_TILES=16384
STRIDE_TILES=14336
HANDOVER_MARGIN=1024
PER_AXIS=70
ID_BASE=${WINDOW_WORLD_ID_BASE:-2000}
PORT_BASE=${WINDOW_PORT_BASE:-44000}
HOME_ID=${WINDOW_HOME_WORLD_ID:-0}

window_origin() { local o=$(($1 * STRIDE_TILES)); local max=$((WORLD_TILES - WINDOW_TILES)); echo $((o > max ? max : o)); }
window_owner() {
  if [ "$1" -lt $((STRIDE_TILES + HANDOVER_MARGIN)) ]; then echo 0; return; fi
  local w=$((($1 - HANDOVER_MARGIN) / STRIDE_TILES)); echo $((w > PER_AXIS - 1 ? PER_AXIS - 1 : w))
}
window_world_id() { if [ "$1" -eq 0 ] && [ "$2" -eq 0 ] && [ "$HOME_ID" -gt 0 ]; then echo "$HOME_ID"; else echo $((ID_BASE + $2 * PER_AXIS + $1)); fi; }
window_port() { echo $((PORT_BASE + $2 * PER_AXIS + $1)); }
