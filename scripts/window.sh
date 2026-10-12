#!/bin/bash
set -eu
cd "$(dirname "$0")/.."
. scripts/window-math.sh
WAIT_SECONDS=${WINDOW_WAIT_SECONDS:-3600}

listening() { ss -ltn 2>/dev/null | grep -q ":$1 "; }

ensure() {
  local wx=$1 wy=$2 gateway=${3:-ws://localhost:4041/social/ws}
  local id port
  if ! window_generated "$wx" "$wy"; then echo "sector $wx,$wy is not generated (GENERATED_SECTORS=$GENERATED_SECTORS)" >&2; return 1; fi
  id=$(window_world_id "$wx" "$wy")
  port=$(window_port "$wx" "$wy")
  if listening "$port"; then echo "window $wx,$wy is running as world $id"; return; fi
  if ! window_running "$wx" "$wy"; then
    scripts/window-world.sh "$wx" "$wy" "$gateway" > /dev/null
  fi
  local waited=0
  until listening "$port"; do
    sleep 5
    waited=$((waited + 5))
    if [ "$waited" -ge "$WAIT_SECONDS" ]; then echo "window $wx,$wy did not come up in ${WAIT_SECONDS}s" >&2; return 1; fi
  done
  echo "window $wx,$wy is running as world $id"
}

window_running() { [ -f "data/windows/$1_$2/world.pid" ] && kill -0 "$(cat "data/windows/$1_$2/world.pid")" 2>/dev/null; }

store_position() {
  local user=$1 gx=$2 gy=$3 level=${4:-0}
  local uri=${MONGO_URI:-$(grep -m1 '^MONGO_URI=' .env | cut -d= -f2-)}
  mongosh --quiet "${uri:-mongodb://localhost:27017/darkan-server}" --eval \
    "db.window_positions.replaceOne({_id: '$user'}, {_id: '$user', x: NumberInt($gx), y: NumberInt($gy), level: NumberInt($level)}, {upsert: true})" > /dev/null
}

handover() {
  local user=${1:?user} gx=${2:?global x} gy=${3:?global y} level=${4:-0}
  local wx wy
  wx=$(window_owner "$gx")
  wy=$(window_owner "$gy")
  store_position "$user" "$gx" "$gy" "$level"
  ensure "$wx" "$wy" "${WINDOW_GATEWAY:-ws://localhost:4041/social/ws}" > /dev/null
  echo "$(window_world_id "$wx" "$wy")"
}

status() {
  for dir in data/windows/*_*; do
    [ -d "$dir" ] || continue
    local name=${dir#data/windows/} wx wy
    wx=${name%_*}; wy=${name#*_}
    if listening "$(window_port "$wx" "$wy")"; then echo "window $wx,$wy world $(window_world_id "$wx" "$wy") running"; else echo "window $wx,$wy world $(window_world_id "$wx" "$wy") stopped"; fi
  done
}

stop() {
  local wx=$1 wy=$2 pid
  pid=$(ss -ltnp 2>/dev/null | grep ":$(window_port "$wx" "$wy") " | grep -o 'pid=[0-9]*' | cut -d= -f2 | head -1)
  [ -n "$pid" ] && kill "$pid" && echo "stopped window $wx,$wy" || echo "window $wx,$wy is not running"
}

case "${1:-}" in
  ensure) ensure "${2:?window x}" "${3:?window y}" "${4:-}" ;;
  handover) handover "${2:-}" "${3:-}" "${4:-}" "${5:-0}" ;;
  status) status ;;
  stop) stop "${2:?window x}" "${3:?window y}" ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
