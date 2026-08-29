#!/bin/bash
# Runs the newest world-server build published by CI from the dev branch, restarting on exit.
# Needs GITLAB_ACCESS_TOKEN (and DARKAN_GITLAB_PROJECT_ID, the darkanrs/server project id).
# Server configuration comes from .env in this directory.

GITLAB_API_URL="https://gitlab.com/api/v4"
PROJECT_ID="${DARKAN_GITLAB_PROJECT_ID:-67255206}"
PROJECT_NAME="world-server"

get_latest_version() {
  curl -s --header "PRIVATE-TOKEN: $GITLAB_ACCESS_TOKEN" \
    "$GITLAB_API_URL/projects/$PROJECT_ID/packages/maven/org/darkan/$PROJECT_NAME/maven-metadata.xml" \
    | xmllint --xpath 'string(/metadata/versioning/latest)' - 2>/dev/null
}

while :
do
  git pull origin dev 2>/dev/null || true

  PACKAGE_VERSION=$(get_latest_version)
  if [ -n "$PACKAGE_VERSION" ]; then
    DOWNLOAD_URL="${GITLAB_API_URL}/projects/${PROJECT_ID}/packages/maven/org/darkan/${PROJECT_NAME}/${PACKAGE_VERSION}/${PROJECT_NAME}-${PACKAGE_VERSION}.jar"
    echo "Downloading package: ${DOWNLOAD_URL}"
    # Only swap the jar in once the download has fully succeeded; a failed fetch keeps the current build running.
    if curl -fL --header "PRIVATE-TOKEN: ${GITLAB_ACCESS_TOKEN}" "${DOWNLOAD_URL}" -o world-server.jar.new; then
      mv world-server.jar.new world-server.jar
      echo "Successfully downloaded the latest package release (${PROJECT_NAME}/${PACKAGE_VERSION})"
    else
      rm -f world-server.jar.new
      echo "Download failed; keeping the existing world-server.jar"
    fi
  else
    echo "No published ${PROJECT_NAME} package found; keeping the existing world-server.jar"
  fi

  java $DARKAN_JAVA_VM_ARGS -jar world-server.jar >> mainlog.txt 2>&1

  echo "You have 5 seconds to stop the server using Ctrl-C. Server will restart otherwise"
  sleep 5
done
