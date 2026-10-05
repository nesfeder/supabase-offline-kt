#!/usr/bin/env bash
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
#
# Makes swift/ buildable from this checkout: builds the framework the Swift package wraps (debug,
# on Apple's SQLite) and copies it into swift/LocalCore (ignored by git), where Package.swift takes
# it when SUPABASE_OFFLINE_LOCAL_CORE=1. For the iOS sample, which depends on swift/ by path, and for
# anyone opening the package itself. Needs JDK 17; the first build downloads its toolchain.
#
# Usage, from anywhere in the repository:
#   scripts/prepare-local-swift-package.sh
# then quit Xcode and open the sample with the variable in Xcode's environment:
#   open -a Xcode --env SUPABASE_OFFLINE_LOCAL_CORE=1 samples/ios/OfflineTasks.xcodeproj
#
# Run it again after a change outside swift/ (the engine or the bridge). Portable: bash 3.2 or later.

set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
built="$root/supabase-offline-swift-bridge/build/XCFrameworks/debug/SupabaseOfflineCore.xcframework"
local_core="$root/swift/LocalCore"

(
    cd "$root"
    ./gradlew :supabase-offline-swift-bridge:assembleSupabaseOfflineCoreDebugXCFramework \
        -Psupabase.offline.iosSqlite=system
)
if [[ ! -d "$built" ]]; then
    echo "The build left no framework at ${built#"$root"/}." >&2
    exit 1
fi
rm -rf "${local_core:?}/SupabaseOfflineCore.xcframework"
mkdir -p "$local_core"
cp -R "$built" "$local_core/"
echo "Copied the framework into ${local_core#"$root"/}."
echo "Build with SUPABASE_OFFLINE_LOCAL_CORE=1, or open Xcode with it:"
echo "  open -a Xcode --env SUPABASE_OFFLINE_LOCAL_CORE=1 samples/ios/OfflineTasks.xcodeproj"
