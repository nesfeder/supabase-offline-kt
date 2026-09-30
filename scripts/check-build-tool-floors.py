#!/usr/bin/env python3
"""Checks that the build-tool floors hold: no floored package resolves below its floor.

The floors are the ``buildToolFloor-*`` libraries in ``gradle/libs.versions.toml``: patched minimum
versions of libraries that the Gradle plugins and their tools put on the build's own classpaths.
The build applies them as dependency constraints to these configurations, which this script checks:

  * the root build script's ``classpath``, which holds every plugin (``build.gradle.kts``);
  * build-logic's ``precompiledScriptPluginAccessorsGenerationClasspath``, the plugins' full
    runtime that kotlin-dsl resolves (``build-logic/build.gradle.kts``);
  * each module's ``androidLintTool`` and ``swiftExportClasspathResolvable``, the lint and Swift
    export tools (``supabase-offline.kmp-library``).

It asks Gradle for their resolved graphs (the ``buildEnvironment`` and ``dependencies`` reports) and
fails when:

  * a floored package resolves below its floor in one of them;
  * one of them is missing, so the floors no longer reach it;
  * a floor resolves in none of them: a mistyped coordinate, or a package no plugin brings any more;
  * a floor raises nothing, because everything that asks for the package already asks for the floor
    or later: the plugin release that made the floor unnecessary has arrived, so remove it.

Usage (from the repository root):
  check-build-tool-floors.py                  run Gradle, then check
  check-build-tool-floors.py --report FILE    check a saved log of the same Gradle command
  check-build-tool-floors.py --catalog FILE   take the floors from another catalog, to check a tree
                                              that has none (the state before the floors)
  check-build-tool-floors.py --self-test      prove the check catches each kind of failure

Runs on the system python3 of macOS (3.9) and on CI runners; standard library only.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field

CATALOG = "gradle/libs.versions.toml"
SETTINGS = "settings.gradle.kts"
FLOOR_PREFIX = "buildToolFloor-"
INCLUDE = re.compile(r'^include\("(?P<path>:[^"]+)"\)', re.MULTILINE)


def gradle_command(settings: str) -> list[str]:
    """The Gradle command whose output this script reads: a report per project in the settings.

    A `dependencies` task named without a path runs in the root project only, so each module's is
    named. The configuration cache is off because report tasks do not support it.
    """
    modules = [match.group("path") for match in INCLUDE.finditer(settings)]
    return (["./gradlew", "--no-configuration-cache", "--console=plain", ":buildEnvironment",
             ":build-logic:dependencies"] + [f"{module}:dependencies" for module in modules])


# (report task, configuration) pairs that must each be present.
REQUIRED = [
    (":buildEnvironment", "classpath"),
    (":build-logic:dependencies", "precompiledScriptPluginAccessorsGenerationClasspath"),
]
# Configurations checked in every project whose `dependencies` report has them. At least one
# project must have each.
PER_MODULE = ["androidLintTool", "swiftExportClasspathResolvable"]

TASK = re.compile(r"^> Task (?P<path>\S+)")
CONFIGURATION = re.compile(r"^(?P<name>[A-Za-z][A-Za-z0-9_]*)( - .*| \(n\))?$")
NODE = re.compile(r"^[| ]*[+\\]--- (?P<body>.+)$")
MARKERS = re.compile(r"( \([cn*]\)| FAILED)+$")
LIBRARY = re.compile(r"^(?P<alias>[\w.-]+)\s*=\s*\{(?P<body>[^}]*)\}\s*$")
VERSION = re.compile(r'^(?P<name>[\w.-]+)\s*=\s*"(?P<value>[^"]+)"\s*$')


@dataclass
class Resolution:
    """What one configuration's report says about one package."""

    resolved: set[str] = field(default_factory=set)
    requested: set[str] = field(default_factory=set)
    requested_unknown: bool = False
    failed: bool = False


def version_key(version: str) -> tuple:
    """Orders versions numerically; a qualifier after the numbers (1.86-beta) sorts before none."""
    numbers = [int(part) for part in re.findall(r"\d+", version.split("-")[0])]
    qualified = "-" in version or bool(re.search(r"[A-Za-z]", version.split("-")[0]))
    return (numbers, 0 if qualified else 1)


def below(version: str, floor: str) -> bool:
    """Whether a resolved or requested version is lower than the floor."""
    return version_key(version) < version_key(floor)


def read_floors(text: str) -> dict[str, str]:
    """The floors in a version catalog, as ``group:module`` to the minimum version."""
    versions: dict[str, str] = {}
    floors: dict[str, str] = {}
    section = ""
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            section = stripped
            continue
        if section == "[versions]":
            match = VERSION.match(stripped)
            if match:
                versions[match.group("name")] = match.group("value")
        elif section == "[libraries]":
            match = LIBRARY.match(stripped)
            if not match or not match.group("alias").startswith(FLOOR_PREFIX):
                continue
            body = match.group("body")
            module = re.search(r'module\s*=\s*"([^"]+)"', body)
            reference = re.search(r'version\.ref\s*=\s*"([^"]+)"', body)
            literal = re.search(r'version\s*=\s*"([^"]+)"', body)
            version = versions.get(reference.group(1)) if reference else None
            if literal:
                version = literal.group(1)
            if not module or not version:
                raise SystemExit(f"{match.group('alias')}: a floor needs a module and a version")
            floors[module.group(1)] = version
    return floors


def parse_node(body: str) -> tuple[str, str | None, str | None, bool, bool] | None:
    """A report line's package, requested and selected versions, and its (c) and FAILED marks."""
    constraint = "(c)" in body
    failed = body.endswith(" FAILED")
    body = MARKERS.sub("", body)
    left, _, right = body.partition(" -> ")
    if left.startswith("project "):
        return None
    parts = left.split(":", 2)
    if len(parts) < 2:
        return None
    requested = parts[2] if len(parts) == 3 else None
    selected = right or None
    if selected and selected.startswith("project "):
        return None
    if selected and ":" in selected:
        selected = selected.split(":")[-1]
    return f"{parts[0]}:{parts[1]}", requested, selected, constraint, failed


def read_report(text: str, floors: dict[str, str]) -> dict[str, dict[str, Resolution]]:
    """Each report task and configuration in the Gradle output, with its floored packages."""
    reports: dict[str, dict[str, Resolution]] = {}
    task = configuration = None
    for line in text.splitlines():
        task_match = TASK.match(line)
        if task_match:
            task = task_match.group("path")
            configuration = None
            continue
        if task is None or not (task.endswith(":dependencies") or task.endswith(":buildEnvironment")):
            continue
        node = NODE.match(line)
        if node and configuration:
            parsed = parse_node(node.group("body"))
            if parsed is None or parsed[0] not in floors:
                continue
            package, requested, selected, constraint, failed = parsed
            resolution = reports[configuration].setdefault(package, Resolution())
            resolution.resolved.add(selected or requested or "?")
            resolution.failed = resolution.failed or failed
            if not constraint:
                if requested and re.fullmatch(r"[\w.-]+", requested):
                    resolution.requested.add(requested)
                else:
                    resolution.requested_unknown = True
            continue
        header = CONFIGURATION.match(line)
        if header:
            configuration = f"{task} {header.group('name')}"
            reports.setdefault(configuration, {})
    return reports


def checked_configurations(reports: dict[str, dict[str, Resolution]]) -> tuple[list[str], list[str]]:
    """The configurations the floors must hold in, and what is missing from the report."""
    checked, missing = [], []
    for task, name in REQUIRED:
        key = f"{task} {name}"
        (checked if key in reports else missing).append(key)
    for name in PER_MODULE:
        found = sorted(key for key in reports
                       if key.endswith(f":dependencies {name}") and not key.startswith(":build-logic:"))
        checked.extend(found)
        if not found:
            missing.append(f"<every module>:dependencies {name}")
    return checked, missing


def check(reports: dict[str, dict[str, Resolution]],
          floors: dict[str, str]) -> tuple[list[str], list[str], list[str]]:
    """The problems, a table of each floored package in each checked configuration, and those."""
    problems: list[str] = []
    table: list[str] = []
    if not floors:
        problems.append(f"{CATALOG} has no {FLOOR_PREFIX}* library, so there is nothing to check")
    checked, missing = checked_configurations(reports)
    problems.extend(f"{key}: not in the Gradle report, so no floor reaches it" for key in missing)
    for package, floor in sorted(floors.items()):
        found = [(key, reports[key][package]) for key in checked if package in reports[key]]
        if not found:
            problems.append(f"{package} (floor {floor}) resolves in no checked configuration: a "
                            "mistyped coordinate, or no plugin brings it any more; remove the floor")
            continue
        raises = False
        for key, resolution in found:
            lowest = min(resolution.requested, key=version_key) if resolution.requested else "?"
            resolved = ", ".join(sorted(resolution.resolved))
            table.append(f"{package:45} {floor:9} {lowest:>9} -> {resolved:9} {key}")
            if resolution.failed:
                problems.append(f"{key}: {package} failed to resolve (floor {floor})")
            for version in sorted(resolution.resolved):
                if version == "?" or below(version, floor):
                    problems.append(f"{key}: {package} resolves to {version}, below its floor {floor}")
            if resolution.requested_unknown or any(below(version, floor) for version in resolution.requested):
                raises = True
        if not raises:
            problems.append(f"{package} (floor {floor}): everything that asks for it already asks for "
                            f"{floor} or later, so the floor is no longer needed; remove it")
    return problems, table, checked


def main() -> None:
    """Parses the arguments, then runs the self-test or the check."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--report", help="a saved log of the Gradle command, instead of running it")
    parser.add_argument("--catalog", default=CATALOG, help="the catalog to read the floors from")
    parser.add_argument("--self-test", action="store_true", help="prove the check catches each failure")
    arguments = parser.parse_args()
    if arguments.self_test:
        self_test()
        return
    with open(arguments.catalog, encoding="utf-8") as catalog:
        floors = read_floors(catalog.read())
    if arguments.report:
        with open(arguments.report, encoding="utf-8") as report:
            text = report.read()
    else:
        with open(SETTINGS, encoding="utf-8") as settings:
            command = gradle_command(settings.read())
        print("$ " + " ".join(command), flush=True)
        run = subprocess.run(command, capture_output=True, text=True)
        text = run.stdout
        if run.returncode != 0:
            sys.stdout.write(run.stdout[-4000:] + run.stderr[-4000:])
            raise SystemExit(f"Gradle failed ({run.returncode}), so nothing was checked")
    problems, table, checked = check(read_report(text, floors), floors)
    print(f"{'package':45} {'floor':9} {'asked for':>9} -> resolved  configuration")
    print("\n".join(table))
    for problem in problems:
        print(f"PROBLEM {problem}")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write("### Build-tool floors\n\n")
            summary.write(f"{len(floors)} floors, {len(problems)} problem(s)\n")
    if problems:
        print(f"::error::The build-tool floors have {len(problems)} problem(s); see the log.")
        sys.exit(1)
    print(f"Every floor holds: {len(floors)} floors, {len(checked)} configurations.")


SELF_TEST_CATALOG = """[versions]
buildToolFloor-lib = "1.86"
other = "1.0"

[libraries]
buildToolFloor-lib = { module = "org.example:lib", version.ref = "buildToolFloor-lib" }
buildToolFloor-other = { module = "org.example:other", version = "2.0.1" }
unrelated = { module = "org.example:unrelated", version.ref = "other" }
"""

LIB_OK = "+--- org.example:lib:1.79 -> 1.86\n+--- org.example:lib:1.86 (c)\n"
OTHER_OK = "|    \\--- org.example:other:{strictly 2.0} -> 2.0.1\n"


def self_test_report(lib: str = LIB_OK, other: str = OTHER_OK, lint: bool = True,
                     accessors: bool = True, swift: str | None = None) -> str:
    """A Gradle log of the checked command, with the given graphs for the two floored packages."""
    sections = [
        "> Task :build-logic:compileKotlin\nw: a compiler warning\n",
        f"> Task :buildEnvironment\nDaemon JVM: a JDK\n\nRoot project 'root'\n\nclasspath\n{lib}{other}",
        "> Task :dependencies\n\nRoot project 'root'\n\nNo configurations\n",
        "> Task :module:dependencies\n\nProject ':module'\n\n"
        + (f"androidLintTool\n{lib}\n" if lint else "")
        + "androidLintToolDeclarable (n)\nNo dependencies\n\n"
        + f"swiftExportClasspathResolvable\n{other if swift is None else swift}\n",
    ]
    if accessors:
        sections.append("> Task :build-logic:dependencies\n\n"
                        "precompiledScriptPluginAccessorsGenerationClasspath - The plugins.\n"
                        f"+--- com.example:plugin:1.0\n|    {lib}{other}")
    return "\n".join(sections) + "\nBUILD SUCCESSFUL in 1s\n"


# Each case: what it proves, the report, and a phrase the problems must contain (None: must pass).
SELF_TEST_CASES: list[tuple[str, str, str | None]] = [
    ("every floor holds", self_test_report(), None),
    ("a package below its floor",
     self_test_report(lib="+--- org.example:lib:1.79\n"),
     "org.example:lib resolves to 1.79, below its floor 1.86"),
    ("a package below its floor in one configuration only",
     self_test_report(swift="\\--- org.example:other:2.0\n"),
     ":module:dependencies swiftExportClasspathResolvable: org.example:other resolves to 2.0"),
    ("a floor that does not resolve",
     self_test_report(lib="+--- org.example:lib:1.79 -> 1.99 FAILED\n"),
     "org.example:lib failed to resolve (floor 1.86)"),
    ("a qualifier sorts before the release",
     self_test_report(lib="+--- org.example:lib:1.79 -> 1.86-beta1\n"),
     "resolves to 1.86-beta1, below its floor 1.86"),
    ("a required configuration missing",
     self_test_report(accessors=False),
     "precompiledScriptPluginAccessorsGenerationClasspath: not in the Gradle report"),
    ("no module with the lint tool configuration",
     self_test_report(lint=False),
     "<every module>:dependencies androidLintTool: not in the Gradle report"),
    ("a floor nothing resolves",
     self_test_report(other=""),
     "org.example:other (floor 2.0.1) resolves in no"),
    ("a floor that raises nothing",
     self_test_report(lib="+--- org.example:lib:1.86\n"),
     "org.example:lib (floor 1.86): everything that asks for it already asks for 1.86 or later"),
    ("a constraint is not a request",
     self_test_report(lib="+--- org.example:lib:1.86\n+--- org.example:lib:1.79 -> 1.86 (c)\n"),
     "org.example:lib (floor 1.86): everything that asks for it already asks for 1.86 or later"),
    ("a newer version from a plugin still passes when another asks for less",
     self_test_report(lib="+--- org.example:lib:1.79 -> 1.90\n+--- org.example:lib:1.90\n"), None),
]


def self_test() -> None:
    """Checks every case in SELF_TEST_CASES against SELF_TEST_CATALOG, and the empty catalog."""
    failures = 0
    floors = read_floors(SELF_TEST_CATALOG)
    if floors != {"org.example:lib": "1.86", "org.example:other": "2.0.1"}:
        print(f"FAIL the catalog's floors are read: got {floors}")
        failures += 1
    for name, report, expected in SELF_TEST_CASES:
        problems, _, _ = check(read_report(report, floors), floors)
        if expected is None:
            passed = not problems
        else:
            passed = any(expected in problem for problem in problems)
        print(f"{'ok  ' if passed else 'FAIL'} {name}" + ("" if passed else f": {problems}"))
        failures += 0 if passed else 1
    problems, _, _ = check(read_report(self_test_report(), {}), {})
    empty = any("has no buildToolFloor-* library" in problem for problem in problems)
    print(f"{'ok  ' if empty else 'FAIL'} a catalog without floors fails")
    failures += 0 if empty else 1
    if failures:
        raise SystemExit(f"{failures} self-test case(s) failed")
    print(f"All {len(SELF_TEST_CASES) + 2} self-test cases passed.")


if __name__ == "__main__":
    main()
