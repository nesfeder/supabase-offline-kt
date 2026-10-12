#!/usr/bin/env bash
#
# Creates an iPhone simulator for this CI job on the newest available iOS runtime and exports its
# UDID as CI_SIMULATOR_UDID. Nothing deletes it: it goes with the hosted runner's machine.
#
# First it deletes any simulator a killed job left behind: one whose name has this job's prefix and
# a creation time older than the job's timeout, so no job still running can own it. The creation
# time is part of the name. Its logs and CoreSimulator's folders for it go too.
#
# Portable: bash 3.2 (macOS's /bin/bash) or later.

set -euo pipefail

: "${GITHUB_ENV:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}" "${HOME:?}"
prefix="offline-kt-ci-"
stale_after_seconds=$((60 * 60)) # longer than the job's 45-minute timeout
now="$(date +%s)"
device_type="com.apple.CoreSimulator.SimDeviceType.iPhone-17"
udid_pattern='^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$'

devices_json="$(xcrun simctl list devices --json)"
stale="$(printf '%s' "$devices_json" | python3 -c '
import json, sys
prefix, now, limit = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
for devices in json.load(sys.stdin)["devices"].values():
    for device in devices:
        name = device["name"]
        if not name.startswith(prefix):
            continue
        created = name[len(prefix):].split("-", 1)[0]
        if not created.isdigit() or now - int(created) > limit:
            print(device["udid"], now - int(created) if created.isdigit() else "unknown")
' "$prefix" "$now" "$stale_after_seconds")"
if [[ -n "$stale" ]]; then
    echo "Removed simulators left by earlier jobs, with their logs:"
    user_temp="$(getconf DARWIN_USER_TEMP_DIR)"
    while read -r udid age; do
        if [[ ! "$udid" =~ $udid_pattern ]]; then
            echo "::error::simctl reported a device id that is not a simulator UDID; nothing deleted"
            exit 1
        fi
        xcrun simctl shutdown "$udid" > /dev/null 2>&1 || true
        xcrun simctl delete "$udid"
        echo "  $udid (created ${age}s ago)"
        simulator_logs="$HOME/Library/Logs/CoreSimulator/$udid"
        if [[ -d "$simulator_logs" ]]; then
            rm -rf "${simulator_logs:?}"
            echo "    ~/Library/Logs/CoreSimulator/$udid"
        fi
        for folder in "${user_temp:?}"com.apple.CoreSimulator.SimDevice."$udid".*; do
            if [[ -e "$folder" ]]; then
                rm -rf "${folder:?}"
                echo "    <user temp>/$(basename "$folder")"
            fi
        done
    done <<< "$stale"
fi

runtime="$(xcrun simctl list runtimes --json | python3 -c '
import json, sys
runtimes = [runtime for runtime in json.load(sys.stdin)["runtimes"]
            if runtime.get("platform") == "iOS" and runtime.get("isAvailable")]
if not runtimes:
    sys.exit("no available iOS simulator runtime")
newest = max(runtimes, key=lambda runtime: tuple(int(part) for part in runtime["version"].split(".")))
print(newest["identifier"])
')"
name="${prefix}${now}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
udid="$(xcrun simctl create "$name" "$device_type" "$runtime")"
echo "CI_SIMULATOR_UDID=$udid" >> "$GITHUB_ENV"
echo "  created simulator $name ($udid): $device_type on $runtime"
