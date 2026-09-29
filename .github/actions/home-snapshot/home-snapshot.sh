#!/usr/bin/env bash
#
# Records, or compares, the first two levels of the user's home directory: each entry's name,
# size and modification time, never a file's contents. The iOS job records a snapshot before its
# first step on a self-hosted runner and compares after its clean-up, so anything the job left in
# the home directory fails the job.
#
# Usage:
#   home-snapshot.sh record  <file>          write a snapshot of $HOME to <file>
#   home-snapshot.sh compare <file>          compare $HOME with the snapshot in <file>; print every
#                                            difference and exit 1 if there is one
#
# Paths print relative to the home directory (~/...). Portable: bash 3.2 or later, BSD find/stat.

set -euo pipefail

# Recorded by name only, never listed and never timed: macOS asks the user before any process reads
# inside these folders, and what changes in them is the user's own doing.
UNLISTED=(Desktop Documents Downloads Movies Music Pictures .Trash)

# Neither recorded nor listed. Other software on the machine writes each of these all the time, so
# it would differ between any two snapshots. The reason is on each line.
EXCLUDED=(
    'actions-runner-*'  # the self-hosted runners' own directories, this job's included
    'Library/*'         # macOS and every app (Preferences changes every minute); the job's own
                        # writes there, the simulator and its logs, are removed and checked by name
    '.gradle/*'         # other Gradle builds; the job uses its own Gradle User Home, and the
                        # clean-up proves the build used it
    '.konan/*'          # other Kotlin/Native builds; the same proof covers the job's own
    '.claude*'          # the coding assistant's session state, written by every session
    '.dropbox'          # the file-sync client's state
    '.cache/gh'         # the GitHub CLI's cache, used by every session
    '.zsh_history'      # interactive shells write these when they exit
    '.zsh_sessions'
    '.DS_Store'         # Finder's view settings for the home folder
)

# Writes the snapshot of $HOME: one line per entry, "<path>\t<size>\t<mtime>", sorted.
take_snapshot() {
    local home="${HOME:?}" errors status=0
    errors="$(mktemp)"
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
    find_args+=(')' -prune -exec stat -f '%N%t-%t-' '{}' + -o -exec stat -f '%N%t%z%t%m' '{}' +)
    (cd "$home" && find "${find_args[@]}" 2> "$errors") | LC_ALL=C sort || status=$?
    # A directory the job's user may not read is recorded as such; any other failure is fatal.
    if [[ -s "$errors" ]]; then
        if grep -v -E '(Permission denied|Operation not permitted)$' "$errors" > /dev/null; then
            echo "home-snapshot: find failed:" >&2
            sed 's/^/  /' "$errors" >&2
            rm -f "$errors"
            return 1
        fi
        sed -E 's/^find: (.*): (Permission denied|Operation not permitted)$/\1\tunreadable\t-/' "$errors" \
            | LC_ALL=C sort
    elif [[ "$status" -ne 0 ]]; then
        rm -f "$errors"
        return 1
    fi
    rm -f "$errors"
}

# Fails unless the snapshot has the entries every home directory has: a checker that saw nothing
# must not report that nothing changed.
check_snapshot() {
    local file="$1"
    if ! grep -q -E '^\./Library	' "$file" || [[ "$(wc -l < "$file")" -lt 10 ]]; then
        echo "home-snapshot: the snapshot is empty or incomplete" >&2
        return 1
    fi
}

mode="${1:-}"
file="${2:-}"
case "$mode" in
    record)
        take_snapshot > "$file"
        check_snapshot "$file"
        echo "Recorded $(wc -l < "$file" | tr -d ' ') entries in the first two levels of ~."
        ;;
    compare)
        check_snapshot "$file"
        after="$(mktemp)"
        take_snapshot > "$after"
        check_snapshot "$after"
        if diff "$file" "$after" > /dev/null; then
            echo "The first two levels of ~ are unchanged: no difference in $(wc -l < "$after" | tr -d ' ') entries."
            rm -f "$after"
        else
            echo "::error::The job changed the home directory:"
            diff "$file" "$after" | sed -E -n 's#^([<>]) \./#  \1 ~/#p' | sed -e 's/^  </  before:/' -e 's/^  >/  after: /'
            rm -f "$after"
            exit 1
        fi
        ;;
    *)
        echo "usage: home-snapshot.sh record|compare <file>" >&2
        exit 2
        ;;
esac
