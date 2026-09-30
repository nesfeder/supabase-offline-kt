#!/usr/bin/env bash
#
# Downloads a file over HTTPS and checks it against a SHA-256 digest.
#
# A download that fails (a network error, a timeout, an HTTP error such as 429 or 503) is tried
# three more times, after 10, 30 and 60 seconds. Each attempt is given 60 seconds, so one file
# takes at most 340 seconds to give up; the caller's timeout must allow that for each file. A file
# that arrives but fails its digest is deleted and fails at once, with no retry: it is not a
# transient failure.
#
# Usage: download-verified.sh <url> <sha256> <file>
#
# Portable: bash 3.2 or later; sha256sum, or shasum where it is missing.

set -euo pipefail

url="${1:?usage: download-verified.sh <url> <sha256> <file>}"
expected="${2:?usage: download-verified.sh <url> <sha256> <file>}"
file="${3:?usage: download-verified.sh <url> <sha256> <file>}"
delays=(10 30 60)

attempt=1
until curl --proto '=https' --tlsv1.2 -fsSL --connect-timeout 30 --max-time 60 -o "$file" "$url"; do
    rm -f "$file"
    if [[ "$attempt" -gt "${#delays[@]}" ]]; then
        echo "::error::Could not download $url after $attempt attempts"
        exit 1
    fi
    delay="${delays[$((attempt - 1))]}"
    echo "::warning::Download attempt $attempt of $url failed; trying again in ${delay}s"
    sleep "$delay"
    attempt=$((attempt + 1))
done

if command -v sha256sum > /dev/null; then
    actual="$(sha256sum "$file" | cut -d ' ' -f 1)"
else
    actual="$(shasum -a 256 "$file" | cut -d ' ' -f 1)"
fi
if [[ "$actual" != "$expected" ]]; then
    rm -f "$file"
    echo "::error::$url does not match its pinned SHA-256: expected $expected, got $actual. Not retried and not cached; find out why before running again."
    exit 1
fi
echo "Downloaded $(basename "$file") from $url (attempt $attempt), SHA-256 $actual."
