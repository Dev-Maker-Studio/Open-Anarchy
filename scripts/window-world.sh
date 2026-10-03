#!/bin/bash
# Runs one 16384 x 16384 window of the 1,000,000 x 1,000,000 generated world as its own world with its own cache.
# usage: scripts/window-world.sh <windowX> <windowY> [lobby gateway url] [foreground]  (docs/infinite-world.md)
# With "foreground" the world replaces this shell (the lobby's orchestrator tracks the process); otherwise it runs detached.
set -eu
cd "$(dirname "$0")/.."
WX=${1:?window x}
WY=${2:?window y}
GATEWAY=${3:-ws://localhost:4041/social/ws}
MODE=${4:-detached}
export JAVA_HOME=$HOME/.jdks/jdk-26.0.2.1+1
. "$(dirname "$0")/low-impact.sh"
. "$(dirname "$0")/window-math.sh"
ID=$(window_world_id "$WX" "$WY")
export WORLD_WINDOW_X=$WX WORLD_WINDOW_Y=$WY
export WORLD_ID=$ID WORLD_PORT=$(window_port "$WX" "$WY") WORLD_NAME="Window $WX,$WY"
export CACHE_SOURCE_PATH=../empty-map CACHE_BUILD_PATH=./data/windows/${WX}_${WY}/cache CACHE_GENERATE_MAPS=true
export DISCOVERED_MAPS_PATH=./data/windows/${WX}_${WY}/discovered.txt
export CACHE_GATE_MAPS=${CACHE_GATE_MAPS:-true}
export SOCIAL_GATEWAY_URL=$GATEWAY
mkdir -p logs data/windows/${WX}_${WY}
if [ "$MODE" = foreground ]; then
  echo $$ > data/windows/${WX}_${WY}/world.pid
  exec $LOW_IMPACT world/build/install/world/bin/world
fi
nohup $LOW_IMPACT world/build/install/world/bin/world > logs/window-${WX}_${WY}.log 2>&1 & 
echo $! > data/windows/${WX}_${WY}/world.pid
echo "window $WX,$WY -> world $ID port $WORLD_PORT"
