#!/usr/bin/env python3
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""The Swift package's half of a release: its framework, built once, and the package, published
to its own repository. The release workflows run these commands; RELEASING.md describes the
process around them.

The package's repository, nesfeder/supabase-offline-swift, holds one commit per release, tagged
X.Y.Z, whose tree is exactly the published tree: Package.swift (rendered from swift/Package.swift
for the release), Sources/, README.md (from swift/README.md) and LICENSE. The framework is an
asset of this repository's GitHub Release X.Y.Z, SupabaseOfflineCore.xcframework.zip, which the
rendered manifest names by URL with its checksum.

Commands (from the repository root):

  build --out DIR
      macOS only. Builds the release XCFramework with Gradle on Apple's SQLite, copies it to DIR,
      puts the privacy manifest (swift/Sources/SupabaseOffline/PrivacyInfo.xcprivacy) in each
      slice's framework, and runs check-required-reasons on the result.
  check-required-reasons --framework PATH [--update]
      macOS only. The required-reason APIs a framework, or every slice of an XCFramework,
      references: C functions and Objective-C classes and constants from `nm -u`, and Objective-C
      selectors from its strings. Fails unless they equal the record
      (supabase-offline-swift-bridge/privacy/required-reason-apis.txt) and the privacy manifest
      declares exactly the categories the record needs, each with a reason Apple lists for it; an
      XCFramework's slices must each carry that manifest. --update rewrites the record.
  zip --framework XCFRAMEWORK --out ZIP
      macOS only. ditto's zip with the XCFramework at its root and no resource forks or extended
      attributes, as SwiftPM takes it; prints its checksum.
  checksum ZIP
      The SHA-256 of the file, which is what `swift package compute-checksum` reports.
  publish --zip ZIP --version X.Y.Z --sha SHA --out DIR (--remote URL | --dry-run)
      Stages the published tree in DIR (which must not exist) from the commit SHA's swift/ and
      LICENSE, read with `git archive` as data (nothing of that commit runs), with the manifest
      rendered for X.Y.Z and the checksum of ZIP, computed here; checks it (check-tree); then,
      unless --dry-run, pushes it to the remote: a commit on top of its main, tagged X.Y.Z, both
      in one atomic push that never forces. It does nothing when the tag already holds exactly
      this tree, so a resume can run it again, and refuses a tag that holds anything else. main
      moves only for the highest version. The remote's access comes from the environment
      (GIT_SSH_COMMAND), never from this script.
  read-manifest --tree DIR --zip ZIP
      macOS only. Has SwiftPM read the staged tree's manifest (`swift package dump-package`), which
      the Linux jobs that publish it cannot: it must hold the binary target at the archive's URL
      with the zip's checksum, the two library targets and nothing else.
  check-tree --tree DIR --zip ZIP --version X.Y.Z
      The check publish runs: the tree holds Package.swift, README.md, LICENSE and Sources/ and
      nothing else, Sources/ only Swift files and privacy manifests, no symbolic link and no hidden
      file; the manifest names X.Y.Z, the archive's URL and the zip's checksum (never the zero
      placeholder), with no local-framework switch and no test target; and the words Kotlin, KMP,
      Konan and kfun, in any case, appear nowhere but in the code of Sources/*.swift. There, the
      package's internal code calls the framework, whose runtime types carry the word (such as
      KotlinLong), and the redaction patterns that keep it out of error text spell it. No comment
      of any kind, no string literal, no file or folder name and no other file may hold one: those
      are what a developer reads in Quick Help, sees in an error, or browses. Public declarations
      are checked separately: scripts/check-swift-public-api.py refuses the words in any public
      symbol.
  draft-release --zip ZIP --version X.Y.Z --sha SHA
      Creates the draft GitHub Release X.Y.Z at SHA with the changelog's section and GitHub's
      generated notes, holding ZIP as its asset. A draft is not public and creates no tag. It does
      nothing when that draft holds exactly this zip already, and refuses a published release, a
      draft from another commit, or a draft holding another build: a version's framework is
      built once.
  download-framework --version X.Y.Z --sha SHA --out DIR
      Downloads the framework of the GitHub Release X.Y.Z (draft or published), which must have
      been staged from SHA, into DIR. A resume takes the framework from there, never from a build.
  known-hosts
      Prints GitHub's SSH host keys, pinned here, for a known_hosts file.
  self-test
      Runs every command it can against throwaway repositories, with a stand-in for gh; the
      commands that need macOS's tools only on macOS.

Standard library only; runs on the system python3 of macOS (3.9) and on CI runners.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import json
import os
import plistlib
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path


def load_release_module():
    """scripts/release.py, whose git and gh helpers, bot identity and fixtures this shares."""
    spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("release.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


release = load_release_module()
ReleaseError = release.ReleaseError
git = release.git

FRAMEWORK = "SupabaseOfflineCore"
XCFRAMEWORK = f"{FRAMEWORK}.xcframework"
ASSET = release.SWIFT_FRAMEWORK_ASSET
GRADLE_TASK = ":supabase-offline-swift-bridge:assembleSupabaseOfflineCoreReleaseXCFramework"
SYSTEM_SQLITE = "-Psupabase.offline.iosSqlite=system"
BUILT_XCFRAMEWORK = f"supabase-offline-swift-bridge/build/XCFrameworks/release/{XCFRAMEWORK}"
PRIVACY_MANIFEST = "swift/Sources/SupabaseOffline/PrivacyInfo.xcprivacy"
PRIVACY_FILE = "PrivacyInfo.xcprivacy"
REQUIRED_REASON_RECORD = "supabase-offline-swift-bridge/privacy/required-reason-apis.txt"
SOURCE_PACKAGE = "swift"
MANIFEST = "Package.swift"
DEVELOPMENT_MARKER = "// MARK: Development only"
ZERO_CHECKSUM = "0" * 64
ASSET_URL = ("https://github.com/nesfeder/supabase-offline-kt/releases/download/\\(version)/"
             f"{ASSET}")
PUBLISHED_ENTRIES = {MANIFEST, "README.md", "LICENSE", "Sources"}
FORBIDDEN_WORDS = re.compile(r"kotlin|kmp|konan|kfun", re.IGNORECASE)
# Left out of the published manifest: the switch to a local framework, and the tests.
DEVELOPMENT_ONLY = ("Context.environment", "LocalCore", "SUPABASE_OFFLINE_LOCAL_CORE", ".testTarget(",
                    DEVELOPMENT_MARKER)
COMMIT_MESSAGE = "Release {version}"

# GitHub's SSH host keys, as https://api.github.com/meta lists them, and their SHA-256 fingerprints
# as GitHub's documentation publishes them; the self-test checks the one against the other.
GITHUB_HOST_KEYS = (
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
    "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKg"
    "wFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=",
    "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwn"
    "GLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywIN"
    "IR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4z"
    "MjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+"
    "bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56"
    "i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=",
)
GITHUB_HOST_KEY_FINGERPRINTS = {
    "+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
    "p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
    "uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s",
}

# Apple's required-reason APIs ("Describing use of required reason API"), by category, as they
# appear in a binary: C functions and Objective-C classes and constants by symbol, and the
# Objective-C methods Apple names by selector. Each category's reasons are the ones Apple lists.
REQUIRED_REASON_APIS = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": {
        "symbols": {"stat", "fstat", "fstatat", "lstat", "getattrlist", "getattrlistbulk", "fgetattrlist",
                    "getattrlistat", "NSFileCreationDate", "NSFileModificationDate",
                    "NSURLContentModificationDateKey", "NSURLCreationDateKey"},
        "selectors": {"creationDate", "modificationDate", "fileModificationDate", "fileCreationDate"},
        "reasons": {"DDA9.1", "C617.1", "3B52.1", "0A2A.1"},
    },
    "NSPrivacyAccessedAPICategorySystemBootTime": {
        "symbols": {"mach_absolute_time"},
        "selectors": {"systemUptime"},
        "reasons": {"35F9.1", "8FFB.1", "3D61.1"},
    },
    "NSPrivacyAccessedAPICategoryDiskSpace": {
        "symbols": {"statfs", "statvfs", "fstatfs", "fstatvfs", "getattrlist", "fgetattrlist", "getattrlistat",
                    "NSURLVolumeAvailableCapacityKey", "NSURLVolumeAvailableCapacityForImportantUsageKey",
                    "NSURLVolumeAvailableCapacityForOpportunisticUsageKey", "NSURLVolumeTotalCapacityKey",
                    "NSFileSystemFreeSize", "NSFileSystemSize"},
        "selectors": set(),
        "reasons": {"85F4.1", "E174.1", "7D9E.1", "B728.1"},
    },
    "NSPrivacyAccessedAPICategoryActiveKeyboards": {
        "symbols": set(),
        "selectors": {"activeInputModes"},
        "reasons": {"3EC4.1", "54BD.1"},
    },
    "NSPrivacyAccessedAPICategoryUserDefaults": {
        "symbols": {"NSUserDefaults"},
        "selectors": set(),
        "reasons": {"CA92.1", "1C8F.1", "C56D.1", "AC6B.1"},
    },
}


def run(arguments: list[str], cwd: Path | None = None) -> str:
    """Runs a command and returns its standard output; fails with its error output."""
    completed = subprocess.run(arguments, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               universal_newlines=True)
    if completed.returncode != 0:
        raise ReleaseError(f"{' '.join(arguments[:3])} failed: {completed.stderr.strip()[-2000:]}")
    return completed.stdout


def checksum(path: Path) -> str:
    """The file's SHA-256 in hex, as `swift package compute-checksum` reports it."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


# ─────────────────────────────────────────────────────────────────────────────
# The framework: build, privacy manifests, zip
# ─────────────────────────────────────────────────────────────────────────────


def slice_binaries(framework: Path) -> list[Path]:
    """The static library of a framework, or of each slice of an XCFramework."""
    if framework.suffix == ".xcframework":
        binaries = sorted(framework.glob(f"*/{FRAMEWORK}.framework/{FRAMEWORK}"))
    else:
        binaries = [framework / FRAMEWORK]
    if not binaries or not all(binary.is_file() for binary in binaries):
        raise ReleaseError(f"no {FRAMEWORK} binary in {framework}")
    return binaries


def referenced_apis(undefined: list[str], strings: set[str]) -> set[tuple[str, str]]:
    """The (category, API) pairs a binary references: from its undefined symbols (`nm -u`, with
    Mach-O's leading underscore) and the NUL-terminated strings in it, where selectors live."""
    names = set()
    for symbol in undefined:
        symbol = symbol.strip()
        if symbol.startswith("_OBJC_CLASS_$_"):
            names.add(symbol[len("_OBJC_CLASS_$_"):])
        elif symbol.startswith("_"):
            # `_stat$INODE64` and the like are the same function under another ABI
            names.add(symbol[1:].split("$", 1)[0])
    found = set()
    for category, apis in REQUIRED_REASON_APIS.items():
        found |= {(category, name) for name in apis["symbols"] & names}
        found |= {(category, name) for name in apis["selectors"] & strings}
    return found


def binary_apis(binary: Path) -> set[tuple[str, str]]:
    """The required-reason APIs one static library references."""
    undefined = run(["xcrun", "nm", "-u", str(binary)]).splitlines()
    strings = {part.decode("latin-1") for part in binary.read_bytes().split(b"\0") if 2 < len(part) < 120}
    return referenced_apis([line for line in undefined if line and not line.endswith(":")], strings)


def read_record(text: str) -> set[tuple[str, str]]:
    """The record's (category, API) lines."""
    pairs = set()
    for line in text.splitlines():
        if line.strip() and not line.startswith("#"):
            category, api = line.split()
            pairs.add((category, api))
    return pairs


def record_text(pairs: set[tuple[str, str]]) -> str:
    """The record as written: its explanation, then one sorted `category API` line per pair."""
    header = ("# The required-reason APIs (Apple's \"Describing use of required reason API\") the release\n"
              "# framework SupabaseOfflineCore references, one `category API` per line. The privacy\n"
              "# manifest, swift/Sources/SupabaseOffline/PrivacyInfo.xcprivacy, declares a reason for each\n"
              "# category here. Written by `python3 scripts/swift-release.py check-required-reasons\n"
              "# --framework <framework> --update`; CI fails when the framework's list differs.\n")
    return header + "".join(f"{category} {api}\n" for category, api in sorted(pairs))


def manifest_problems(manifest: bytes, needed: set[str]) -> list[str]:
    """What is wrong with a privacy manifest that must declare exactly the categories needed."""
    try:
        document = plistlib.loads(manifest)
    except Exception as error:  # plistlib raises several types for a malformed file
        return [f"the privacy manifest is not a property list: {error}"]
    problems = []
    if document.get("NSPrivacyTracking") is not False:
        problems.append("the privacy manifest must say NSPrivacyTracking is false")
    declared = {}
    for entry in document.get("NSPrivacyAccessedAPITypes") or []:
        declared[entry.get("NSPrivacyAccessedAPIType")] = set(entry.get("NSPrivacyAccessedAPITypeReasons") or [])
    for category in sorted(needed - set(declared)):
        problems.append(f"the privacy manifest declares no reason for {category}, which the framework uses")
    for category in sorted(set(declared) - needed):
        problems.append(f"the privacy manifest declares {category}, which the framework does not use")
    for category in sorted(set(declared) & needed):
        reasons = declared[category]
        allowed = REQUIRED_REASON_APIS[category]["reasons"]
        if not reasons or not reasons <= allowed:
            problems.append(f"{category} needs one or more of Apple's reasons {sorted(allowed)},"
                            f" not {sorted(reasons)}")
    return problems


def required_reason_problems(found: set[tuple[str, str]], record: set[tuple[str, str]],
                             manifest: bytes) -> list[str]:
    """Differences between what a framework references and the record, and the manifest's own."""
    problems = [f"the framework references {category} {api}, which the record does not list"
                for category, api in sorted(found - record)]
    problems += [f"the record lists {category} {api}, which the framework no longer references"
                 for category, api in sorted(record - found)]
    return problems + manifest_problems(manifest, {category for category, _ in record})


def check_required_reasons(root: Path, framework: Path, update: bool = False) -> str:
    """Checks (or records) the framework's required-reason APIs against the privacy manifest."""
    found = set()
    for binary in slice_binaries(framework):
        found |= binary_apis(binary)
    record_path = root / REQUIRED_REASON_RECORD
    manifest = (root / PRIVACY_MANIFEST).read_bytes()
    if update:
        record_path.parent.mkdir(parents=True, exist_ok=True)
        record_path.write_text(record_text(found), encoding="utf-8")
    problems = required_reason_problems(found, read_record(record_path.read_text(encoding="utf-8")), manifest)
    if framework.suffix == ".xcframework":
        for binary in slice_binaries(framework):
            embedded = binary.parent / PRIVACY_FILE
            if not embedded.is_file() or embedded.read_bytes() != manifest:
                problems.append(f"{binary.parent.relative_to(framework)} does not carry {PRIVACY_MANIFEST}")
    if problems:
        raise ReleaseError("required-reason APIs:\n  " + "\n  ".join(problems)
                           + f"\nAfter an intended change, run with --update, declare the reasons in"
                           f" {PRIVACY_MANIFEST} and commit both.")
    listing = ", ".join(f"{api} ({category.replace('NSPrivacyAccessedAPICategory', '')})"
                        for category, api in sorted(found)) or "none"
    return f"Required-reason APIs: {listing}; the record and the privacy manifest agree."


def build_framework(root: Path, out: Path) -> Path:
    """Builds the release XCFramework, copies it to out with a privacy manifest in each slice, and
    checks its required-reason APIs; returns the copy."""
    if subprocess.run(["./gradlew", GRADLE_TASK, SYSTEM_SQLITE, "--stacktrace"], cwd=root).returncode:
        raise ReleaseError(f"Gradle could not build {XCFRAMEWORK}; its output is above")
    target = out / XCFRAMEWORK
    if target.exists():
        shutil.rmtree(target)
    out.mkdir(parents=True, exist_ok=True)
    shutil.copytree(root / BUILT_XCFRAMEWORK, target, symlinks=True)
    for binary in slice_binaries(target):
        shutil.copyfile(root / PRIVACY_MANIFEST, binary.parent / PRIVACY_FILE)
    print(check_required_reasons(root, target))
    return target


def zip_problems(archive: Path) -> list[str]:
    """What keeps SwiftPM from taking the zip as a binary target's archive."""
    with zipfile.ZipFile(archive) as opened:
        names = opened.namelist()
    problems = [f"{name} is outside {XCFRAMEWORK}/" for name in names if not name.startswith(f"{XCFRAMEWORK}/")]
    problems += [f"{name} is a resource fork or metadata entry" for name in names
                 if "__MACOSX" in name or "/._" in name or name.startswith("._")]
    if f"{XCFRAMEWORK}/Info.plist" not in names:
        problems.append(f"no {XCFRAMEWORK}/Info.plist")
    return problems


def zip_framework(framework: Path, out: Path) -> str:
    """Zips the XCFramework as SwiftPM takes it; returns the checksum."""
    if framework.name != XCFRAMEWORK:
        raise ReleaseError(f"{framework} is not {XCFRAMEWORK}")
    if out.exists():
        raise ReleaseError(f"{out} exists: the framework is zipped once")
    run(["ditto", "-c", "-k", "--keepParent", "--norsrc", "--noextattr", "--noqtn", "--noacl",
         str(framework), str(out)])
    problems = zip_problems(out)
    if problems:
        raise ReleaseError(f"{out.name}:\n  " + "\n  ".join(problems))
    return checksum(out)


# ─────────────────────────────────────────────────────────────────────────────
# The published tree
# ─────────────────────────────────────────────────────────────────────────────


def render_manifest(source: str, version: str, archive_checksum: str) -> str:
    """The published manifest: the source manifest up to its development-only part, with the
    version and the checksum set. Each substitution must happen exactly once."""
    if not release.RELEASE_VERSION.match(version):
        raise ReleaseError(f"{version!r} is not a release version X.Y.Z")
    if not re.fullmatch(r"[0-9a-f]{64}", archive_checksum) or archive_checksum == ZERO_CHECKSUM:
        raise ReleaseError(f"{archive_checksum!r} is not the checksum of an archive")
    lines = source.splitlines()
    markers = [index for index, line in enumerate(lines) if line.strip() == DEVELOPMENT_MARKER]
    if len(markers) != 1:
        raise ReleaseError(f"swift/{MANIFEST} has {len(markers)} `{DEVELOPMENT_MARKER}` lines, not one")
    published = "\n".join(lines[:markers[0]]).rstrip() + "\n"
    for name, value in (("version", version), ("checksum", archive_checksum)):
        published, count = re.subn(rf'^let {name} = "[^"\n]*"$', f'let {name} = "{value}"', published,
                                   flags=re.MULTILINE)
        if count != 1:
            raise ReleaseError(f"swift/{MANIFEST} has {count} `let {name} = \"…\"` lines, not one")
    return published


def committed_tree(root: Path, sha: str, prefix: str) -> dict[str, bytes]:
    """The files under prefix at the commit, read with `git archive`: path to bytes."""
    archived = subprocess.run(["git", "-C", str(root), "archive", "--format=tar", sha, prefix],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if archived.returncode != 0:
        raise ReleaseError(f"git archive {sha} {prefix} failed: {archived.stderr.decode().strip()}")
    files = {}
    with tempfile.TemporaryFile() as handle:
        handle.write(archived.stdout)
        handle.seek(0)
        with tarfile.open(fileobj=handle) as tar:
            for member in tar.getmembers():
                if member.issym() or member.islnk():
                    raise ReleaseError(f"{member.name} at {sha} is a link: the package holds files only")
                if member.isfile():
                    files[member.name] = tar.extractfile(member).read()
    return files


def stage_tree(root: Path, sha: str, version: str, archive: Path, out: Path) -> str:
    """Writes the published tree to out and checks it; returns the checksum written."""
    if out.exists() and any(out.iterdir()):
        raise ReleaseError(f"{out} is not empty: the tree is staged afresh")
    archive_checksum = checksum(archive)
    source = committed_tree(root, sha, SOURCE_PACKAGE)
    licence = release.committed_file(root, sha, "LICENSE")
    if f"{SOURCE_PACKAGE}/{MANIFEST}" not in source or f"{SOURCE_PACKAGE}/README.md" not in source or not licence:
        raise ReleaseError(f"{sha} lacks swift/{MANIFEST}, swift/README.md or LICENSE")
    files = {
        MANIFEST: render_manifest(source[f"{SOURCE_PACKAGE}/{MANIFEST}"].decode("utf-8"), version,
                                  archive_checksum).encode("utf-8"),
        "README.md": source[f"{SOURCE_PACKAGE}/README.md"],
        "LICENSE": licence.encode("utf-8"),
    }
    for name, content in source.items():
        if name.startswith(f"{SOURCE_PACKAGE}/Sources/"):
            files[name[len(SOURCE_PACKAGE) + 1:]] = content
    for name, content in files.items():
        (out / name).parent.mkdir(parents=True, exist_ok=True)
        (out / name).write_bytes(content)
        (out / name).chmod(0o644)
    problems = tree_problems(out, version, archive_checksum)
    if problems:
        raise ReleaseError("the staged tree is not publishable:\n  " + "\n  ".join(problems))
    return archive_checksum


def swift_comments_and_strings(text: str) -> tuple[list[str], list[str]]:
    """The comments and the string literals of Swift source, each as its text.

    A small lexer: `//` and nested `/* */` comments; `"`, `\"\"\"` and raw `#"`-delimited strings,
    interpolations included; bare `/…/` and `#/…/#` regex literals, which are code, are skipped
    whole so a quote or a slash inside one is not misread. A bare regex is recognised where an
    expression starts (after `=`, `(`, `,`, `[`, `:`, `{`, `;`, `return` or a line's start) and
    its first character is not a space; any other `/` is a division."""
    comments, strings = [], []
    index, length = 0, len(text)
    expression_start = True
    while index < length:
        char = text[index]
        word = re.match(r"[A-Za-z_][A-Za-z0-9_]*", text[index:])
        if word:
            expression_start = word.group(0) == "return"
            index += len(word.group(0))
            continue
        if text.startswith("//", index):
            end = text.find("\n", index)
            end = length if end < 0 else end
            comments.append(text[index:end])
            index = end
            continue
        if text.startswith("/*", index):
            depth, cursor = 1, index + 2
            while cursor < length and depth:
                if text.startswith("/*", cursor):
                    depth, cursor = depth + 1, cursor + 2
                elif text.startswith("*/", cursor):
                    depth, cursor = depth - 1, cursor + 2
                else:
                    cursor += 1
            comments.append(text[index:cursor])
            index = cursor
            continue
        literal = re.match(r'(#*)("""|"|/)', text[index:])
        if literal:
            hashes, quote = literal.group(1), literal.group(2)
            start = index + len(literal.group(0))
            bare_regex = quote == "/" and not hashes
            if bare_regex and (not expression_start or text[start:start + 1] in ("", " ", "\t", "\n")):
                literal = None  # a division, not a regex literal
        if literal:
            closing = quote + hashes
            # A raw string's escape carries its hashes; a regex literal's never does.
            escape = "\\" + (hashes if quote != "/" else "")
            single_line = quote == '"' or bare_regex
            cursor = start
            while cursor < length and not text.startswith(closing, cursor):
                if text.startswith(escape, cursor):
                    cursor += len(escape) + 1
                elif single_line and text[cursor] == "\n":
                    break
                else:
                    cursor += 1
            cursor = min(length, cursor + len(closing))
            if quote != "/":
                strings.append(text[index:cursor])
            index = cursor
            expression_start = False
            continue
        if char in "=(,[:{;\n":
            expression_start = True
        elif not char.isspace():
            expression_start = False
        index += 1
    return comments, strings


def tree_problems(tree: Path, version: str, archive_checksum: str) -> list[str]:
    """What keeps a staged tree from being published (check-tree)."""
    problems = []
    entries = {entry.name for entry in tree.iterdir()}
    for name in sorted(entries - PUBLISHED_ENTRIES):
        problems.append(f"{name} is not part of the published package")
    for name in sorted(PUBLISHED_ENTRIES - entries):
        problems.append(f"{name} is missing")
    for path in sorted(tree.rglob("*")):
        relative = path.relative_to(tree).as_posix()
        if path.is_symlink():
            problems.append(f"{relative} is a symbolic link")
        if path.name.startswith("."):
            problems.append(f"{relative} is a hidden file")
        if FORBIDDEN_WORDS.search(relative):
            problems.append(f"the path {relative} names {FORBIDDEN_WORDS.search(relative).group(0)}")
        if not path.is_file() or path.is_symlink():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if relative.startswith("Sources/"):
            if not (path.suffix == ".swift" or path.name == PRIVACY_FILE):
                problems.append(f"{relative} is neither Swift nor a privacy manifest")
            if path.suffix == ".swift":
                comments, strings = swift_comments_and_strings(text)
                for kind, pieces in (("a comment", comments), ("a string literal", strings)):
                    for piece in pieces:
                        match = FORBIDDEN_WORDS.search(piece)
                        if match:
                            problems.append(f"{relative} has {match.group(0)!r} in {kind}: {piece.strip()[:80]}")
                continue
        match = FORBIDDEN_WORDS.search(text)
        if match:
            line = text[:match.start()].count("\n") + 1
            problems.append(f"{relative}:{line} names {match.group(0)!r}")
    manifest_path = tree / MANIFEST
    if manifest_path.is_file():
        manifest = manifest_path.read_text(encoding="utf-8")
        if f'let version = "{version}"' not in manifest:
            problems.append(f"{MANIFEST} does not set version {version}")
        if re.search(rf'let checksum = "{ZERO_CHECKSUM}"', manifest):
            problems.append(f"{MANIFEST} has the zero checksum")
        elif f'let checksum = "{archive_checksum}"' not in manifest:
            problems.append(f"{MANIFEST}'s checksum is not the archive's, {archive_checksum}")
        if ASSET_URL not in manifest:
            problems.append(f"{MANIFEST} does not name the archive's URL {ASSET_URL}")
        for development in DEVELOPMENT_ONLY:
            if development in manifest:
                problems.append(f"{MANIFEST} holds {development!r}, which is for development only")
    return problems


def dumped_manifest_problems(dump: dict, archive_checksum: str) -> list[str]:
    """What is wrong with a published manifest as `swift package dump-package` reads it."""
    targets = {target.get("name"): target for target in dump.get("targets") or []}
    products = sorted(product.get("name") for product in dump.get("products") or [])
    problems = []
    if set(targets) != {FRAMEWORK, "SupabaseOffline", "SupabaseOfflineTesting"}:
        problems.append(f"the targets are {sorted(targets)}")
    if products != ["SupabaseOffline", "SupabaseOfflineTesting"]:
        problems.append(f"the products are {products}")
    core = targets.get(FRAMEWORK) or {}
    if core.get("type") != "binary" or not str(core.get("url", "")).endswith(f"/releases/download/"
                                                                             f"{dump.get('_version')}/{ASSET}"):
        problems.append(f"{FRAMEWORK} is not the binary target at the archive's URL: {core}")
    if core.get("checksum") != archive_checksum:
        problems.append(f"{FRAMEWORK}'s checksum is {core.get('checksum')}, not the archive's {archive_checksum}")
    return problems


def read_manifest(tree: Path, archive: Path) -> str:
    """Has SwiftPM read the staged manifest, and checks what it read."""
    if not (tree / MANIFEST).is_file():
        raise ReleaseError(f"no {MANIFEST} in {tree}")
    with tempfile.TemporaryDirectory() as directory:
        dumped = run(["swift", "package", "dump-package", "--scratch-path", directory], cwd=tree)
    dump = json.loads(dumped)
    version = re.search(r'^let version = "([^"]+)"$', (tree / MANIFEST).read_text(encoding="utf-8"), re.MULTILINE)
    dump["_version"] = version.group(1) if version else ""
    problems = dumped_manifest_problems(dump, checksum(archive))
    if problems:
        raise ReleaseError("SwiftPM reads the staged manifest as:\n  " + "\n  ".join(problems))
    return (f"SwiftPM reads the staged manifest: products {', '.join(product['name'] for product in dump['products'])};"
            f" {FRAMEWORK} from {next(target for target in dump['targets'] if target['name'] == FRAMEWORK)['url']}.")


# ─────────────────────────────────────────────────────────────────────────────
# The package's repository
# ─────────────────────────────────────────────────────────────────────────────


def semantic(version: str) -> tuple[int, ...]:
    """A release version as numbers, for ordering."""
    return tuple(int(part) for part in version.split("."))


def remote_refs(scratch: Path, remote: str) -> dict[str, str]:
    """The remote's branches and tags (peeled), as ref to commit."""
    refs = {}
    for line in git(scratch, "ls-remote", remote).splitlines():
        sha, ref = line.split("\t")
        if ref.endswith("^{}"):
            refs[ref[:-3]] = sha
        else:
            refs.setdefault(ref, sha)
    return refs


def push_package(tree: Path, version: str, remote: str) -> str:
    """Publishes the tree as the commit tagged version on the remote; see `publish`."""
    with tempfile.TemporaryDirectory() as directory:
        scratch = Path(directory) / "publish.git"
        git(Path(directory), "init", "-q", "--bare", str(scratch))
        # The staged folder is the work tree, read through an index of its own; no ignore rule of
        # this machine may leave a file out, and the tree object must list every staged file.
        environment = dict(os.environ, GIT_DIR=str(scratch), GIT_WORK_TREE=str(tree),
                           GIT_INDEX_FILE=str(Path(directory) / "index"))
        for arguments in (["-c", "core.excludesFile=/dev/null", "add", "--all", "."], ["write-tree"]):
            completed = subprocess.run(["git", *arguments], cwd=tree, env=environment, stdout=subprocess.PIPE,
                                       stderr=subprocess.PIPE, universal_newlines=True)
            if completed.returncode != 0:
                raise ReleaseError(f"git {' '.join(arguments[2:] or arguments)} failed: {completed.stderr.strip()}")
        tree_id = completed.stdout.strip()
        listed = sorted(git(scratch, "ls-tree", "-r", "--name-only", tree_id).splitlines())
        staged = sorted(path.relative_to(tree).as_posix() for path in tree.rglob("*") if path.is_file())
        if listed != staged:
            raise ReleaseError(f"the commit's tree would not hold exactly the staged files: {listed} != {staged}")
        refs = remote_refs(scratch, remote)
        tag_ref, main_ref = f"refs/tags/{version}", "refs/heads/main"
        if tag_ref in refs:
            git(scratch, "fetch", "-q", "--no-tags", remote, tag_ref)
            tagged_tree = git(scratch, "rev-parse", "FETCH_HEAD^{tree}").strip()
            if tagged_tree != tree_id:
                raise ReleaseError(f"the Swift package's tag {version} holds another tree ({tagged_tree},"
                                   f" this one {tree_id}): a published tag is never moved (RELEASING.md)")
            return f"The Swift package's tag {version} already holds this tree at {refs[tag_ref]}."
        parent = refs.get(main_ref)
        if parent:
            git(scratch, "fetch", "-q", "--no-tags", remote, main_ref)
        if parent and git(scratch, "rev-parse", "FETCH_HEAD^{tree}").strip() == tree_id:
            commit = parent
        else:
            arguments = ["-c", f"user.name={release.BOT_NAME}", "-c", f"user.email={release.BOT_EMAIL}",
                         "commit-tree", tree_id, "-m", COMMIT_MESSAGE.format(version=version)]
            commit = git(scratch, *arguments, *(["-p", parent] if parent else [])).strip()
        released = [ref[len("refs/tags/"):] for ref in refs if ref.startswith("refs/tags/")]
        highest = all(semantic(version) > semantic(other) for other in released
                      if release.RELEASE_VERSION.match(other))
        updates = [f"{commit}:{tag_ref}"] + ([f"{commit}:{main_ref}"] if highest else [])
        git(scratch, "push", "-q", "--atomic", remote, *updates)
        moved = " and moved main to it" if highest else "; main stays on a higher version"
        return f"Pushed the Swift package's commit {commit}, tagged {version}{moved}."


def publish(root: Path, archive: Path, version: str, sha: str, out: Path, remote: str | None) -> str:
    """Stages and checks the tree, then pushes it unless remote is None (a dry run)."""
    archive_checksum = stage_tree(root, sha, version, archive, out)
    staged = f"Staged the Swift package {version} in {out}, checksum {archive_checksum}."
    if remote is None:
        return staged + " Dry run: nothing pushed."
    return staged + " " + push_package(out, version, remote)


# ─────────────────────────────────────────────────────────────────────────────
# The draft GitHub Release that keeps the framework
# ─────────────────────────────────────────────────────────────────────────────


def release_details(root: Path, version: str) -> dict | None:
    """The GitHub Release version (draft or published) as gh reports it, or None."""
    viewed = release.gh(root, "release", "view", version, "--json", "isDraft,assets,targetCommitish,url",
                        check=False)
    return json.loads(viewed.stdout or "{}") if viewed.returncode == 0 else None


def downloaded_asset(root: Path, version: str, folder: Path) -> Path:
    """Downloads the release's framework into folder."""
    release.gh(root, "release", "download", version, "--pattern", ASSET, "--dir", str(folder))
    return folder / ASSET


def draft_release(root: Path, archive: Path, version: str, sha: str) -> str:
    """Creates the draft release holding the framework, or confirms it holds exactly this zip."""
    details = release_details(root, version)
    if details is None:
        changelog = release.committed_file(root, sha, release.CHANGELOG)
        with tempfile.TemporaryDirectory() as directory:
            notes = Path(directory) / "notes.md"
            notes.write_text(release.release_notes(changelog, version), encoding="utf-8")
            release.gh(root, "release", "create", version, "--draft", "--target", sha, "--title", version,
                       "--notes-file", str(notes), "--generate-notes", str(archive))
        return f"Created the draft GitHub Release {version} at {sha}, holding {ASSET} ({checksum(archive)})."
    if not details.get("isDraft"):
        raise ReleaseError(f"the GitHub Release {version} is published already: a released version is never"
                           " built again; finish it with Release resume (RELEASING.md)")
    if details.get("targetCommitish") != sha:
        raise ReleaseError(f"the draft release {version} was staged from {details.get('targetCommitish')},"
                           f" not {sha}. If Maven Central has not published {version}, delete that draft"
                           " and run Release again; if it has, run Release resume (RELEASING.md)")
    if ASSET not in [asset.get("name") for asset in details.get("assets") or []]:
        release.gh(root, "release", "upload", version, str(archive))
        return f"Added {ASSET} ({checksum(archive)}) to the draft GitHub Release {version}."
    with tempfile.TemporaryDirectory() as directory:
        held = checksum(downloaded_asset(root, version, Path(directory)))
    if held != checksum(archive):
        raise ReleaseError(f"the draft release {version} holds another build of the framework ({held}, this"
                           f" run's {checksum(archive)}). If Maven Central has not published {version},"
                           " delete the draft and run Release again; if it has, run Release resume"
                           " (RELEASING.md)")
    return f"The draft GitHub Release {version} already holds this {ASSET} ({held})."


def download_framework(root: Path, version: str, sha: str, out: Path) -> Path:
    """Downloads the framework the release staged from sha; returns the zip."""
    details = release_details(root, version)
    if details is None or ASSET not in [asset.get("name") for asset in details.get("assets") or []]:
        raise ReleaseError(f"no GitHub Release {version} holds {ASSET}: the Release run's draft job stages"
                           " it before the approval (RELEASING.md, \"When a release stops halfway\")")
    if details.get("targetCommitish") != sha:
        raise ReleaseError(f"the GitHub Release {version} was staged from {details.get('targetCommitish')},"
                           f" not {sha}")
    out.mkdir(parents=True, exist_ok=True)
    if (out / ASSET).exists():
        raise ReleaseError(f"{out / ASSET} exists already")
    return downloaded_asset(root, version, out)


# ─────────────────────────────────────────────────────────────────────────────
# Self-test
# ─────────────────────────────────────────────────────────────────────────────

FIXTURE_MANIFEST = """\
// swift-tools-version: 6.0
import PackageDescription

/// The release.
let version = "0.0.0"

/// Its checksum.
let checksum = "0000000000000000000000000000000000000000000000000000000000000000"

let package = Package(
    name: "SupabaseOffline",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "SupabaseOffline", targets: ["SupabaseOffline"]),
        .library(name: "SupabaseOfflineTesting", targets: ["SupabaseOfflineTesting"]),
    ],
    targets: [
        .binaryTarget(
            name: "SupabaseOfflineCore",
            url: "https://github.com/nesfeder/supabase-offline-kt/releases/download/\\(version)/SupabaseOfflineCore.xcframework.zip",
            checksum: checksum
        ),
        .target(name: "SupabaseOffline", dependencies: ["SupabaseOfflineCore"], resources: [.copy("PrivacyInfo.xcprivacy")]),
        .target(name: "SupabaseOfflineTesting", dependencies: ["SupabaseOffline"]),
    ]
)

// MARK: Development only
if Context.environment["SUPABASE_OFFLINE_LOCAL_CORE"] == "1" {
    package.targets[0] = .binaryTarget(name: "SupabaseOfflineCore", path: "LocalCore/SupabaseOfflineCore.xcframework")
}
package.targets.append(.testTarget(name: "SupabaseOfflineTests"))
"""
FIXTURE_SWIFT = """\
import Foundation

/// A client.
public final class Client {
    /// The runtime's word, removed from messages.
    private let pattern = /(?i)\\b(?:kotlin|konan)\\b/
    func count(_ value: KotlinLong) -> Int { Int(value.int64Value) / 2 }
    func message() -> String { "plain words" }
}
"""
FIXTURE_FILES = {
    "swift/Package.swift": FIXTURE_MANIFEST,
    "swift/README.md": "# SupabaseOffline\n\nAdd the package in Xcode.\n",
    "swift/PARITY.md": "Swift and Kotlin names.\n",
    "swift/Sources/SupabaseOffline/Client.swift": FIXTURE_SWIFT,
    "swift/Sources/SupabaseOffline/PrivacyInfo.xcprivacy": "<plist/>\n",
    "swift/Sources/SupabaseOfflineTesting/Fake.swift": "/// A fake.\nstruct Fake {}\n",
    "swift/Tests/SupabaseOfflineTests/ClientTests.swift": "import Testing\n",
    "LICENSE": "MIT License\n",
    "CHANGELOG.md": "# Changelog\n\n## [0.1.0] - 2026-10-07\n\n### Added\n\n- The Swift package.\n",
    "gradle.properties": "VERSION_NAME=0.1.0\n",
}
FIXTURE_PRIVACY = plistlib.dumps({
    "NSPrivacyTracking": False, "NSPrivacyTrackingDomains": [], "NSPrivacyCollectedDataTypes": [],
    "NSPrivacyAccessedAPITypes": [
        {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
         "NSPrivacyAccessedAPITypeReasons": ["C617.1"]},
        {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
         "NSPrivacyAccessedAPITypeReasons": ["CA92.1"]},
    ],
})


def self_test() -> int:
    """Runs each command's cases against throwaway repositories and remotes."""
    failures = []

    def expect(condition: bool, name: str) -> None:
        print(f"{'ok  ' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    def refused(call, phrase: str) -> bool:
        try:
            call()
        except ReleaseError as error:
            return phrase in str(error)
        return False

    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        archive = work / ASSET
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr(f"{XCFRAMEWORK}/Info.plist", "<plist/>")
        archive_checksum = checksum(archive)
        expect(archive_checksum == hashlib.sha256(archive.read_bytes()).hexdigest()
               and len(archive_checksum) == 64, "checksum is the archive's SHA-256")
        expect(not zip_problems(archive), "a zip with the XCFramework at its root passes")
        with zipfile.ZipFile(work / "bad.zip", "w") as zipped:
            zipped.writestr(f"{XCFRAMEWORK}/Info.plist", "<plist/>")
            zipped.writestr(f"{XCFRAMEWORK}/._Info.plist", "fork")
            zipped.writestr("Other/file", "x")
        expect(len(zip_problems(work / "bad.zip")) == 2, "a zip with a resource fork or another root is refused")

        # The manifest.
        rendered = render_manifest(FIXTURE_MANIFEST, "0.1.0", archive_checksum)
        expect('let version = "0.1.0"' in rendered and f'let checksum = "{archive_checksum}"' in rendered
               and ASSET_URL in rendered, "the manifest is rendered with the version, the checksum and the URL")
        expect(not any(development in rendered for development in DEVELOPMENT_ONLY),
               "the rendered manifest has no local-framework switch, no test target and no marker")
        expect(refused(lambda: render_manifest(FIXTURE_MANIFEST.replace(DEVELOPMENT_MARKER, "// MARK: other"),
                                               "0.1.0", archive_checksum), "lines, not one"),
               "a manifest without its development marker is refused")
        expect(refused(lambda: render_manifest(FIXTURE_MANIFEST.replace("let checksum", "var checksum"),
                                               "0.1.0", archive_checksum), "`let checksum"),
               "a manifest whose checksum line is not there is refused")
        expect(refused(lambda: render_manifest(FIXTURE_MANIFEST, "0.1.0", ZERO_CHECKSUM), "not the checksum"),
               "the zero checksum is never rendered")
        expect(refused(lambda: render_manifest(FIXTURE_MANIFEST, "0.1.0-SNAPSHOT", archive_checksum),
                       "not a release version"), "a snapshot version is never rendered")

        # The lexer.
        comments, strings = swift_comments_and_strings(
            'let a = "x // y" // c1\n/* c2 /* nested */ still */ let b = #"raw "quoted" \\(no)"#\n'
            'let r = /a"b\\/c/ ; let d = 4 / 2 / 1\nlet m = """\nmulti "line"\n"""\n')
        expect(comments == ["// c1", "/* c2 /* nested */ still */"]
               and strings == ['"x // y"', '#"raw "quoted" \\(no)"#', '"""\nmulti "line"\n"""'],
               "the lexer finds comments and strings, and skips regex literals and division")

        # Staging and the tree check.
        root = release.make_repository(work, "source", FIXTURE_FILES)
        sha = git(root, "rev-parse", "HEAD").strip()
        tree = work / "tree"
        stage_tree(root, sha, "0.1.0", archive, tree)
        listing = sorted(path.relative_to(tree).as_posix() for path in tree.rglob("*") if path.is_file())
        expect(listing == ["LICENSE", "Package.swift", "README.md", "Sources/SupabaseOffline/Client.swift",
                           "Sources/SupabaseOffline/PrivacyInfo.xcprivacy",
                           "Sources/SupabaseOfflineTesting/Fake.swift"],
               "the staged tree is the manifest, the readme, the licence and the sources, nothing else")
        expect(not tree_problems(tree, "0.1.0", archive_checksum),
               "the words in Sources' code (a framework type, a regex) are allowed")
        expect(refused(lambda: stage_tree(root, sha, "0.1.0", archive, tree), "not empty"),
               "a tree is staged afresh")

        def mutated(name: str, change) -> list[str]:
            copy = work / f"mutant-{name}"
            shutil.copytree(tree, copy)
            change(copy)
            return tree_problems(copy, "0.1.0", archive_checksum)

        def write(relative: str, text: str):
            def change(copy: Path) -> None:
                (copy / relative).parent.mkdir(parents=True, exist_ok=True)
                (copy / relative).write_text(text)
            return change

        def edit(relative: str, old: str, new: str):
            return lambda copy: (copy / relative).write_text((copy / relative).read_text().replace(old, new))

        cases = [
            ("zero", edit(MANIFEST, archive_checksum, ZERO_CHECKSUM), "the zero checksum"),
            ("checksum", edit(MANIFEST, archive_checksum, "1" * 64), "not the archive's"),
            ("version", edit(MANIFEST, '"0.1.0"', '"0.1.1"'), "does not set version 0.1.0"),
            ("url", edit(MANIFEST, "supabase-offline-kt/releases", "supabase-offline-swift/releases"),
             "does not name the archive's URL"),
            ("local", edit(MANIFEST, "let package", "let local = Context.environment\nlet package"),
             "Context.environment"),
            ("tests", edit(MANIFEST, "    ]\n)", "        .testTarget(name: \"T\"),\n    ]\n)"), ".testTarget("),
            ("tests-folder", write("Tests/SupabaseOfflineTests/ClientTests.swift", "import Testing\n"),
             "Tests is not part of the published package"),
            ("parity", write("PARITY.md", "names\n"), "PARITY.md is not part of"),
            ("local-core", write("LocalCore/x", "x"), "LocalCore is not part of"),
            ("hidden", write("Sources/SupabaseOffline/.DS_Store", "x"), "is a hidden file"),
            ("resource", write("Sources/SupabaseOffline/notes.txt", "x"), "neither Swift nor a privacy manifest"),
            ("readme", edit("README.md", "Add the package", "Add the Kotlin package"), "README.md:3 names 'Kotlin'"),
            ("doc-comment", edit("Sources/SupabaseOffline/Client.swift", "/// A client.",
                                 "/// A client over KMP."), "'KMP' in a comment"),
            ("block-comment", edit("Sources/SupabaseOffline/Client.swift", "import Foundation",
                                   "import Foundation\n/* built with konan */"), "'konan' in a comment"),
            ("string", edit("Sources/SupabaseOffline/Client.swift", '"plain words"', '"a Kotlin failure"'),
             "'Kotlin' in a string literal"),
            ("raw-string", edit("Sources/SupabaseOffline/Client.swift", '"plain words"', '#"kfun:x"#'),
             "'kfun' in a string literal"),
            ("file-name", write("Sources/SupabaseOffline/KotlinBridge.swift", "struct A {}\n"),
             "the path Sources/SupabaseOffline/KotlinBridge.swift names"),
            ("licence", edit("LICENSE", "MIT", "MIT (Kotlin)"), "LICENSE:1 names"),
        ]
        for name, change, phrase in cases:
            problems = mutated(name, change)
            expect(any(phrase in problem for problem in problems), f"check-tree refuses {name}: {phrase}")

        # The package's repository: a bare remote standing in for it.
        remote = work / "swift-package.git"
        subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(remote)], check=True)
        first = push_package(tree, "0.1.0", str(remote))
        refs = remote_refs(work, str(remote))
        expect(first.startswith("Pushed") and refs.get("refs/tags/0.1.0") == refs.get("refs/heads/main"),
               "the first publish pushes the commit, tagged, and main to it")
        expect(git(work, "--git-dir", str(remote), "log", "-1", "--format=%an|%s", "0.1.0").strip()
               == f"{release.BOT_NAME}|Release 0.1.0", "the commit is the bot's, named for the release")
        published_files = sorted(git(work, "--git-dir", str(remote), "ls-tree", "-r", "--name-only", "0.1.0").splitlines())
        expect(published_files == listing, "the tag holds exactly the staged tree")
        expect("already holds this tree" in push_package(tree, "0.1.0", str(remote))
               and remote_refs(work, str(remote)) == refs, "the same publish again changes nothing")
        changed = work / "changed"
        shutil.copytree(tree, changed)
        (changed / "README.md").write_text("# SupabaseOffline\n\nChanged.\n")
        expect(refused(lambda: push_package(changed, "0.1.0", str(remote)), "never moved")
               and remote_refs(work, str(remote)) == refs, "a changed tree under the same tag is refused, nothing moved")
        later = push_package(changed, "0.2.0", str(remote))
        expect("moved main" in later and git(work, "--git-dir", str(remote), "rev-parse", "main^").strip()
               == refs["refs/heads/main"], "the next version is a commit on top of main")
        patch = push_package(tree, "0.1.1", str(remote))
        refs = remote_refs(work, str(remote))
        expect("main stays" in patch and refs["refs/heads/main"] == refs["refs/tags/0.2.0"]
               and "refs/tags/0.1.1" in refs, "a lower version is tagged without moving main")
        # A resume after a push that reached main but not the tag tags main's commit.
        git(work, "--git-dir", str(remote), "tag", "-d", "0.2.0")
        resumed = push_package(changed, "0.2.0", str(remote))
        refs = remote_refs(work, str(remote))
        expect(resumed.startswith("Pushed") and refs["refs/tags/0.2.0"] == refs["refs/heads/main"]
               and git(work, "--git-dir", str(remote), "rev-list", "--count", "main").strip() == "2",
               "a resume with the tag missing tags the commit main already holds")
        dry = work / "dry"
        expect("Dry run" in publish(root, archive, "0.3.0", sha, dry, None)
               and "refs/tags/0.3.0" not in remote_refs(work, str(remote)), "a dry run stages and pushes nothing")

        # The draft release, against a stand-in for gh.
        gh_state = release.install_fake_gh(work)
        expect(draft_release(root, archive, "0.1.0", sha).startswith("Created the draft"),
               "draft-release creates the draft with the framework")
        state = json.loads(gh_state.read_text())
        created = state["calls"][-1]
        expect(state["releases"]["0.1.0"]["isDraft"] and "--draft" in created and str(archive) in created
               and created[created.index("--target") + 1] == sha and "--generate-notes" in created
               and "The Swift package." in state["releases"]["0.1.0"]["notes"] and "--verify-tag" not in created,
               "the draft is at the commit, with the changelog's section, generated notes and the zip")
        expect("already holds this" in draft_release(root, archive, "0.1.0", sha),
               "draft-release again with the same zip does nothing")
        rebuilt = work / "rebuilt" / ASSET
        rebuilt.parent.mkdir()
        with zipfile.ZipFile(rebuilt, "w") as zipped:
            zipped.writestr(f"{XCFRAMEWORK}/Info.plist", "<plist>rebuilt</plist>")
        expect(refused(lambda: draft_release(root, rebuilt, "0.1.0", sha), "holds another build"),
               "draft-release refuses a draft holding another build")
        expect(refused(lambda: draft_release(root, archive, "0.1.0", "f" * 40), "was staged from"),
               "draft-release refuses a draft from another commit")
        fetched = download_framework(root, "0.1.0", sha, work / "resume")
        expect(checksum(fetched) == archive_checksum, "download-framework fetches the staged zip")
        expect(refused(lambda: download_framework(root, "0.1.0", "f" * 40, work / "other"), "was staged from"),
               "download-framework refuses a release staged from another commit")
        expect(refused(lambda: download_framework(root, "0.9.0", sha, work / "none"), "no GitHub Release 0.9.0"),
               "download-framework refuses a version with no release")
        state = json.loads(gh_state.read_text())
        state["releases"]["0.1.0"]["isDraft"] = False
        gh_state.write_text(json.dumps(state))
        expect(refused(lambda: draft_release(root, archive, "0.1.0", sha), "published already"),
               "draft-release refuses a published release")

        # SwiftPM's reading of the manifest.
        good_dump = {"_version": "0.1.0", "products": [{"name": "SupabaseOffline"}, {"name": "SupabaseOfflineTesting"}],
                     "targets": [{"name": FRAMEWORK, "type": "binary", "checksum": archive_checksum,
                                  "url": "https://github.com/nesfeder/supabase-offline-kt/releases/download/0.1.0/" + ASSET},
                                 {"name": "SupabaseOffline"}, {"name": "SupabaseOfflineTesting"}]}
        expect(not dumped_manifest_problems(good_dump, archive_checksum), "SwiftPM's reading of a good manifest passes")
        with_tests = json.loads(json.dumps(good_dump))
        with_tests["targets"].append({"name": "SupabaseOfflineTests", "type": "test"})
        expect(any("the targets are" in problem for problem in dumped_manifest_problems(with_tests, archive_checksum)),
               "SwiftPM's reading of a manifest with a test target fails")
        expect(any("not the archive's" in problem for problem in dumped_manifest_problems(good_dump, "1" * 64)),
               "SwiftPM's reading of another checksum fails")
        if shutil.which("swift") and sys.platform == "darwin":
            try:
                read = read_manifest(tree, archive)
            except ReleaseError as error:
                read = str(error)
            expect(read.startswith("SwiftPM reads the staged manifest"), f"SwiftPM reads a staged manifest: {read}")
        else:
            print("skip read-manifest: SwiftPM for iOS packages is macOS's (the release's macOS job runs it)")

        # Required-reason APIs.
        found = referenced_apis(["_fstat", "_lstat$INODE64", "_OBJC_CLASS_$_NSUserDefaults", "_clock_gettime",
                                 "_NSFileModificationDate", "_OBJC_CLASS_$_NSProcessInfo"], {"standardUserDefaults"})
        record = {("NSPrivacyAccessedAPICategoryFileTimestamp", "fstat"),
                  ("NSPrivacyAccessedAPICategoryFileTimestamp", "lstat"),
                  ("NSPrivacyAccessedAPICategoryFileTimestamp", "NSFileModificationDate"),
                  ("NSPrivacyAccessedAPICategoryUserDefaults", "NSUserDefaults")}
        expect(found == record, "symbols and selectors map to their categories; others are ignored")
        expect(read_record(record_text(record)) == record, "the record reads back as written")
        expect(not required_reason_problems(found, record, FIXTURE_PRIVACY), "a matching record and manifest pass")
        uptime = referenced_apis(["_fstat"], {"systemUptime"})
        expect(any("SystemBootTime systemUptime, which the record" in problem
                   for problem in required_reason_problems(found | uptime, record, FIXTURE_PRIVACY)),
               "a new API the record does not list fails")
        expect(any("no longer references" in problem
                   for problem in required_reason_problems(found - {("NSPrivacyAccessedAPICategoryFileTimestamp",
                                                                      "fstat")}, record, FIXTURE_PRIVACY)),
               "an API the framework stopped using fails until the record is updated")
        bare = plistlib.dumps({"NSPrivacyTracking": False, "NSPrivacyAccessedAPITypes": [
            {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
             "NSPrivacyAccessedAPITypeReasons": ["C617.1"]},
            {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryDiskSpace",
             "NSPrivacyAccessedAPITypeReasons": ["E174.1"]}]})
        problems = required_reason_problems(found, record, bare)
        expect(any("no reason for NSPrivacyAccessedAPICategoryUserDefaults" in problem for problem in problems)
               and any("declares NSPrivacyAccessedAPICategoryDiskSpace" in problem for problem in problems),
               "the manifest must declare exactly the record's categories")
        wrong = FIXTURE_PRIVACY.replace(b"CA92.1", b"C617.1")
        expect(any("UserDefaults needs one or more" in problem
                   for problem in required_reason_problems(found, record, wrong)),
               "a reason Apple does not list for the category fails")

        # GitHub's host keys.
        fingerprints = {base64.b64encode(hashlib.sha256(base64.b64decode(key.split()[1])).digest()).decode().rstrip("=")
                        for key in GITHUB_HOST_KEYS}
        expect(fingerprints == GITHUB_HOST_KEY_FINGERPRINTS, "the pinned host keys have GitHub's fingerprints")

        if shutil.which("ditto"):
            framework = work / "built" / XCFRAMEWORK
            (framework / "ios-arm64").mkdir(parents=True)
            (framework / "Info.plist").write_text("<plist/>")
            (framework / "ios-arm64" / "file").write_text("x")
            subprocess.run(["xattr", "-w", "com.example.test", "1", str(framework / "ios-arm64" / "file")])
            zipped_checksum = zip_framework(framework, work / "built.zip")
            with zipfile.ZipFile(work / "built.zip") as opened:
                names = opened.namelist()
            expect(zipped_checksum == checksum(work / "built.zip") and f"{XCFRAMEWORK}/ios-arm64/file" in names
                   and not zip_problems(work / "built.zip"),
                   "ditto's zip holds the XCFramework at its root and no extended attributes")
            expect(refused(lambda: zip_framework(framework, work / "built.zip"), "zipped once"),
                   "the framework is zipped once")
        else:
            print("skip zip: ditto is macOS's (the release's macOS job runs it)")
    if failures:
        print(f"SELF-TEST FAILED: {len(failures)} check(s).")
        return 1
    print("SELF-TEST PASSED.")
    return 0


def main(argv: list[str]) -> int:
    """Parses the command line and runs one command."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("build").add_argument("--out", required=True)
    reasons = commands.add_parser("check-required-reasons")
    reasons.add_argument("--framework", required=True)
    reasons.add_argument("--update", action="store_true")
    zipped = commands.add_parser("zip")
    zipped.add_argument("--framework", required=True)
    zipped.add_argument("--out", required=True)
    commands.add_parser("checksum").add_argument("zip")
    published = commands.add_parser("publish")
    published.add_argument("--zip", required=True)
    published.add_argument("--version", required=True)
    published.add_argument("--sha", required=True)
    published.add_argument("--out", required=True)
    target = published.add_mutually_exclusive_group(required=True)
    target.add_argument("--remote")
    target.add_argument("--dry-run", action="store_true")
    dumped = commands.add_parser("read-manifest")
    dumped.add_argument("--tree", required=True)
    dumped.add_argument("--zip", required=True)
    checked = commands.add_parser("check-tree")
    checked.add_argument("--tree", required=True)
    checked.add_argument("--zip", required=True)
    checked.add_argument("--version", required=True)
    drafted = commands.add_parser("draft-release")
    drafted.add_argument("--zip", required=True)
    drafted.add_argument("--version", required=True)
    drafted.add_argument("--sha", required=True)
    downloaded = commands.add_parser("download-framework")
    downloaded.add_argument("--version", required=True)
    downloaded.add_argument("--sha", required=True)
    downloaded.add_argument("--out", required=True)
    commands.add_parser("known-hosts")
    commands.add_parser("self-test")
    arguments = parser.parse_args(argv)
    root = Path.cwd()
    try:
        if arguments.command == "self-test":
            return self_test()
        if arguments.command == "build":
            print(f"Built {build_framework(root, Path(arguments.out).resolve())}.")
        elif arguments.command == "check-required-reasons":
            print(check_required_reasons(root, Path(arguments.framework), arguments.update))
        elif arguments.command == "zip":
            out = Path(arguments.out)
            value = zip_framework(Path(arguments.framework), out)
            print(f"Zipped {out}: {out.stat().st_size} bytes, checksum {value}.")
        elif arguments.command == "checksum":
            print(checksum(Path(arguments.zip)))
        elif arguments.command == "publish":
            print(publish(root, Path(arguments.zip), arguments.version, arguments.sha,
                          Path(arguments.out), None if arguments.dry_run else arguments.remote))
        elif arguments.command == "read-manifest":
            print(read_manifest(Path(arguments.tree), Path(arguments.zip)))
        elif arguments.command == "check-tree":
            problems = tree_problems(Path(arguments.tree), arguments.version, checksum(Path(arguments.zip)))
            if problems:
                raise ReleaseError("the tree is not publishable:\n  " + "\n  ".join(problems))
            print("The tree is publishable.")
        elif arguments.command == "draft-release":
            print(draft_release(root, Path(arguments.zip), arguments.version, arguments.sha))
        elif arguments.command == "download-framework":
            path = download_framework(root, arguments.version, arguments.sha, Path(arguments.out))
            print(f"Downloaded {path}: checksum {checksum(path)}.")
        elif arguments.command == "known-hosts":
            print("\n".join(f"github.com {key}" for key in GITHUB_HOST_KEYS))
    except ReleaseError as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
