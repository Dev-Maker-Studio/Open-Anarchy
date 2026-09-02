#!/bin/bash
# Runs the lobby-server jar from the newest GitLab Release (a tag cut from dev), restarting on exit.
# Needs GITLAB_ACCESS_TOKEN (and DARKAN_GITLAB_PROJECT_ID). Server configuration comes from .env.
# Releases update on purpose: AUTO_UPDATE_CHANNEL=release in the environment opts a server into
# restarting onto new releases automatically.

GITLAB_API_URL="https://gitlab.com/api/v4"
PROJECT_ID="${DARKAN_GITLAB_PROJECT_ID:-67255206}"
export DARKAN_GITLAB_PROJECT_ID="$PROJECT_ID"

while :
do
  git pull origin dev 2>/dev/null || true
  # The cache source tree (cache-source/, a submodule) carries the gameval catalogs the server reads and, when
  # CACHE_SOURCE_PATH points at it, the cache it builds and serves; keep it at the commit this checkout pins.
  git submodule update --init --depth 1 cache-source 2>/dev/null || true

  RELEASE=$(curl -s --header "PRIVATE-TOKEN: ${GITLAB_ACCESS_TOKEN}" "${GITLAB_API_URL}/projects/${PROJECT_ID}/releases?order_by=released_at&sort=desc&per_page=1")
  TAG=$(echo "$RELEASE" | jq -r '.[0].tag_name // empty')
  URL=$(echo "$RELEASE" | jq -r '.[0].assets.links | map(select(.name | test("^lobby-server-.*\\.jar$"))) | .[0].url // empty')
  if [ -n "$URL" ] && curl -fL --header "PRIVATE-TOKEN: ${GITLAB_ACCESS_TOKEN}" "$URL" -o lobby-server.jar.new; then
    mv lobby-server.jar.new lobby-server.jar
    echo "Successfully downloaded lobby-server release $TAG"
  else
    rm -f lobby-server.jar.new
    echo "No release download; keeping the existing lobby-server.jar"
  fi

  BUILD_VERSION="$TAG" java $DARKAN_JAVA_VM_ARGS -jar lobby-server.jar >> mainlog.txt 2>&1

  echo "You have 5 seconds to stop the server using Ctrl-C. Server will restart otherwise"
  sleep 5
done
