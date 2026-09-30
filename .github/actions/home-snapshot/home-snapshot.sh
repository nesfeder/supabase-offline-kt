#!/usr/bin/env bash
#
# Records, or compares, the first two levels of the user's home directory, and the entries directly
# inside a few persistence points: each entry's name, size, modification time, status-change time
# and mode, never a file's contents. On a self-hosted runner the iOS job records before its first
# step and compares after its clean-up, so a change to the home directory while the job ran fails
# the job. README.md beside this script says what it cannot see.
#
# Usage:
#   home-snapshot.sh record  <state dir> <temporary dir> <run>
#       Records $HOME in <state dir>, with <run> (the run's URL) beside it. A record still there is
#       an earlier job's that never reached its comparison: what changed since it is printed as a
#       warning first.
#   home-snapshot.sh compare <state dir> <temporary dir> <run>
#       Compares $HOME with the record <run> made in <state dir>, prints every difference and exits
#       1 if there is one. The record is deleted once compared.
#
# <state dir> must outlive the job. The script's own files go in a folder it makes inside
# <temporary dir> and removes on every exit. Paths print relative to the home directory (~/...).
# Portable: bash 3.2 or later, BSD find and stat.

set -euo pipefail

# Recorded by name only, never listed and never timed: macOS asks the user before any process reads
# inside these folders, and what changes in them is the user's own doing.
UNLISTED=(Desktop Documents Downloads Movies Music Pictures .Trash)

# Neither recorded nor listed. Something other than the job writes each of these while it runs;
# each line says what, and why the job does not write there. Everything else is watched, including
# the rest of Library, the runners' own files, and the Gradle and Kotlin/Native homes.
EXCLUDED=(
    # The runners' busy folders: the runner writes its checkout, tools and temporary files under
    # _work, and its logs under _diag, for this job and for jobs of the machine's other runners.
    # Their control files beside them (.env, .path, .runner, bin, ...) are watched.
    'actions-runner-*/_work'
    'actions-runner-*/_diag'
    # macOS and its services, every minute or every few: user defaults, the keychains, caches and
    # the shared app-group containers. The job's Java tools have a home of their own; its native
    # tools (xcodebuild, simctl) use this one, but were measured writing nothing in these.
    'Library/Preferences'
    'Library/Keychains'
    'Library/Caches'
    'Library/Group Containers'
    'Library/Application Scripts'
    # macOS services that write on their own schedule: Shortcuts, Siri suggestions, Wallet,
    # HomeKit, the energy daemon, on-device intelligence and translation, and Google's updater.
    'Library/Shortcuts'
    'Library/Suggestions'
    'Library/Passes'
    'Library/HomeKit'
    'Library/homeenergyd'
    'Library/DuetExpertCenter'
    'Library/DataDeliveryServices'
    'Library/Translation'
    'Library/Google'
    # Gradle's temporary folder and the Kotlin Gradle plugin's build statistics, written by every
    # other Gradle build as the user. The job's Gradle User Home is its own, so it writes neither.
    '.gradle/.tmp'
    '.gradle/kotlin-profile'
    # The coding assistant's settings and session state, written by every session.
    '.claude*'
    # The file-sync client's state.
    '.dropbox'
    # The GitHub CLI's cache, used by every session.
    '.cache/gh'
    # The Supabase CLI writes under it on every command, in any session: it rewrites
    # telemetry.json and creates traces/, and a new child moves the folder's own modification
    # time, so the folder is excluded whole. A run went red on 2026-09-30 when another session
    # ran the CLI during a job; the job itself never runs it.
    '.supabase'
    # npm writes a debug log here for every command, in any session.
    '.npm/_logs'
    # Interactive shells write these when they exit.
    '.zsh_history'
    '.zsh_sessions'
    # Finder's view settings, for the home folder and for any folder in it the user opens.
    '.DS_Store'
    '*/.DS_Store'
)

# Listed one level further down, so an existing file in them rewritten in place moves its own
# status-change time: a launch agent, a Gradle init script, git's user configuration, and each
# runner's programs, which its service runs. Nothing but a runner's self-update writes them.
# A folder that is a symbolic link (a runner's bin after an update) is skipped: its target is
# listed under its own name.
LISTED_DEEPER=(
    'Library/LaunchAgents'
    '.gradle/init.d'
    '.config/git'
    'actions-runner-*/bin*'
    'actions-runner-*/externals*'
)

# Writes the snapshot of $HOME, sorted: one line per entry,
# "<path>\t<size>\t<mtime>\t<ctime>\t<mode>".
take_snapshot() {
    local home="${HOME:?}" errors="$scratch/find-errors" status=0
    local find_args=(. -mindepth 1 -maxdepth 2)
    local pattern first=1
    find_args+=('(')
    for pattern in "${EXCLUDED[@]}"; do
        if [[ "$first" -eq 0 ]]; then find_args+=(-o); fi
        find_args+=(-path "./$pattern")
        first=0
    done
    find_args+=(')' -prune -o '(')
    first=1
    for pattern in "${UNLISTED[@]}"; do
        if [[ "$first" -eq 0 ]]; then find_args+=(-o); fi
        find_args+=(-path "./$pattern")
        first=0
    done
    local format='%N%t%z%t%m%t%c%t%p'
    find_args+=(')' -prune -exec stat -f '%N%t-%t-%t-%t-' '{}' + -o -exec stat -f "$format" '{}' +)
    (
        cd "$home" || exit 1
        failed=0
        find "${find_args[@]}" || failed=1
        for pattern in "${LISTED_DEEPER[@]}"; do
            # Unquoted, so each pattern expands to the folders it names.
            # shellcheck disable=SC2086
            for folder in ./$pattern; do
                if [[ -d "$folder" && ! -L "$folder" ]]; then
                    find "$folder" -mindepth 1 -maxdepth 1 -exec stat -f "$format" '{}' + || failed=1
                fi
            done
        done
        exit "$failed"
    ) 2> "$errors" | LC_ALL=C sort || status=$?
    # A directory the job's user may not read is recorded as such; any other failure is fatal.
    if [[ -s "$errors" ]]; then
        if grep -v -E '(Permission denied|Operation not permitted)$' "$errors" > /dev/null; then
            echo "home-snapshot: find failed:" >&2
            sed 's/^/  /' "$errors" >&2
            return 1
        fi
        sed -E 's/^find: (.*): (Permission denied|Operation not permitted)$/\1\tunreadable\t-\t-\t-/' \
            "$errors" | LC_ALL=C sort
    elif [[ "$status" -ne 0 ]]; then
        return 1
    fi
}

# Fails unless the snapshot has the entries every home directory has: a checker that saw nothing
# must not report that nothing changed.
check_snapshot() {
    local file="$1"
    if [[ ! -f "$file" ]] || ! grep -q -E '^\./Library	' "$file" \
        || [[ "$(wc -l < "$file")" -lt 10 ]]; then
        echo "home-snapshot: the snapshot is empty or incomplete" >&2
        return 1
    fi
}

# Prints the lines that differ between two snapshots, relative to ~, between stop-commands markers
# so that no file name can be read as a workflow command.
print_differences() {
    local token
    token="$(openssl rand -hex 16)"
    echo "  Each line: the path, its size in bytes, its modification and status-change times (seconds"
    echo "  since 1970) and its mode. The status-change time moves on any write, rename, mode, owner or"
    echo "  extended-attribute change, and cannot be set back."
    echo "::stop-commands::$token"
    diff "$1" "$2" | sed -E -n 's#^([<>]) \./#  \1 ~/#p' \
        | sed -e 's/^  </  before:/' -e 's/^  >/  after: /' || true
    echo "::$token::"
}

# Prints which run made the record, or "an earlier job" when that is unknown or malformed.
recorded_by() {
    local run=""
    if [[ -s "$run_file" ]]; then
        run="$(head -n 1 "$run_file")"
    fi
    if [[ "$run" =~ ^https://[A-Za-z0-9./_-]+$ ]]; then
        printf '%s' "$run"
    else
        printf 'an earlier job'
    fi
}

# How to read a red that names a runner's own files: printed after the differences.
print_runner_note() {
    echo "  A red that names only a runner's directory with its .runner_migrated or"
    echo "  .credentials_migrated, or a runner's new bin.*, externals.* or update.finished, is that"
    echo "  runner refreshing its own settings or updating itself; its _diag log says so. Re-run the"
    echo "  job, and do not add an exclusion for it."
}

# Reports a record an earlier job left: it was killed before its comparison ran.
report_unfinished_job() {
    local now="$1" earlier
    earlier="$(recorded_by)"
    if ! check_snapshot "$record" 2> /dev/null; then
        echo "::warning::A job on this runner ($earlier) left an unreadable home-directory record and never compared it. It is replaced."
    elif diff "$record" "$now" > /dev/null; then
        echo "A job on this runner ($earlier) never compared the home directory with its record; nothing has changed since that record."
    else
        echo "::warning::A job on this runner ($earlier) stopped before comparing the home directory with its record. What changed since that record, by that job or by anything else on this machine, is listed in this step's log. This job goes on with a record of its own."
        echo "Changed since the record of $earlier:"
        print_differences "$record" "$now"
    fi
}

usage() {
    echo "usage: home-snapshot.sh record|compare <state dir> <temporary dir> <run>" >&2
    exit 2
}

mode="${1:-}"
state="${2:-}"
temporary="${3:-}"
run="${4:-}"
case "$mode" in
    record | compare) ;;
    *) usage ;;
esac
if [[ -z "$state" || -z "$temporary" || ! -d "$temporary" || -z "$run" ]]; then
    usage
fi

# mktemp on macOS ignores TMPDIR unless given a path, so every file of this script's own goes in a
# folder made here and removed on every exit, a failed comparison or a signal included.
scratch="$(mktemp -d "$temporary/home-snapshot.XXXXXX")"
remove_scratch() {
    case "$scratch" in
        "$temporary"/home-snapshot.*) rm -rf "${scratch:?}" ;;
    esac
}
trap remove_scratch EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

record="$state/record.tsv"
run_file="$state/run.txt"
now="$scratch/now.tsv"
if [[ -e "$record" && ! -f "$record" ]]; then
    echo "::error::The record's path (offline-kt-ci-home-snapshot/record.tsv, in the runner's work folder) is taken by something that is not a file. Remove it by hand; no job can record until then."
    exit 1
fi

case "$mode" in
    record)
        take_snapshot > "$now"
        check_snapshot "$now"
        if [[ -e "$record" ]]; then
            report_unfinished_job "$now"
        fi
        mkdir -p "$state"
        printf '%s\n' "$run" > "$run_file"
        cp "$now" "$record.partial"
        mv -f "$record.partial" "$record"
        check_snapshot "$record"
        echo "Recorded $(wc -l < "$record" | tr -d ' ') entries in the first two levels of ~ and in its persistence points."
        ;;
    compare)
        if [[ ! -e "$record" ]]; then
            echo "::error::No home-directory record to compare with: the record step did not run."
            exit 1
        fi
        take_snapshot > "$now"
        check_snapshot "$now"
        if [[ "$(recorded_by)" != "$run" ]]; then
            # This job's record step never wrote: the record is an earlier job's, so report it as
            # such, not as this job's change.
            report_unfinished_job "$now"
            rm -f "$record" "$run_file"
            echo "::error::This job made no home-directory record, so its changes cannot be told apart: its record step did not run."
            exit 1
        fi
        check_snapshot "$record"
        if diff "$record" "$now" > /dev/null; then
            rm -f "$record" "$run_file"
            echo "The home directory is unchanged: no difference in $(wc -l < "$now" | tr -d ' ') entries."
        else
            echo "::error::The home directory changed while this job ran, by the job or by anything else running as this user in that time. The changes are listed in this step's log. A path the job does not write: re-run the job, and if the same path comes back, it belongs in home-snapshot.sh's exclusions with its reason."
            echo "Changed since this job's record:"
            print_differences "$record" "$now"
            print_runner_note
            rm -f "$record" "$run_file"
            exit 1
        fi
        ;;
esac
