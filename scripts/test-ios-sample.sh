#!/usr/bin/env bash
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
#
# Builds the iOS sample (samples/ios) against the Swift package in swift/, with every warning of the
# sample's own targets an error, and runs its model tests and its UI smoke test on an iOS simulator.
# The tests run in demo mode: nothing reaches a Supabase project.
#
# Usage, from the repository root, once swift/LocalCore holds the framework
# (scripts/prepare-local-swift-package.sh, or scripts/test-swift-package.sh, which copies the same
# build there):
#   scripts/test-ios-sample.sh --simulator <UDID> --derived-data <DIR>
#
# Everything xcodebuild writes goes under the derived-data directory, the sample's remote packages
# included, at the versions its Package.resolved pins. SwiftPM also re-creates its ~/.swiftpm link
# and records each remote package's fingerprint under ~/Library/org.swift.swiftpm/security, as any
# package build does. When GITHUB_STEP_SUMMARY is set, the test counts are added to it. Portable:
# bash 3.2 or later.

set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
simulator=""
derived_data=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --simulator) simulator="${2:?--simulator needs a UDID}"; shift 2 ;;
        --derived-data) derived_data="${2:?--derived-data needs a directory}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
: "${simulator:?--simulator is required}" "${derived_data:?--derived-data is required}"

if [[ ! -d "$root/swift/LocalCore/SupabaseOfflineCore.xcframework" ]]; then
    echo "swift/LocalCore holds no framework: run scripts/prepare-local-swift-package.sh first." >&2
    exit 1
fi

log="$derived_data/ios-sample-tests.log"
mkdir -p "$derived_data"
status=0
(
    cd "$root/samples/ios"
    SUPABASE_OFFLINE_LOCAL_CORE=1 xcodebuild test \
        -project OfflineTasks.xcodeproj \
        -scheme OfflineTasks \
        -destination "platform=iOS Simulator,id=$simulator" \
        -derivedDataPath "$derived_data" \
        -clonedSourcePackagesDirPath "$derived_data/packages" \
        -packageCachePath "$derived_data/package-cache" \
        -onlyUsePackageVersionsFromResolvedFile \
        -parallel-testing-enabled NO \
        OFFLINE_TASKS_WARNINGS_AS_ERRORS=YES
) > "$log" 2>&1 || status=$?

# The compiler's diagnostics and the tests' own lines, not the whole build transcript.
grep -E "error:|warning: |✘|✔ Suite|Test run with|Test Case .*(passed|failed)|TEST (EXECUTE )?(SUCCEEDED|FAILED)" "$log" \
    | grep -v "appintentsmetadataprocessor" || true
if [[ "$status" -ne 0 ]]; then
    echo "xcodebuild failed (exit $status); the last lines of its output:" >&2
    tail -n 40 "$log" >&2
    exit "$status"
fi
model_tests="$(grep -E "Test run with [0-9]+ tests" "$log" | tail -n 1 || true)"
if [[ ! "$model_tests" =~ Test\ run\ with\ [1-9][0-9]*\ tests.*passed ]]; then
    echo "No passing run of the model tests was reported." >&2
    exit 1
fi
ui_tests="$(grep -cE "Test Case .*OfflineTasksUITests.* passed" "$log" || true)"
if [[ "$ui_tests" -lt 1 ]]; then
    echo "No passing UI test was reported." >&2
    exit 1
fi
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    echo "iOS sample: ${model_tests#*✔ }; $ui_tests UI test(s) passed" >> "$GITHUB_STEP_SUMMARY"
fi
