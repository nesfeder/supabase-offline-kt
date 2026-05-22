#!/usr/bin/env bash
#
# Builds both iOS XCFrameworks, zips them, computes SHA-256 checksums, and rewrites the
# constants at the top of Package.swift in place so SwiftPM resolves against the matching
# GitHub Release.
#
# After running this script you still need to:
#   1) Inspect the diff in Package.swift
#   2) Commit the change on your release branch
#   3) `gh release create <version>` (or via the GitHub UI) and upload the two zips
#      from `ios/distribution/` as release assets
#   4) Tag and push as documented in docs/release.md
#
# If `gh` is installed and `--publish` is passed, steps 3 + 4 happen automatically.
#
# Usage:
#   ./scripts/release-xcframeworks.sh                # builds + zips + rewrites Package.swift
#   ./scripts/release-xcframeworks.sh --publish      # additionally creates the GH release
#                                                    # and uploads the zips (requires `gh auth`)
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

PUBLISH=false
if [[ "${1:-}" == "--publish" ]]; then
    PUBLISH=true
fi

# 1) Read version from shared/build.gradle.kts
VERSION=$(grep '^version' shared/build.gradle.kts | awk -F'"' '{print $2}')
if [ -z "$VERSION" ]; then
    echo "Error: Could not read the current version from shared/build.gradle.kts"
    exit 1
fi
echo "Releasing version: $VERSION"

# 2) Build the XCFrameworks via the existing helper (also copies into ios/distribution/)
./scripts/build-xcframeworks-to-distribution.sh

KMP_FRAMEWORK="ios/distribution/SupabaseOffline.xcframework"
SUPPORT_FRAMEWORK="ios/distribution/SupabaseOfflineSupport.xcframework"
KMP_ZIP="ios/distribution/SupabaseOffline.xcframework.zip"
SUPPORT_ZIP="ios/distribution/SupabaseOfflineSupport.xcframework.zip"

if [ ! -d "$KMP_FRAMEWORK" ] || [ ! -d "$SUPPORT_FRAMEWORK" ]; then
    echo "Error: expected XCFrameworks at $KMP_FRAMEWORK and $SUPPORT_FRAMEWORK but at least one is missing."
    exit 1
fi

# 3) Zip each XCFramework (cd into ios/distribution/ so the archive root is the xcframework itself)
echo "Zipping XCFrameworks..."
rm -f "$KMP_ZIP" "$SUPPORT_ZIP"
(cd ios/distribution && zip -r -q "SupabaseOffline.xcframework.zip" "SupabaseOffline.xcframework")
(cd ios/distribution && zip -r -q "SupabaseOfflineSupport.xcframework.zip" "SupabaseOfflineSupport.xcframework")

# 4) Compute SwiftPM checksums (uses `swift package compute-checksum` so the hash format
#    matches what SwiftPM expects in `.binaryTarget(checksum:)`).
echo "Computing SwiftPM checksums..."
KMP_CHECKSUM=$(swift package compute-checksum "$KMP_ZIP")
SUPPORT_CHECKSUM=$(swift package compute-checksum "$SUPPORT_ZIP")
echo "  SupabaseOffline.xcframework.zip        $KMP_CHECKSUM"
echo "  SupabaseOfflineSupport.xcframework.zip $SUPPORT_CHECKSUM"

# 5) Rewrite Package.swift in place.
echo "Updating Package.swift..."
TMP_FILE=$(mktemp)
sed \
    -e "s|^let version = .*|let version = \"$VERSION\"|" \
    -e "s|^let kmpChecksum     = \".*\"$|let kmpChecksum     = \"$KMP_CHECKSUM\"|" \
    -e "s|^let supportChecksum = \".*\"$|let supportChecksum = \"$SUPPORT_CHECKSUM\"|" \
    Package.swift > "$TMP_FILE"
mv "$TMP_FILE" Package.swift

echo ""
echo "Package.swift updated to:"
grep -E '^let (version|kmpChecksum|supportChecksum)' Package.swift

# 6) Optional: create the GitHub release and upload the zips
if [ "$PUBLISH" = true ]; then
    if ! command -v gh >/dev/null 2>&1; then
        echo "Error: --publish requires the GitHub CLI (\`gh\`). Install from https://cli.github.com/."
        exit 1
    fi
    echo ""
    echo "Creating GitHub release $VERSION and uploading XCFramework zips..."
    gh release create "$VERSION" \
        --title "$VERSION" \
        --generate-notes \
        "$KMP_ZIP" \
        "$SUPPORT_ZIP"
    echo "Release published: https://github.com/nesfeder/supabase-offline-kt/releases/tag/$VERSION"
else
    cat <<EOF

Next steps (manual):
  1. Review the Package.swift diff and commit it on your release branch.
  2. Create a GitHub release for tag '$VERSION' and upload both:
        $KMP_ZIP
        $SUPPORT_ZIP
     Or re-run with --publish to do that automatically (requires \`gh auth login\`).
EOF
fi
