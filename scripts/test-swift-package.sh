#!/usr/bin/env bash
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
#
# Runs the Swift package's tests on an iOS simulator against the core framework built from this
# checkout, with every compiler warning an error, then checks that the package's public API shows
# nothing of the core (scripts/check-swift-public-api.py).
#
# Usage, from the repository root, after building the core:
#   ./gradlew :supabase-offline-swift-bridge:assembleSupabaseOfflineCoreDebugXCFramework \
#       -Psupabase.offline.iosSqlite=system
#   scripts/test-swift-package.sh --simulator <UDID> --derived-data <DIR> [--framework <XCFRAMEWORK>]
#
# --framework tests against another build of the core, such as the release's (scripts/swift-release.py);
# the default is Gradle's debug XCFramework above. The framework is copied into swift/LocalCore
# (ignored by git), where Package.swift takes it from in a checkout (SUPABASE_OFFLINE_LOCAL_CORE=1,
# which the script sets, says the same). Everything xcodebuild writes goes under the derived-data directory,
# but for the link SwiftPM re-creates in ~/.swiftpm on every package build (home-snapshot.sh).
# When GITHUB_STEP_SUMMARY is set, the test count is added to it. Portable: bash 3.2 or later.

set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
simulator=""
derived_data=""
built="$root/supabase-offline-swift-bridge/build/XCFrameworks/debug/SupabaseOfflineCore.xcframework"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --simulator) simulator="${2:?--simulator needs a UDID}"; shift 2 ;;
        --derived-data) derived_data="${2:?--derived-data needs a directory}"; shift 2 ;;
        --framework) built="${2:?--framework needs an XCFramework}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
: "${simulator:?--simulator is required}" "${derived_data:?--derived-data is required}"

local_core="$root/swift/LocalCore"
if [[ ! -d "$built" || "$(basename "$built")" != SupabaseOfflineCore.xcframework ]]; then
    echo "No core framework at ${built#"$root"/}: build it first (see this script's usage)." >&2
    exit 1
fi
rm -rf "${local_core:?}/SupabaseOfflineCore.xcframework"
mkdir -p "$local_core"
cp -R "$built" "$local_core/"

log="$derived_data/swift-package-tests.log"
mkdir -p "$derived_data"
status=0
(
    cd "$root/swift"
    SUPABASE_OFFLINE_LOCAL_CORE=1 xcodebuild test \
        -scheme SupabaseOffline-Package \
        -destination "platform=iOS Simulator,id=$simulator" \
        -derivedDataPath "$derived_data" \
        -clonedSourcePackagesDirPath "$derived_data/packages" \
        -packageCachePath "$derived_data/package-cache" \
        -parallel-testing-enabled NO \
        SWIFT_TREAT_WARNINGS_AS_ERRORS=YES
) > "$log" 2>&1 || status=$?

# The compiler's diagnostics and the tests' own lines, not the whole build transcript.
grep -E "error:|warning: |✘|✔ Suite|Test run with|TEST (EXECUTE )?(SUCCEEDED|FAILED)" "$log" \
    | grep -v "appintentsmetadataprocessor" || true
if [[ "$status" -ne 0 ]]; then
    echo "xcodebuild failed (exit $status); the last lines of its output:" >&2
    tail -n 40 "$log" >&2
    exit "$status"
fi
summary="$(grep -E "Test run with [0-9]+ tests" "$log" | tail -n 1 || true)"
if [[ ! "$summary" =~ Test\ run\ with\ [1-9][0-9]*\ tests.*passed ]]; then
    echo "No passing test run was reported." >&2
    exit 1
fi
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    echo "Swift package: ${summary#*✔ }" >> "$GITHUB_STEP_SUMMARY"
fi

python3 "$root/scripts/check-swift-public-api.py" --products "$derived_data/Build/Products/Debug-iphonesimulator"
