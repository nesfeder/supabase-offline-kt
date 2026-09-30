#!/usr/bin/env bash
#
# Runs a Gradle build as a separate unprivileged user, on a copy of the checkout, so that code the
# build runs (in the upstream canary, a release nobody here has reviewed) cannot reach anything of
# the runner user's: the job's tokens, the Actions cache token that the setup actions' post steps
# hold, those post steps' programs, the workspace or the step's file commands.
#
#   prepare                           create the user, close the runner user's home to other users,
#                                     copy the checkout without .git, and prove what the user
#                                     cannot reach; any doubt fails the step
#   build <gradle argument>...        run ./gradlew in the copy as that user, with no daemon and an
#                                     environment of seven named variables
#   collect <directory> <test task> <module>...
#                                     stop everything the user started, delete its files in the
#                                     shared temporary directories, restore the home's mode, and
#                                     copy back each module's JUnit XML: regular files only, from
#                                     inside the copy
#
# GitHub-hosted Linux runners only: it needs passwordless sudo and useradd.

set -euo pipefail

readonly BUILD_USER=canary-build
readonly BUILD_HOME="/home/$BUILD_USER"
readonly COPY="$BUILD_HOME/work"
readonly MAX_RESULT_FILES=10000

# The build user's whole environment. Nothing of the runner's reaches it: no token, no GITHUB_*,
# no ACTIONS_*, no RUNNER_*.
build_environment() {
    : "${JAVA_HOME:?}" "${ANDROID_HOME:?}"
    environment=(
        "HOME=$BUILD_HOME" "USER=$BUILD_USER" "LOGNAME=$BUILD_USER" "LANG=C.UTF-8"
        "PATH=$JAVA_HOME/bin:/usr/bin:/bin" "JAVA_HOME=$JAVA_HOME" "ANDROID_HOME=$ANDROID_HOME"
    )
}

as_build_user() {
    sudo -u "$BUILD_USER" -- env -i "${environment[@]}" "$@"
}

# A probe that must fail when run as the build user; if it succeeds, the step fails.
must_not() {
    local description=$1
    shift
    if as_build_user "$@" > /dev/null 2>&1; then
        echo "::error::The build user can $description."
        exit 1
    fi
    echo "  cannot $description"
    echo "- cannot $description" >> "$GITHUB_STEP_SUMMARY"
}

# A probe that must succeed when run as the build user, or the build could not run at all.
must() {
    local description=$1
    shift
    if ! as_build_user "$@" > /dev/null 2>&1; then
        echo "::error::The build user cannot $description."
        exit 1
    fi
    echo "  can $description"
}

prepare() {
    : "${GITHUB_WORKSPACE:?}" "${RUNNER_TEMP:?}" "${RUNNER_WORKSPACE:?}" "${GITHUB_ENV:?}"
    : "${GITHUB_STEP_SUMMARY:?}" "${HOME:?}"
    build_environment
    cd /

    if id "$BUILD_USER" > /dev/null 2>&1; then
        echo "::error::A user named $BUILD_USER already exists."
        exit 1
    fi
    sudo useradd --create-home --user-group --shell /usr/sbin/nologin "$BUILD_USER"
    # Readable, so the runner user can copy the results back; the user owns it all the same.
    sudo chmod 0755 "$BUILD_HOME"

    # The runner user's home holds the workspace, the runner, the actions and their post steps,
    # and the step files; other users lose all access to it until collect restores the mode.
    stat -c '%a' "$HOME" > "$RUNNER_TEMP/runner-home-mode"
    sudo chmod o-rwx "$HOME"

    sudo -u "$BUILD_USER" -- mkdir -m 0755 "$COPY"
    tar -C "$GITHUB_WORKSPACE" --exclude=.git -cf - . | sudo -u "$BUILD_USER" -- tar -xf - -C "$COPY"
    local git_metadata
    git_metadata=$(find "$COPY" \( -name .git -o -name .git-credentials \) -print -quit)
    if [[ ! -d "$COPY/.github" || -n "$git_metadata" ]]; then
        echo "::error::The copy of the checkout is incomplete or holds git metadata."
        exit 1
    fi

    echo "What the build user can reach (all as $BUILD_USER):"
    echo "### The build user, $BUILD_USER" >> "$GITHUB_STEP_SUMMARY"

    local expected actual
    expected=$(printf '%s\n' "${environment[@]%%=*}" | sort | tr '\n' ' ')
    actual=$(as_build_user /usr/bin/env | cut -d= -f1 | sort | tr '\n' ' ')
    if [[ "$actual" != "$expected" ]]; then
        echo "::error::The build user's environment holds other variables than the allowed ones."
        exit 1
    fi
    echo "  environment: $actual"
    echo "- environment: only $actual" >> "$GITHUB_STEP_SUMMARY"
    if [[ "$(id -Gn "$BUILD_USER")" != "$BUILD_USER" ]]; then
        echo "::error::The build user belongs to groups other than its own."
        exit 1
    fi
    echo "  groups: $BUILD_USER only"
    echo "- groups: $BUILD_USER only" >> "$GITHUB_STEP_SUMMARY"

    must_not "use sudo" sudo -n true
    must_not "enter the runner user's home" test -x "$HOME"
    must_not "write the workspace" test -w "$GITHUB_WORKSPACE"
    must_not "write the job's temporary directory" test -w "$RUNNER_TEMP"
    must_not "write the step's file commands" test -w "$GITHUB_ENV"
    must_not "write the actions and their post steps" test -w "$(dirname "$RUNNER_WORKSPACE")/_actions"
    must_not "read this step's environment" cat "/proc/$$/environ"
    local step_shell worker runner_root file
    step_shell=$PPID
    worker=$(ps -o ppid= -p "$step_shell" | tr -d ' ')
    must_not "read the step shell's environment" cat "/proc/$step_shell/environ"
    must_not "read the runner worker's environment" cat "/proc/$worker/environ"
    runner_root=$(dirname "$(dirname "$(readlink -f "/proc/$worker/exe")")")
    for file in .credentials .credentials_rsaparams .runner; do
        if [[ -e "$runner_root/$file" ]]; then
            must_not "read the runner's $file" cat "$runner_root/$file"
        fi
    done
    if [[ -e /var/run/docker.sock ]]; then
        must_not "use the Docker socket" test -w /var/run/docker.sock
    fi
    must "run Java" "$JAVA_HOME/bin/java" -version
    must "read the Android SDK" test -r "$ANDROID_HOME"
    must "write its copy of the checkout" test -w "$COPY"
}

build() {
    build_environment
    cd "$COPY"
    as_build_user bash .github/scripts/run-gradle.sh --no-daemon \
        -Pkotlin.compiler.execution.strategy=in-process "$@"
}

collect() {
    local destination=$1 task=$2
    shift 2
    : "${RUNNER_TEMP:?}" "${HOME:?}"
    cd /
    mkdir -p "$destination"
    if ! id "$BUILD_USER" > /dev/null 2>&1; then
        echo "No build user: nothing to collect."
        return 0
    fi

    # Everything the user started stops first, so nothing can change what is copied back.
    sudo pkill -KILL -u "$BUILD_USER" || true
    local attempt
    for attempt in $(seq 1 20); do
        if ! pgrep -u "$BUILD_USER" > /dev/null; then
            break
        fi
        sleep 0.5
    done
    if pgrep -u "$BUILD_USER" > /dev/null; then
        echo "::error::A process of the build user is still running."
        exit 1
    fi
    echo "Stopped every process of $BUILD_USER (checked $attempt times)."

    # Its files in the directories every user shares, which a later step could otherwise read.
    local shared=(/tmp /var/tmp /dev/shm)
    sudo find "${shared[@]}" -xdev -user "$BUILD_USER" -delete 2> /dev/null || true
    if [[ -n "$(sudo find "${shared[@]}" -xdev -user "$BUILD_USER" -print -quit 2> /dev/null)" ]]; then
        echo "::error::A file of the build user is left in a shared temporary directory."
        exit 1
    fi

    if [[ -f "$RUNNER_TEMP/runner-home-mode" ]]; then
        sudo chmod "$(cat "$RUNNER_TEMP/runner-home-mode")" "$HOME"
    fi

    # Only regular files named as Gradle names JUnit results, inside the copy, and bounded in size
    # and number. Whatever else the build left is never read.
    local module results real file copied
    for module in "$@"; do
        mkdir -p "$destination/$module"
        results="$COPY/$module/build/test-results/$task"
        real=$(realpath -e "$results" 2> /dev/null || true)
        if [[ "$real" != "$COPY/"* ]]; then
            echo "$module: no test results to copy"
            continue
        fi
        copied=0
        while IFS= read -r -d '' file; do
            if ((copied >= MAX_RESULT_FILES)); then
                echo "::warning::$module: more than $MAX_RESULT_FILES result files; the rest are left"
                break
            fi
            if cp -- "$file" "$destination/$module/"; then
                copied=$((copied + 1))
            fi
        done < <(find "$real" -maxdepth 1 -type f -name 'TEST-*.xml' -size -20M -print0 2> /dev/null)
        echo "$module: copied $copied result files"
    done
}

case "${1:-}" in
    prepare) prepare ;;
    build) shift && build "$@" ;;
    collect) shift && collect "$@" ;;
    *)
        echo "usage: $0 prepare | build <gradle argument>..." >&2
        echo "       $0 collect <directory> <test task> <module>..." >&2
        exit 2
        ;;
esac
