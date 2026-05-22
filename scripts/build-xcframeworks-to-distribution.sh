#!/bin/bash
set -e

# Builds both iOS XCFrameworks (KMP + Support) and copies them to the distribution folder.
# Use this script when you want to manually generate and publish the XCFrameworks locally
# (e.g. for testing or a manual release), without CI or git operations.
#
# Prerequisites: Run from the repository root. Xcode and Gradle must be available.
#
# Usage: ./scripts/build-xcframework-to-distribution.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

DISTRIBUTION_DIR="./ios/distribution"
KMP_XCFRAMEWORK_SRC="./shared/build/XCFrameworks/release/SupabaseOffline.xcframework"
SUPPORT_XCFRAMEWORK_SRC="./ios/support/build/SupabaseOfflineSupport.xcframework"
KMP_XCFRAMEWORK_DEST="$DISTRIBUTION_DIR/SupabaseOffline.xcframework"
SUPPORT_XCFRAMEWORK_DEST="$DISTRIBUTION_DIR/SupabaseOfflineSupport.xcframework"

echo "Building XCFrameworks..."
./gradlew :shared:assembleSupabaseOfflineReleaseXCFramework

# assembleSupabaseOfflineReleaseXCFramework builds the KMP framework and triggers
# buildSupportXCFramework, so both artifacts should exist after the above.

if [ ! -d "$KMP_XCFRAMEWORK_SRC" ]; then
  echo "Error: KMP XCFramework not found at $KMP_XCFRAMEWORK_SRC"
  exit 1
fi
if [ ! -d "$SUPPORT_XCFRAMEWORK_SRC" ]; then
  echo "Error: Support XCFramework not found at $SUPPORT_XCFRAMEWORK_SRC"
  exit 1
fi

echo "Copying XCFrameworks to distribution..."
rm -rf "$DISTRIBUTION_DIR"
mkdir -p "$DISTRIBUTION_DIR"
cp -R "$KMP_XCFRAMEWORK_SRC" "$KMP_XCFRAMEWORK_DEST"
cp -R "$SUPPORT_XCFRAMEWORK_SRC" "$SUPPORT_XCFRAMEWORK_DEST"

echo "Done. XCFrameworks are in $DISTRIBUTION_DIR:"