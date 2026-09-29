#!/usr/bin/env bash
#
# Creates an iPhone simulator for this CI job on the newest available iOS runtime and exports its
# UDID as CI_SIMULATOR_UDID. clean-up-local-runner.sh deletes it.
#
# Portable: bash 3.2 (the Mac's /bin/bash) or later.

set -euo pipefail

: "${GITHUB_ENV:?}" "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
device_type="com.apple.CoreSimulator.SimDeviceType.iPhone-17"
runtime="$(xcrun simctl list runtimes --json | python3 -c '
import json, sys
runtimes = [runtime for runtime in json.load(sys.stdin)["runtimes"]
            if runtime.get("platform") == "iOS" and runtime.get("isAvailable")]
if not runtimes:
    sys.exit("no available iOS simulator runtime")
newest = max(runtimes, key=lambda runtime: tuple(int(part) for part in runtime["version"].split(".")))
print(newest["identifier"])
')"
name="offline-kt-ci-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
udid="$(xcrun simctl create "$name" "$device_type" "$runtime")"
echo "CI_SIMULATOR_UDID=$udid" >> "$GITHUB_ENV"
echo "  created simulator $name ($udid): $device_type on $runtime"
