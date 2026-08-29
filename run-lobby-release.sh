#!/bin/bash
# Runs the lobby-server jar attached to the newest GitLab release (master), restarting on exit.
# Needs GITLAB_ACCESS_TOKEN (and DARKAN_GITLAB_PROJECT_ID). Server configuration comes from .env.

GITLAB_API_URL="https://gitlab.com/api/v4"
PROJECT_ID="${DARKAN_GITLAB_PROJECT_ID:-67255206}"

while :
do
  git pull origin master 2>/dev/null || true

  PACKAGE_DOWNLOAD_URL=$(curl -s --header "PRIVATE-TOKEN: ${GITLAB_ACCESS_TOKEN}" "${GITLAB_API_URL}/projects/${PROJECT_ID}/releases?order_by=created_at&sort=desc" \
    | jq -r 'max_by(.released_at) | .assets.links | map(select(.name | test("lobby-server.*jar"))) | .[0].direct_asset_url')
  if [ -n "$PACKAGE_DOWNLOAD_URL" ] && [ "$PACKAGE_DOWNLOAD_URL" != "null" ] \
     && curl -fL --header "PRIVATE-TOKEN: ${GITLAB_ACCESS_TOKEN}" "${PACKAGE_DOWNLOAD_URL}" -o lobby-server.jar.new; then
    mv lobby-server.jar.new lobby-server.jar
    echo "Successfully downloaded the latest lobby-server release"
  else
    rm -f lobby-server.jar.new
    echo "No release download; keeping the existing lobby-server.jar"
  fi

  java $DARKAN_JAVA_VM_ARGS -jar lobby-server.jar >> mainlog.txt 2>&1

  echo "You have 5 seconds to stop the server using Ctrl-C. Server will restart otherwise"
  sleep 5
done
