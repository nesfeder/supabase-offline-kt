#!/usr/bin/env bash
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
#
# Builds the Swift package's documentation (DocC) with every documentation warning an error, checks
# that it names nothing of the framework underneath (scripts/check-swift-public-api.py --docc),
# merges the two products' archives into one, and transforms that for the documentation site,
# where it is served under /swift/ beside the MkDocs pages.
#
# Usage, from the repository root, once scripts/test-swift-package.sh has put the core framework
# in swift/LocalCore:
#   scripts/build-swift-docs.sh --derived-data <DIR> [--out <DIR>] [--hosting-base-path <PATH>]
#
# --out receives the static site (it must not exist yet); without it the archives are built and
# checked only. The hosting base path is the site's path to it, supabase-offline-kt/swift by
# default. A simulator build needs no simulator here: it builds for any arm64 simulator, the only
# kind the framework has a slice for. Portable: bash 3.2 or later.

set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
derived_data=""
out=""
base_path="supabase-offline-kt/swift"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --derived-data) derived_data="${2:?--derived-data needs a directory}"; shift 2 ;;
        --out) out="${2:?--out needs a directory}"; shift 2 ;;
        --hosting-base-path) base_path="${2:?--hosting-base-path needs a path}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
: "${derived_data:?--derived-data is required}"
if [[ ! -d "$root/swift/LocalCore/SupabaseOfflineCore.xcframework" ]]; then
    echo "No core framework in swift/LocalCore: run scripts/test-swift-package.sh first." >&2
    exit 1
fi
if [[ -n "$out" && -e "$out" ]]; then
    echo "$out exists: the site is written afresh." >&2
    exit 1
fi

log="$derived_data/swift-docs.log"
mkdir -p "$derived_data"
status=0
(
    cd "$root/swift"
    SUPABASE_OFFLINE_LOCAL_CORE=1 xcodebuild docbuild \
        -scheme SupabaseOffline-Package \
        -destination 'generic/platform=iOS Simulator' \
        -derivedDataPath "$derived_data" \
        -clonedSourcePackagesDirPath "$derived_data/packages" \
        -packageCachePath "$derived_data/package-cache" \
        ARCHS=arm64 \
        OTHER_DOCC_FLAGS=--warnings-as-errors
) > "$log" 2>&1 || status=$?

grep -E "(error|warning): " "$log" | grep -v "appintentsmetadataprocessor" | sort -u || true
if [[ "$status" -ne 0 ]]; then
    echo "xcodebuild docbuild failed (exit $status); the last lines of its output:" >&2
    tail -n 40 "$log" >&2
    exit "$status"
fi

products="$derived_data/Build/Products/Debug-iphonesimulator"
archives=("$products/SupabaseOffline.doccarchive" "$products/SupabaseOfflineTesting.doccarchive")
for archive in "${archives[@]}"; do
    if [[ ! -d "$archive" ]]; then
        echo "xcodebuild docbuild wrote no ${archive##*/}." >&2
        exit 1
    fi
done
python3 "$root/scripts/check-swift-public-api.py" --docc "${archives[@]}"

if [[ -n "$out" ]]; then
    merged="$derived_data/SupabaseOffline-merged.doccarchive"
    rm -rf "${merged:?}"
    xcrun docc merge "${archives[@]}" \
        --synthesized-landing-page-name SupabaseOffline \
        --synthesized-landing-page-kind Package \
        --output-path "$merged" >> "$log" 2>&1
    xcrun docc process-archive transform-for-static-hosting "$merged" \
        --output-path "$out" \
        --hosting-base-path "$base_path" >> "$log" 2>&1
    # docc's transform gives every page under documentation/ the hosting base path but leaves the
    # archive's root index.html at the server root (/js/…, /css/…, baseUrl "/"), so it loads nothing.
    sed -i '' -E -e "s#(href|src)=\"/#\1=\"/${base_path}/#g" \
        -e "s#var baseUrl = \"/\"#var baseUrl = \"/${base_path}/\"#" "$out/index.html"
    # Every root-relative href and src now starts with the base path, once, and so does baseUrl.
    unprefixed=$(grep -oE '(href|src)="/[^"]*"' "$out/index.html" | grep -vF "=\"/${base_path}/" || true)
    if [[ -n "$unprefixed" ]] || grep -qF "/${base_path}/${base_path}/" "$out/index.html" \
        || ! grep -qF "var baseUrl = \"/${base_path}/\"" "$out/index.html" \
        || ! grep -qF "src=\"/${base_path}/js/" "$out/index.html"; then
        echo "The root index.html does not load from /${base_path}/: docc's output changed." >&2
        exit 1
    fi
    pages=$(find "$out/documentation" -name index.html | wc -l | tr -d ' ')
    echo "The Swift API reference for /$base_path/: $pages pages in ${out}."
fi
