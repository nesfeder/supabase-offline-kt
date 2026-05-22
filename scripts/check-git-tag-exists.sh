#!/bin/bash
set -e

# Read the current version from shared/build.gradle.kts
VERSION=$(grep '^version' shared/build.gradle.kts | awk -F'"' '{print $2}')
  if [ -z "$VERSION" ]; then
      echo "Error: Could not read the current version from shared/build.gradle.kts"
      exit 1
  fi
  echo "Current version: $VERSION"

# Ensure the git tag for the current version does not already exist
if git rev-parse "refs/tags/$VERSION" > /dev/null 2>&1; then
    echo "Version $VERSION tag is already published. Skipping XCFramework publish."
    exit 78
fi
echo "Tag $VERSION is not published yet. Proceeding with publish."