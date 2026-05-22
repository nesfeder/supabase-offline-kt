#!/bin/bash
set -e

GROUP=io.github.nesfeder.supabase-offline-kt
ARTIFACT=shared-android
REPO_URL=https://maven.pkg.github.com/nesfeder/supabase-offline-kt

# Read the current version from shared/build.gradle.kts
VERSION=$(grep '^version' shared/build.gradle.kts | awk -F'"' '{print $2}')
  if [ -z "$VERSION" ]; then
      echo "Error: Could not read the current version from shared/build.gradle.kts"
      exit 1
  fi

# Construct the URL to check for the artifact's POM file
GROUP_PATH=$(echo "$GROUP" | tr '.' '/')
URL="$REPO_URL/$GROUP_PATH/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION.pom"
echo "Checking if $URL exists in Maven repo..."

REPO_TOKEN=$(grep '^token=' github.properties | cut -d= -f2-)

if curl --head --silent --fail \
  -H "Authorization: Bearer $REPO_TOKEN" \
  "$URL" > /dev/null; then
  echo "Artifact $ARTIFACT version $VERSION already exists. Skipping publish."
  exit 78
else
    echo "Artifact $ARTIFACT version $VERSION does not exist. Proceeding with publish."
fi