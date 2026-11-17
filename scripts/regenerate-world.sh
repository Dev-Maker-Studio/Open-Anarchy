#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: scripts/regenerate-world.sh <systemd user unit> [--yes]"
  echo "Run from the world's server folder while its unit is stopped."
  exit 1
}

[ $# -ge 1 ] || usage
UNIT=$1
CONFIRMED=${2:-}
VANILLA_CACHE=${VANILLA_CACHE:-/data/projects/Personal/darkan-vanilla/vanilla-cache}
MONGO_CONTAINER=${MONGO_CONTAINER:-darkan-mongo-new}
SPAWN_X=${SPAWN_X:-7500}
SPAWN_Y=${SPAWN_Y:-7500}
SPAWN_PLANE=${SPAWN_PLANE:-0}

setting() {
  grep -E "^$1=" .env | tail -1 | cut -d= -f2-
}

[ -f .env ] || { echo "No .env here, run from the world's server folder"; exit 1; }
[ "$(setting CACHE_GENERATE_MAPS)" = true ] || { echo "This world is not generated"; exit 1; }
if systemctl --user is-active --quiet "$UNIT"; then
  echo "$UNIT is running, stop it first"
  exit 1
fi

WORLD_ID=$(setting WORLD_ID)
TREE=$(realpath "$(setting CACHE_SOURCE_PATH)")
BUILD=$(setting CACHE_BUILD_PATH)
DATABASE=$(setting MONGO_URI)
DATABASE=${DATABASE##*/}
DATABASE=${DATABASE%%\?*}
BACKUP=data/backup-regenerate-$(date +%Y%m%d-%H%M%S)

[ -d "$TREE/maps" ] || { echo "No maps folder in $TREE"; exit 1; }
[ -d "$VANILLA_CACHE/maps" ] || { echo "No vanilla maps in $VANILLA_CACHE"; exit 1; }

echo "World $WORLD_ID, tree $TREE, build $BUILD, database $DATABASE, backup $BACKUP"
if [ "$CONFIRMED" != --yes ]; then
  read -r -p "Regenerate this world? Its saved squares and world state move to the backup. [y/N] " answer
  [ "$answer" = y ] || exit 1
fi

mkdir -p "$BACKUP"

podman exec "$MONGO_CONTAINER" mongodump --quiet --db "$DATABASE" --archive=/tmp/regenerate.archive
podman cp "$MONGO_CONTAINER":/tmp/regenerate.archive "$BACKUP/mongo-$DATABASE.archive"
podman exec "$MONGO_CONTAINER" rm /tmp/regenerate.archive

find "$TREE/maps" -mindepth 1 -maxdepth 1 -type d ! -empty -printf '%f\n' | sort | while read -r square; do
  cmp -s "$TREE/maps/$square/terrain.json" "$VANILLA_CACHE/maps/$square/terrain.json" 2>/dev/null || echo "$square"
done > "$BACKUP/saved-squares.txt"
if [ -s "$BACKUP/saved-squares.txt" ]; then
  tar -C "$TREE/maps" -czf "$BACKUP/saved-squares.tar.gz" -T "$BACKUP/saved-squares.txt"
  (cd "$TREE/maps" && xargs -a "$OLDPWD/$BACKUP/saved-squares.txt" rm -r)
fi
echo "Removed $(wc -l < "$BACKUP/saved-squares.txt") saved squares"

for state in "data/world-delta/sector-$WORLD_ID.json" "data/world-delta/sector-$WORLD_ID-claims.json" "data/city-homes/sector-$WORLD_ID.json" \
  "data/roads/sector-$WORLD_ID.json" "data/city-shops/sector-$WORLD_ID" \
  "data/generated-scenery/sector-$WORLD_ID" data/worldmap-squares-v1 data/worldmap-visited.txt data/quests/world.json; do
  if [ -e "$state" ]; then
    mkdir -p "$BACKUP/$(dirname "$state")"
    mv "$state" "$BACKUP/$state"
  fi
done
for quests in data/quests/*/generated.json; do
  [ -e "$quests" ] || continue
  mkdir -p "$BACKUP/$(dirname "$quests")"
  mv "$quests" "$BACKUP/$quests"
done

rm -rf "$BUILD"

podman exec "$MONGO_CONTAINER" mongosh --quiet "$DATABASE" --eval "
const moved = db.players.updateMany({}, {
  \$set: {tile: {x: $SPAWN_X, y: $SPAWN_Y, plane: $SPAWN_PLANE}},
  \$unset: {'savingAttributes.respawnBedTile': '', 'savingAttributes.respawnBedLoc': '', 'savingAttributes.respawnWakeTile': ''}
});
print('Moved ' + moved.modifiedCount + ' of ' + moved.matchedCount + ' players to the spawn');
"

echo "Done. Start the world alone so nothing else packs into $BUILD at the same time:"
echo "  systemctl --user start --job-mode=ignore-dependencies $UNIT"
echo "If a lobby serves $BUILD, keep it stopped until the world is listening, then start it."
