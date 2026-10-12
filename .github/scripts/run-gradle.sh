#!/usr/bin/env bash
#
# Runs ./gradlew with --stacktrace, then the given arguments.
#
# Portable: bash 3.2 (macOS's /bin/bash) or later.

set -euo pipefail

exec ./gradlew --stacktrace "$@"
