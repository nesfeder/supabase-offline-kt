#!/usr/bin/env python3
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""Keeps the Swift bridge's Objective-C header to the bridge's own surface.

The Swift package wraps one Kotlin framework, SupabaseOfflineCore, built from
supabase-offline-swift-bridge. Its header is the only Kotlin surface a Swift target can import, so
it must name nothing but the bridge's own types and the Kotlin runtime's base types: never a type
of the library, supabase-kt, Ktor, okio or kotlinx. The check fails when the header

  1. declares or names a class, protocol or category that the allow-list does not hold
     (supabase-offline-swift-bridge/header/allowed-names.txt: one name, glob or `Class (Category)`
     per line);
  2. exports a suspend function: a method with a `completionHandler:` block;
  3. exports a function that throws: a method with an `error:` out-parameter;
  4. differs from the recorded header, supabase-offline-swift-bridge/header/SupabaseOfflineCore.h,
     which the Swift package is written against. After an intended change, run with --update and
     commit the record.

It reads the header of the release device framework, which CI links first:

  ./gradlew :supabase-offline-swift-bridge:linkReleaseFrameworkIosArm64 \\
      -Psupabase.offline.iosSqlite=system

It fails with status 2 when that header or the allow-list is missing: a check that saw nothing
proves nothing.

Usage:
  scripts/check-bridge-header.py              check the linked header
  scripts/check-bridge-header.py --update     record the linked header as the contract
  scripts/check-bridge-header.py --self-test  prove the check catches each kind of violation

Runs on the system python3 of macOS (3.9) and on CI runners; standard library only.
"""

from __future__ import annotations

import fnmatch
import os
import re
import sys
import tempfile
from typing import List, Optional, Set, Tuple

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULE = "supabase-offline-swift-bridge"
LINKED_HEADER = os.path.join(
    MODULE,
    "build",
    "bin",
    "iosArm64",
    "releaseFramework",
    "SupabaseOfflineCore.framework",
    "Headers",
    "SupabaseOfflineCore.h",
)
RECORDED_HEADER = os.path.join(MODULE, "header", "SupabaseOfflineCore.h")
ALLOW_LIST = os.path.join(MODULE, "header", "allowed-names.txt")

# `@class A, B<T>;` and `@protocol A, B;`: forward declarations, which name every type used.
FORWARD = re.compile(r"^@(class|protocol)\s+([^;{]+);", re.MULTILINE)
# `@interface Name<T> : Super <Protocols>` or `@interface Name (Category)`.
INTERFACE = re.compile(r"^@interface\s+(\w+)\s*(?:<[^>]*>)?\s*(?:\((\w*)\))?", re.MULTILINE)
# `@protocol Name <Protocols>` on its own, not a forward declaration.
PROTOCOL = re.compile(r"^@protocol\s+(\w+)\s*(?:<[^>]*>)?\s*$", re.MULTILINE)
SUSPEND = re.compile(r"\bcompletionHandler:")
THROWING = re.compile(r"\berror:\s*\(\s*NSError\b")
# Block comments: the KDoc the header carries may mention any name.
COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)


def read_allow_list(text: str) -> Tuple[List[str], Set[str]]:
    """The name globs and the exact `Class (Category)` entries of an allow-list."""
    globs, categories = [], set()
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if "(" in line:
            categories.add(re.sub(r"\s+", " ", line))
        else:
            globs.append(line)
    return globs, categories


def declared_names(header: str) -> Tuple[Set[str], Set[str]]:
    """Every type name the header declares or names, and every category as `Class (Category)`."""
    code = COMMENT.sub("", header)
    names, categories = set(), set()
    for match in FORWARD.finditer(code):
        for item in match.group(2).split(","):
            name = item.strip().split("<", 1)[0].strip()
            if name:
                names.add(name)
    for match in INTERFACE.finditer(code):
        names.add(match.group(1))
        if match.group(2) is not None:
            categories.add(f"{match.group(1)} ({match.group(2)})")
    for match in PROTOCOL.finditer(code):
        names.add(match.group(1))
    return names, categories


def problems(header: str, allow_list: str, recorded: Optional[str]) -> List[str]:
    """Every rule [header] breaks, one line each; empty when it passes."""
    globs, allowed_categories = read_allow_list(allow_list)
    names, categories = declared_names(header)
    found = []
    if not names:
        found.append("the header declares no type: it is not a framework header")
    for name in sorted(names):
        if not any(fnmatch.fnmatchcase(name, pattern) for pattern in globs):
            found.append(f"{name}: not on the allow-list; the header must not export it")
    for category in sorted(categories - allowed_categories):
        found.append(f"{category}: a category not on the allow-list")
    code = COMMENT.sub("", header)
    for number, line in enumerate(code.splitlines(), start=1):
        if SUSPEND.search(line):
            found.append(f"line {number}: a suspend function is exported (completionHandler:)")
        if THROWING.search(line):
            found.append(f"line {number}: a function that throws is exported (error:)")
    if recorded is not None and recorded != header:
        found.append(
            f"the header differs from {RECORDED_HEADER}: after an intended change, run "
            "scripts/check-bridge-header.py --update and commit the record"
        )
    return found


def read(path: str) -> Optional[str]:
    """The file's text, or `None` when it does not exist."""
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def check(root: str, update: bool) -> int:
    header = read(os.path.join(root, LINKED_HEADER))
    allow_list = read(os.path.join(root, ALLOW_LIST))
    if header is None:
        print(
            f"No linked header at {LINKED_HEADER}: run ./gradlew "
            ":supabase-offline-swift-bridge:linkReleaseFrameworkIosArm64 "
            "-Psupabase.offline.iosSqlite=system first.",
            file=sys.stderr,
        )
        return 2
    if allow_list is None:
        print(f"No allow-list at {ALLOW_LIST}.", file=sys.stderr)
        return 2
    recorded_path = os.path.join(root, RECORDED_HEADER)
    found = problems(header, allow_list, None if update else read(recorded_path))
    if not update and read(recorded_path) is None:
        found.append(f"no recorded header at {RECORDED_HEADER}: run with --update and commit it")
    for line in found:
        print(f"::error::SupabaseOfflineCore.h: {line}")
    names, _ = declared_names(header)
    print(f"Checked SupabaseOfflineCore.h: {len(names)} type names, {len(found)} problem(s).")
    if found:
        return 1
    if update:
        with open(recorded_path, "w", encoding="utf-8") as handle:
            handle.write(header)
        print(f"Recorded the header at {RECORDED_HEADER}.")
    return 0


CLEAN = """\
@class SOCBridgeClient, SOCKotlinArray<T>;
@protocol SOCBridgeLogSink;

NS_ASSUME_NONNULL_BEGIN
__attribute__((swift_name("KotlinBase")))
@interface SOCBase : NSObject
@end
@interface NSError (NSErrorSOCKotlinException)
@end
/** Names SOCSupabase_postgrest_ktPostgrestResult in prose, which never counts. */
@interface SOCBridgeClient : SOCBase
- (void)selectQuery:(NSString *)query completion:(void (^)(NSString * _Nullable))completion;
@end
@protocol SOCBridgeLogSink
@required
@end
@interface SOCKotlinArray<T> : SOCBase
@end
NS_ASSUME_NONNULL_END
"""

ALLOWED = """\
SOCBridge*   # the bridge's own types
NS*
SOCBase
SOCKotlinArray
NSError (NSErrorSOCKotlinException)
"""

SELF_TEST_CASES: Tuple[Tuple[str, str, Optional[str], bool], ...] = (
    ("a clean header", CLEAN, CLEAN, False),
    (
        "a library type",
        CLEAN.replace("@class SOCBridgeClient,", "@class SOCBridgeClient, SOCSupabase_offline_ktOffline,"),
        None,
        True,
    ),
    (
        "a supabase-kt type in an interface",
        CLEAN + "@interface SOCSupabase_postgrest_ktPostgrestResult : SOCBase\n@end\n",
        None,
        True,
    ),
    ("a kotlinx protocol", CLEAN + "@protocol SOCKotlinx_coroutines_coreJob\n@end\n", None, True),
    ("a category on a platform type", CLEAN + "@interface NSData (SOCBridgeLeak)\n@end\n", None, True),
    (
        "a suspend function",
        CLEAN.replace("completion:(void", "completionHandler:(void"),
        None,
        True,
    ),
    (
        "a throwing function",
        CLEAN.replace(
            "completion:(void (^)(NSString * _Nullable))completion;",
            "error:(NSError * _Nullable * _Nullable)error;",
        ),
        None,
        True,
    ),
    ("a header that differs from the record", CLEAN, CLEAN + "\n", True),
    ("no declarations", "NS_ASSUME_NONNULL_BEGIN\nNS_ASSUME_NONNULL_END\n", None, True),
)


def self_test() -> int:
    failures = []
    for name, header, recorded, expected in SELF_TEST_CASES:
        found = problems(header, ALLOWED, recorded)
        if bool(found) != expected:
            failures.append(f"{name}: expected {'a problem' if expected else 'none'}, got {found}")
    with tempfile.TemporaryDirectory() as root:
        if check(root, update=False) != 2:
            failures.append("a tree with no linked header did not fail with status 2")
    for failure in failures:
        print(f"::error::self-test: {failure}")
    total = len(SELF_TEST_CASES) + 1
    print(f"Self-test: {total - len(failures)} of {total} cases as expected.")
    return 1 if failures else 0


if __name__ == "__main__":
    arguments = sys.argv[1:]
    if arguments == ["--self-test"]:
        sys.exit(self_test())
    if arguments not in ([], ["--update"]):
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(check(REPO_ROOT, update=arguments == ["--update"]))
