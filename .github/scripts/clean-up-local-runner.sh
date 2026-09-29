#!/usr/bin/env bash
#
# Removes everything the iOS job wrote outside its workspace on a self-hosted runner, and lists
# what it removed:
# - the simulator it created, that simulator's logs, and the folders CoreSimulator made for it in
#   the per-user temporary directory;
# - any process still running from the job's directories;
# - the job's own home, Gradle User Home, Kotlin/Native and temporary directories;
# - the JDK setup-java put in this runner's own tool cache.
# It fails if the build did not use the job's own directories, if anything it removes is still
# there, or if a check cannot run. The home-snapshot action then compares the home directory.
#
# Runs under `if: always()`, so it keeps going past a failed step. Paths print relative to the
# runner's temporary directory, its tool cache or ~, never in full. Portable: bash 3.2 or later.

set -uo pipefail

: "${RUNNER_TEMP:?}" "${RUNNER_WORKSPACE:?}" "${HOME:?}"
root="$RUNNER_TEMP/offline-kt-ci"
work_folder="$(dirname "$RUNNER_WORKSPACE")"
tool_cache="${RUNNER_TOOL_CACHE:-}"
failed=0

# Prints a path with its private prefix replaced by a name.
show() {
    local shown="$1" tilde="~"
    shown="${shown/#$root/<job>}"
    shown="${shown/#$RUNNER_TEMP/<runner temp>}"
    [[ -n "$tool_cache" ]] && shown="${shown/#$tool_cache/<tool cache>}"
    shown="${shown/#$HOME/$tilde}"
    printf '%s' "$shown"
}

size_of() {
    du -sh "$1" 2> /dev/null | cut -f1
}

# The process ids running from the job's directories, one per line; fails if pgrep cannot look.
job_processes() {
    local found status=0
    found="$(pgrep -f "$root")" || status=$?
    case "$status" in
        0) printf '%s\n' "$found" ;;
        1) ;;
        *) echo "::error::pgrep could not list processes (exit $status)" >&2; return 1 ;;
    esac
}

# The build must have used the job's own directories, not the user's ~/.gradle and ~/.konan.
if [[ -n "${CI_SIMULATOR_UDID:-}" ]]; then
    if compgen -G "$root/konan/kotlin-native-prebuilt-*" > /dev/null && [[ -d "$root/gradle-home/caches" ]]; then
        echo "The build used the job's own Gradle User Home and Kotlin/Native directory."
    else
        echo "::error::The build did not use the job's own Gradle User Home and Kotlin/Native directory"
        failed=1
    fi
fi

# What the tools wrote in the job's home, where Java's user.home pointed.
if [[ -d "$root/home" ]]; then
    echo "Written under the job's home (Java's user.home):"
    listing="$(cd "$root/home" && find . -mindepth 1 -maxdepth 3 | LC_ALL=C sort)"
    if [[ -n "$listing" ]]; then
        printf '%s\n' "$listing" | sed 's#^\./#  ~job/#'
    else
        echo "  nothing"
    fi
fi

echo "Removed:"

udid="${CI_SIMULATOR_UDID:-}"
udid_pattern='^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$'
if [[ "$udid" =~ $udid_pattern ]]; then
    xcrun simctl shutdown "$udid" > /dev/null 2>&1 || true
    if xcrun simctl delete "$udid"; then
        echo "  simulator $udid"
    fi
    simulator_logs="$HOME/Library/Logs/CoreSimulator/$udid"
    if [[ -d "$simulator_logs" ]]; then
        echo "  $(size_of "$simulator_logs")  $(show "$simulator_logs")"
        rm -rf "${simulator_logs:?}"
    fi
    # CoreSimulator makes these outside the job's environment, so TMPDIR does not reach them.
    user_temp="$(getconf DARWIN_USER_TEMP_DIR)"
    for folder in "${user_temp:?}"com.apple.CoreSimulator.SimDevice."$udid".*; do
        if [[ -e "$folder" ]]; then
            echo "  $(size_of "$folder")  <user temp>/$(basename "$folder")"
            rm -rf "${folder:?}"
        fi
    done
elif [[ -n "$udid" ]]; then
    echo "::error::CI_SIMULATOR_UDID is not a simulator UDID; nothing deleted"
    failed=1
fi

if pids="$(job_processes)"; then
    if [[ -n "$pids" ]]; then
        for pid in $pids; do
            echo "  process $pid"
        done
        # shellcheck disable=SC2086 # one argument per process id
        kill $pids 2> /dev/null || true
        sleep 2
        # shellcheck disable=SC2086
        kill -9 $pids 2> /dev/null || true
    fi
else
    failed=1
fi

case "$root" in
    "$RUNNER_TEMP"/*)
        for directory in "$root"/*; do
            if [[ -e "$directory" ]]; then
                echo "  $(size_of "$directory")  $(show "$directory")"
            fi
        done
        chmod -R u+w "$root" 2> /dev/null
        rm -rf "${root:?}"
        ;;
esac

# setup-java installs into the tool cache; remove only a tool cache that is this runner's own.
case "$tool_cache" in
    "$work_folder"/*)
        for jdk in "$tool_cache"/Java_*; do
            if [[ -e "$jdk" ]]; then
                echo "  $(size_of "$jdk")  $(show "$jdk")"
                rm -rf "${jdk:?}"
            fi
        done
        ;;
    *)
        echo "::warning::The tool cache is not inside this runner's work folder; its JDK is left in place"
        ;;
esac

echo "Left behind outside the workspace:"
left_behind=0
if [[ -e "$root" ]]; then
    echo "  $(show "$root")"
    left_behind=1
fi
if [[ -n "$udid" ]]; then
    if devices="$(xcrun simctl list devices)"; then
        if [[ "$devices" == *"$udid"* ]]; then
            echo "  simulator $udid"
            left_behind=1
        fi
    else
        echo "::error::simctl could not list the simulators"
        failed=1
    fi
    for folder in "$(getconf DARWIN_USER_TEMP_DIR)"com.apple.CoreSimulator.SimDevice."$udid".*; do
        if [[ -e "$folder" ]]; then
            echo "  <user temp>/$(basename "$folder")"
            left_behind=1
        fi
    done
fi
if pids="$(job_processes)"; then
    if [[ -n "$pids" ]]; then
        echo "  processes: $(printf '%s' "$pids" | tr '\n' ' ')"
        left_behind=1
    fi
else
    failed=1
fi
case "$tool_cache" in
    "$work_folder"/*)
        for jdk in "$tool_cache"/Java_*; do
            if [[ -e "$jdk" ]]; then
                echo "  $(show "$jdk")"
                left_behind=1
            fi
        done
        ;;
esac
if [[ "$left_behind" -eq 0 ]]; then
    echo "  nothing"
fi
if [[ "$left_behind" -ne 0 || "$failed" -ne 0 ]]; then
    exit 1
fi
