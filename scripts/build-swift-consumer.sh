#!/usr/bin/env bash
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
#
# Builds swift/MainActorConsumer, code written as a new Xcode 26 app writes it (Swift 6, the main
# actor as its default isolation, approachable concurrency), against the Swift package, with the
# consumer's own warnings errors: a row type, a read, a live read in a `.task`, a write, a Storage
# download, the status sequence and a preview, and a patch written from an actor of the app's own
# and a record handed over from a `@Sendable` callback, must compile as an app writes them.
#
# Usage, from the repository root, once scripts/test-swift-package.sh has put the core framework
# in swift/LocalCore:
#   scripts/build-swift-consumer.sh --simulator <UDID> --derived-data <DIR>
#
# Portable: bash 3.2 or later.

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
    echo "No core framework in swift/LocalCore: run scripts/test-swift-package.sh first." >&2
    exit 1
fi

log="$derived_data/swift-consumer.log"
mkdir -p "$derived_data"
status=0
(
    cd "$root/swift/MainActorConsumer"
    SUPABASE_OFFLINE_LOCAL_CORE=1 xcodebuild build \
        -scheme MainActorConsumer-Package \
        -destination "platform=iOS Simulator,id=$simulator" \
        -derivedDataPath "$derived_data" \
        -clonedSourcePackagesDirPath "$derived_data/packages" \
        -packageCachePath "$derived_data/package-cache"
) > "$log" 2>&1 || status=$?

grep -E "(error|warning): " "$log" | grep -v "appintentsmetadataprocessor" | sort -u || true
if [[ "$status" -ne 0 ]]; then
    echo "The main-actor consumer did not build (exit $status); the last lines of its output:" >&2
    tail -n 40 "$log" >&2
    exit "$status"
fi
if ! grep -q -- "-warnings-as-errors" "$log"; then
    echo "The consumer was built without -warnings-as-errors: its manifest must treat warnings as errors." >&2
    exit 1
fi
echo "The main-actor consumer builds against the package with no warning."
