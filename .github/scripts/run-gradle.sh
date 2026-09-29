#!/usr/bin/env bash
#
# Runs ./gradlew with the flags this runner needs, then the given arguments.
#
# On the maintainer's Mac (IOS_RUNNER=local) the build must leave nothing running and share
# nothing with the owner's own builds: no Gradle daemon, the Kotlin compiler in-process, no build
# cache or configuration cache, the job's own Kotlin/Native directory, and every simulator test
# on the simulator this job created. The Gradle User Home is already the job's own.
#
# Portable: bash 3.2 (the Mac's /bin/bash) or later.

set -euo pipefail

args=(--stacktrace)
if [[ "${IOS_RUNNER:-}" == local ]]; then
    : "${GRADLE_USER_HOME:?}" "${KONAN_DATA_DIR:?}" "${CI_SIMULATOR_UDID:?}"
    args+=(
        --no-daemon
        --no-build-cache
        --no-configuration-cache
        -Pkotlin.compiler.execution.strategy=in-process
        "-Pkonan.data.dir=$KONAN_DATA_DIR"
        --init-script .github/gradle/use-created-simulator.init.gradle
    )
fi
exec ./gradlew "${args[@]}" "$@"
