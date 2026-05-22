#!/bin/bash
set -e

SCHEME="SupabaseOfflineSupport"
BUILD_DIR="./ios/support/build"
PROJECT_DIR="./ios/support/$SCHEME.xcodeproj"
XCFRAMEWORK_OUTPUT_FILE="$BUILD_DIR/$SCHEME.xcframework"

# Clean and build the API framework for device and simulator independently
rm -rf $BUILD_DIR

xcodebuild archive \
  -project $PROJECT_DIR \
  -scheme $SCHEME \
  -destination "generic/platform=iOS" \
  -archivePath "$BUILD_DIR/ios" \
  SKIP_INSTALL=NO \
  BUILD_LIBRARY_FOR_DISTRIBUTION=YES

xcodebuild archive \
  -project $PROJECT_DIR \
  -scheme $SCHEME \
  -destination "generic/platform=iOS Simulator" \
  -archivePath "$BUILD_DIR/ios-sim" \
  SKIP_INSTALL=NO \
  BUILD_LIBRARY_FOR_DISTRIBUTION=YES

# Clean and package the XCFramework for the API framework
rm -rf "$API_XCFRAMEWORK_OUTPUT_FILE"

xcodebuild -create-xcframework \
  -framework "$BUILD_DIR/ios.xcarchive/Products/Library/Frameworks/$SCHEME.framework" \
  -framework "$BUILD_DIR/ios-sim.xcarchive/Products/Library/Frameworks/$SCHEME.framework" \
  -output "$XCFRAMEWORK_OUTPUT_FILE"