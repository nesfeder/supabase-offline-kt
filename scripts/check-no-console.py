#!/usr/bin/env python3
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""Keeps the library from writing to a console. Whole-tree: every run scans every main source set
of both published modules, so no base commit is needed.

The library reports through its log sink only: supabase-kt's logging, at the level and to the
processor the app configured. Nothing in shipped code may print, on any path, failures in
background work included. The check fails on a call or reference to any of:

    print / println                         Kotlin's console output
    System.out / System.err / printStackTrace   the JVM's
    android.util.Log                        Android's log
    NSLog / NSLogv / os_log                 Apple's logs
    printf / fprintf / puts / perror        the C library's, through cinterop
    java.util.logging                       the JDK's logger

Comments, string literals and character literals are stripped before anything is matched, so the
words in prose or in a message never count; the code inside a string template still does.

Usage:
    scripts/check-no-console.py               check the tree
    scripts/check-no-console.py --self-test   prove the check catches each kind of console output

It fails with status 2 when it finds no main source file to scan: a check that saw nothing proves
nothing.
"""

import os
import re
import sys
import tempfile
from typing import Iterator, List, Tuple

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULES = ("supabase-offline-kt", "supabase-offline-kt-testing")

# (name, pattern) matched against code with comments and literals stripped.
FORBIDDEN: Tuple[Tuple[str, "re.Pattern[str]"], ...] = (
    ("print", re.compile(r"(?<![\w.])(?:kotlin\.io\.)?print(?:ln)?\s*\(")),
    ("System.out / System.err", re.compile(r"\bSystem\s*\.\s*(?:out|err)\b")),
    ("printStackTrace", re.compile(r"\.\s*printStackTrace\s*\(")),
    ("android.util.Log", re.compile(r"\bandroid\s*\.\s*util\s*\.\s*Log\b")),
    ("NSLog", re.compile(r"\bNSLogv?\b")),
    ("os_log", re.compile(r"\bos_log\w*\b")),
    ("C stdio", re.compile(r"(?<![\w.])(?:platform\.posix\.)?(?:f?printf|puts|perror)\s*\(")),
    ("java.util.logging", re.compile(r"\bjava\s*\.\s*util\s*\.\s*logging\b")),
)


def strip_comments_and_literals(source: str) -> str:
    """The code of a Kotlin file with every comment and literal blanked, newlines kept.

    A string template's `${...}` stays as code, since it runs; `$name` is a plain reference.
    """
    out: List[str] = []
    index, length = 0, len(source)
    # Each entry is the closing delimiter of an open string, or "}" for an open template; the
    # brace depth of each template counts its own nested braces.
    stack: List[str] = []
    depths: List[int] = []

    def blank(text: str) -> str:
        return "".join("\n" if ch == "\n" else " " for ch in text)

    while index < length:
        in_string = bool(stack) and stack[-1] != "}"
        if in_string:
            closing = stack[-1]
            if closing == '"' and source[index] == "\\":
                out.append(blank(source[index : index + 2]))
                index += 2
                continue
            if source.startswith(closing, index):
                end = index + len(closing)
                # A raw string ends at the last of a run of quotes.
                while closing == '"""' and end < length and source[end] == '"':
                    end += 1
                out.append(blank(source[index:end]))
                index = end
                stack.pop()
                continue
            if source.startswith("${", index):
                out.append("  ")
                index += 2
                stack.append("}")
                depths.append(0)
                continue
            out.append(blank(source[index]))
            index += 1
            continue

        # Code, at the top level or inside a template.
        if source.startswith("//", index):
            end = source.find("\n", index)
            end = length if end == -1 else end
            out.append(blank(source[index:end]))
            index = end
            continue
        if source.startswith("/*", index):
            depth, end = 1, index + 2
            while end < length and depth:
                if source.startswith("/*", end):
                    depth, end = depth + 1, end + 2
                elif source.startswith("*/", end):
                    depth, end = depth - 1, end + 2
                else:
                    end += 1
            out.append(blank(source[index:end]))
            index = end
            continue
        if source.startswith('"""', index):
            out.append("   ")
            index += 3
            stack.append('"""')
            continue
        if source[index] == '"':
            out.append(" ")
            index += 1
            stack.append('"')
            continue
        if source[index] == "'":
            match = re.match(r"'(?:\\.|\\u[0-9a-fA-F]{4}|[^'\\])'", source[index:])
            if match:
                out.append(blank(match.group(0)))
                index += len(match.group(0))
                continue
        if source[index] == "`":
            end = source.find("`", index + 1)
            end = length if end == -1 else end + 1
            out.append(blank(source[index:end]))
            index = end
            continue
        if stack and stack[-1] == "}":
            if source[index] == "{":
                depths[-1] += 1
            elif source[index] == "}":
                if depths[-1] == 0:
                    stack.pop()
                    depths.pop()
                    out.append(" ")
                    index += 1
                    continue
                depths[-1] -= 1
        out.append(source[index])
        index += 1
    return "".join(out)


def main_source_files(root: str) -> Iterator[str]:
    """Every Kotlin file of a main source set of both modules, relative to [root]."""
    for module in MODULES:
        source_root = os.path.join(root, module, "src")
        if not os.path.isdir(source_root):
            continue
        for source_set in sorted(os.listdir(source_root)):
            if not source_set.endswith("Main"):
                continue
            for directory, _, files in sorted(os.walk(os.path.join(source_root, source_set))):
                for name in sorted(files):
                    if name.endswith(".kt"):
                        yield os.path.relpath(os.path.join(directory, name), root)


def violations(root: str) -> Tuple[int, List[str]]:
    """How many files were scanned, and one `path:line: name` per console call found."""
    scanned, found = 0, []
    for path in main_source_files(root):
        scanned += 1
        with open(os.path.join(root, path), encoding="utf-8") as handle:
            code = strip_comments_and_literals(handle.read())
        for number, line in enumerate(code.split("\n"), start=1):
            for name, pattern in FORBIDDEN:
                if pattern.search(line):
                    found.append(f"{path}:{number}: {name}")
    return scanned, found


def check(root: str) -> int:
    scanned, found = violations(root)
    if scanned == 0:
        print("No main source file found to scan.", file=sys.stderr)
        return 2
    for line in found:
        print(f"::error::{line}: the library must log through its log sink, never a console")
    print(f"Scanned {scanned} main-source files: {len(found)} console call(s).")
    return 1 if found else 0


SELF_TEST_CASES: Tuple[Tuple[str, str, bool], ...] = (
    ("println", 'fun a() { println("x") }', True),
    ("print", "fun a() { print(1) }", True),
    ("qualified println", 'fun a() { kotlin.io.println("x") }', True),
    ("NSLog", 'fun a() { NSLog("%s", "x") }', True),
    ("NSLog import", "import platform.Foundation.NSLog", True),
    ("os_log", "fun a() { os_log_create(null, null) }", True),
    ("printf", 'fun a() { platform.posix.printf("x") }', True),
    ("System.err", 'fun a() { System.err.println("x") }', True),
    ("printStackTrace", "fun a(e: Throwable) { e.printStackTrace() }", True),
    ("android Log", "import android.util.Log", True),
    ("java logging", "import java.util.logging.Logger", True),
    ("inside a template", 'fun a() = "${println("x")}"', True),
    ("in a line comment", '// println("x")', False),
    ("in a block comment", '/* NSLog("x") /* nested */ println() */ fun a() = 1', False),
    ("in a string", 'val a = "call println(x) or NSLog"', False),
    ("in a raw string", 'val a = """println("x")"""', False),
    ("an escaped quote", r'val a = "say \"println(x)\""', False),
    ("a longer name", "fun a() { printlnLater(); reprint(1); blueprint(2) }", False),
    ("a member named print", "fun a(p: Printer) { p.print(1) }", False),
    ("a template reference", 'val a = "$printer and ${count}"', False),
)


def self_test() -> int:
    failures = []
    for name, source, expected in SELF_TEST_CASES:
        with tempfile.TemporaryDirectory() as root:
            directory = os.path.join(root, MODULES[0], "src", "commonMain", "kotlin")
            os.makedirs(directory)
            with open(os.path.join(directory, "Probe.kt"), "w", encoding="utf-8") as handle:
                handle.write(source + "\n")
            _, found = violations(root)
            if bool(found) != expected:
                failures.append(f"{name}: expected {'a violation' if expected else 'none'}, got {found}")
    with tempfile.TemporaryDirectory() as root:
        if check(root) != 2:
            failures.append("an empty tree did not fail with status 2")
    for failure in failures:
        print(f"::error::self-test: {failure}")
    total = len(SELF_TEST_CASES) + 1
    print(f"Self-test: {total - len(failures)} of {total} cases as expected.")
    return 1 if failures else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(check(REPO_ROOT))
