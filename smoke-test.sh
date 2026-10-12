#!/bin/bash
set -u
cd "$(dirname "$0")"

LOG_DIR="${SMOKE_LOG_DIR:-/tmp/darkan-smoke}"
STARTUP_TIMEOUT="${SMOKE_TIMEOUT:-240}"
mkdir -p "$LOG_DIR"
LOBBY_LOG="$LOG_DIR/lobby.log"
WORLD_LOG="$LOG_DIR/world.log"
: > "$LOBBY_LOG"; : > "$WORLD_LOG"

cleanup() {
  [ -n "${WORLD_PID:-}" ] && kill "$WORLD_PID" 2>/dev/null
  [ -n "${LOBBY_PID:-}" ] && kill "$LOBBY_PID" 2>/dev/null
  wait 2>/dev/null
}
trap cleanup EXIT

wait_for() {
  local waited=0
  until grep -q "$2" "$1"; do
    if grep -qiE "Exception|FATAL|SEVERE" "$1"; then
      echo "FAIL: $3 - error in log:"; grep -iE -m3 -A3 "Exception|FATAL|SEVERE" "$1"; return 1
    fi
    sleep 2; waited=$((waited + 2))
    if [ "$waited" -ge "$STARTUP_TIMEOUT" ]; then echo "FAIL: $3 - timed out after ${STARTUP_TIMEOUT}s"; tail -20 "$1"; return 1; fi
  done
  echo "ok: $3 (${waited}s)"
}

echo "Building..."
./gradlew :lobby:installDist :world:installDist -q --console=plain 2>&1 | grep -vE "^WARNING|^Failed to get project id" || { echo "FAIL: build"; exit 1; }

echo "Starting lobby..."
lobby/build/install/lobby/bin/lobby > "$LOBBY_LOG" 2>&1 &
LOBBY_PID=$!
wait_for "$LOBBY_LOG" "Lobby server started" "lobby up" || exit 1

echo "Starting world..."
world/build/install/world/bin/world > "$WORLD_LOG" 2>&1 &
WORLD_PID=$!
wait_for "$WORLD_LOG" "World server started" "world up" || exit 1
wait_for "$LOBBY_LOG" "SocialGateway: world connected" "world registered with lobby gateway" || exit 1

echo "Checking /health..."
curl -sf "http://127.0.0.1:${LOBBY_API_PORT:-4040}/health" >/dev/null && echo "ok: lobby /health" || { echo "FAIL: /health"; exit 1; }
curl -s "http://127.0.0.1:${LOBBY_API_PORT:-4040}/worldlist" | head -c 300; echo

sleep 6
if grep -qiE "Exception|SEVERE" "$WORLD_LOG" "$LOBBY_LOG"; then
  echo "FAIL: errors after startup:"; grep -iE -m5 -A3 "Exception|SEVERE" "$WORLD_LOG" "$LOBBY_LOG"; exit 1
fi
echo "SMOKE TEST PASSED"
