#!/bin/bash
# Runs the newest lobby-server build published by CI from the dev branch, restarting on exit.
# Needs GITLAB_ACCESS_TOKEN (and DARKAN_GITLAB_PROJECT_ID, the darkanrs/server project id).
# Server configuration comes from .env in this directory.

GITLAB_API_URL="https://gitlab.com/api/v4"
PROJECT_ID="${DARKAN_GITLAB_PROJECT_ID:-67255206}"
export DARKAN_GITLAB_PROJECT_ID="$PROJECT_ID"
PROJECT_NAME="lobby-server"

CHANNEL="${AUTO_UPDATE_CHANNEL:-dev}"
export AUTO_UPDATE_CHANNEL="$CHANNEL"

# Newest published version on the channel: versions are <version>-<branch>-<pipeline id>, and
# pipeline ids only grow, so the highest id wins.
get_latest_version() {
  curl -s --header "PRIVATE-TOKEN: $GITLAB_ACCESS_TOKEN" \
    "$GITLAB_API_URL/projects/$PROJECT_ID/packages/maven/org/darkan/$PROJECT_NAME/maven-metadata.xml" \
    | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g' \
    | grep -- "-$CHANNEL-" | sort -t- -k3,3n | tail -1
}

while :
do
  git pull origin dev 2>/dev/null || true

  PACKAGE_VERSION=$(get_latest_version)
  if [ -n "$PACKAGE_VERSION" ]; then
    DOWNLOAD_URL="${GITLAB_API_URL}/projects/${PROJECT_ID}/packages/maven/org/darkan/${PROJECT_NAME}/${PACKAGE_VERSION}/${PROJECT_NAME}-${PACKAGE_VERSION}.jar"
    echo "Downloading package: ${DOWNLOAD_URL}"
    # Only swap the jar in once the download has fully succeeded; a failed fetch keeps the current build running.
    if curl -fL --header "PRIVATE-TOKEN: ${GITLAB_ACCESS_TOKEN}" "${DOWNLOAD_URL}" -o lobby-server.jar.new; then
      mv lobby-server.jar.new lobby-server.jar
      echo "Successfully downloaded the latest package release (${PROJECT_NAME}/${PACKAGE_VERSION})"
    else
      rm -f lobby-server.jar.new
      echo "Download failed; keeping the existing lobby-server.jar"
    fi
  else
    echo "No published ${PROJECT_NAME} package found; keeping the existing lobby-server.jar"
  fi

  # Beta servers follow the dev branch: the server watches the registry and restarts itself onto a newer
  # build through the system-update countdown (AUTO_UPDATE=false in the environment turns that off).
  AUTO_UPDATE="${AUTO_UPDATE:-true}" BUILD_VERSION="$PACKAGE_VERSION" java $DARKAN_JAVA_VM_ARGS -jar lobby-server.jar >> mainlog.txt 2>&1

  echo "You have 5 seconds to stop the server using Ctrl-C. Server will restart otherwise"
  sleep 5
done
