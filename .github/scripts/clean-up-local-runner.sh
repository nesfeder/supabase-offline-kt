#!/usr/bin/env bash
#
# Removes everything the iOS job wrote outside its workspace on the maintainer's Mac, lists what it
# removed, then proves nothing is left: the simulator it created (and that simulator's logs),
# any process still running from the job's directories, the job's Gradle User Home, Kotlin/Native
# and temporary directories, and the JDK setup-java put in this runner's own tool cache.
#
# Runs under `if: always()`, so it keeps going past a failed step and fails only if something is
# left behind. Portable: bash 3.2 (the Mac's /bin/bash) or later.

set -uo pipefail

: "${RUNNER_TEMP:?}" "${RUNNER_WORKSPACE:?}"
root="$RUNNER_TEMP/offline-kt-ci"
work_folder="$(dirname "$RUNNER_WORKSPACE")"
left_behind=0

size_of() {
    du -sh "$1" 2> /dev/null | cut -f1
}

# The build must have used the job's own directories, not the owner's ~/.gradle and ~/.konan.
if [[ -n "${CI_SIMULATOR_UDID:-}" ]]; then
    used_own=1
    compgen -G "$root/konan/kotlin-native-prebuilt-*" > /dev/null || used_own=0
    [[ -d "$root/gradle-home/caches" ]] || used_own=0
    if [[ "$used_own" -eq 1 ]]; then
        echo "The build used the job's own Gradle User Home and Kotlin/Native directory."
    else
        echo "::error::The build did not use the job's own Gradle User Home and Kotlin/Native directory"
        left_behind=1
    fi
fi

echo "Removed:"

udid="${CI_SIMULATOR_UDID:-}"
if [[ "$udid" =~ ^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$ ]]; then
    xcrun simctl shutdown "$udid" > /dev/null 2>&1 || true
    if xcrun simctl delete "$udid"; then
        echo "  simulator $udid"
    fi
    simulator_logs="$HOME/Library/Logs/CoreSimulator/$udid"
    if [[ -d "$simulator_logs" ]]; then
        echo "  $(size_of "$simulator_logs")  $simulator_logs"
        rm -rf "${simulator_logs:?}"
    fi
elif [[ -n "$udid" ]]; then
    echo "::warning::CI_SIMULATOR_UDID is not a simulator UDID; nothing deleted"
fi

pids="$(pgrep -f "$root" || true)"
if [[ -n "$pids" ]]; then
    for pid in $pids; do
        echo "  process $pid: $(ps -o command= -p "$pid" | cut -c1-120)"
    done
    # shellcheck disable=SC2086 # one argument per process id
    kill $pids 2> /dev/null || true
    sleep 2
    # shellcheck disable=SC2086
    kill -9 $pids 2> /dev/null || true
fi

case "$root" in
    "$RUNNER_TEMP"/*)
        for directory in "$root"/*; do
            [[ -e "$directory" ]] && echo "  $(size_of "$directory")  $directory"
        done
        chmod -R u+w "$root" 2> /dev/null
        rm -rf "${root:?}"
        ;;
esac

# setup-java installs into the tool cache; remove only a tool cache that is this runner's own.
tool_cache="${RUNNER_TOOL_CACHE:-}"
case "$tool_cache" in
    "$work_folder"/*)
        for jdk in "$tool_cache"/Java_*; do
            if [[ -e "$jdk" ]]; then
                echo "  $(size_of "$jdk")  $jdk"
                rm -rf "${jdk:?}"
            fi
        done
        ;;
    *)
        echo "::warning::RUNNER_TOOL_CACHE ($tool_cache) is not inside this runner's work folder; its JDK is left in place"
        ;;
esac

echo "Left behind outside the workspace:"
if [[ -e "$root" ]]; then
    echo "  $root"
    left_behind=1
fi
if [[ -n "$udid" ]] && xcrun simctl list devices | grep -q "$udid"; then
    echo "  simulator $udid"
    left_behind=1
fi
if pgrep -f "$root" > /dev/null; then
    echo "  processes: $(pgrep -f "$root" | tr '\n' ' ')"
    left_behind=1
fi
for jdk in "$tool_cache"/Java_*; do
    case "$jdk" in
        "$work_folder"/*) [[ -e "$jdk" ]] && { echo "  $jdk"; left_behind=1; } ;;
    esac
done
if [[ "$left_behind" -eq 0 ]]; then
    echo "  nothing"
fi
exit "$left_behind"
