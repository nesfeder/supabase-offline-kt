#!/usr/bin/env python3
"""Checks that the build-tool floors hold: no floored package resolves below its floor.

The floors are the ``buildToolFloor-*`` libraries in ``gradle/libs.versions.toml``: patched minimum
versions of libraries that the Gradle plugins and their tools put on the build's own classpaths.
The build applies them as dependency constraints to these configurations, which this script checks:

  * the root build script's ``classpath``, which holds every plugin (``build.gradle.kts``);
  * build-logic's ``precompiledScriptPluginAccessorsGenerationClasspath``, the plugins' full
    runtime that kotlin-dsl resolves (``build-logic/build.gradle.kts``);
  * every module's ``androidLintTool``, ``swiftExportClasspathResolvable``,
    ``kotlinBouncyCastleConfiguration``, ``dokkaHtmlGeneratorRuntimeResolver~internal`` and
    ``dokkaHtmlPublicationPluginResolver~internal``: the lint and Swift export tools, the Kotlin
    Gradle plugin's publishing validation, and Dokka's generator and publication plugins; the root
    project counts as a module here, for the Dokka generator and plugins of the site's API
    reference (``supabase-offline.api-reference``);
  * every configuration of a module whose name starts with ``unified-test-platform-``: the Android
    Gradle plugin's unified test platform, which an application module creates to run instrumented
    tests (both ``supabase-offline.build-tool-floors``, which every role plugin applies).

It takes the modules from Gradle's own ``projects`` report, asks Gradle for the resolved graphs (the
``buildEnvironment`` and ``dependencies`` reports) and fails when:

  * a floored package resolves below its floor, or fails to resolve, in one of them;
  * one of them is missing, or the root project or a module Gradle lists has no ``dependencies``
    report, so the floors are not known to reach it;
  * a floor resolves in none of them: a mistyped coordinate, or a package no plugin brings any more;
  * a floor raises nothing, because everything that asks for the package already asks for the floor
    or later: the plugin release that made the floor unnecessary has arrived, so remove it.

A pass means the floors hold, not that they are patched versions: the check knows the floors, not
the advisories. Each floor's comment in the catalog names its advisory and patched version, and the
dependency graph's security alerts are what catch a vulnerable version.

Usage (from the repository root):
  check-build-tool-floors.py                  run Gradle, then check
  check-build-tool-floors.py --report FILE    check a saved log of the same two Gradle commands
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
# A floor's alias in any of the forms Gradle treats as one (`-`, `_` or `.` after the prefix).
FLOOR_ALIAS = re.compile(r"^buildToolFloor[-_.]")

# Both commands print plain task headers (`> Task :path`), which is how the reports are told apart.
# The configuration cache is off because report tasks do not support it.
GRADLE = ["./gradlew", "--no-configuration-cache", "--console=plain"]
PROJECTS_COMMAND = GRADLE + [":projects"]


def reports_command(modules: list[str]) -> list[str]:
    """The Gradle command for the reports. A `dependencies` task named without a path runs in the
    root project only, so each module's is named, and the root project's as `:dependencies`."""
    return (GRADLE + [":buildEnvironment", ":build-logic:dependencies", f"{ROOT}:dependencies"]
            + [f"{module}:dependencies" for module in modules])


# (report task, configuration) pairs that must each be present.
REQUIRED = [
    (":buildEnvironment", "classpath"),
    (":build-logic:dependencies", "precompiledScriptPluginAccessorsGenerationClasspath"),
]
# The root project, as the prefix of its tasks' paths (`:dependencies`). It is checked as a module.
ROOT = ""
# Configurations checked in every module whose `dependencies` report has them. At least one module
# must have each.
PER_MODULE = ["androidLintTool", "swiftExportClasspathResolvable", "kotlinBouncyCastleConfiguration",
              "dokkaHtmlGeneratorRuntimeResolver~internal",
              "dokkaHtmlPublicationPluginResolver~internal"]
# Configurations checked by the start of their name, in every module that has them; at least one
# module must have one for each prefix. A later release's new configuration of the same tool is
# checked without a change here, as the convention plugin floors it without one.
PER_MODULE_PREFIXES = ["unified-test-platform-"]

TASK = re.compile(r"^> Task (?P<path>\S+)")
# A project in the `projects` report's hierarchy; its "Project locations" lines are lower case, and
# an included build is not a project of this build.
PROJECT = re.compile(r"^[| +\\-]*Project '(?P<path>:[^']+)'")
# A configuration's name may hold `-`, `.` or `~` (`unified-test-platform-core`, Dokka's `…~internal`).
CONFIGURATION = re.compile(r"^(?P<name>[A-Za-z_][\w~.-]*)( - .*| \(n\))?$")
NODE = re.compile(r"^[| ]*[+\\]--- (?P<body>.+)$")
MARKERS = re.compile(r"( \([cn*]\)| FAILED)+$")
LIBRARY = re.compile(r"^(?P<alias>[\w.-]+)\s*=\s*\{(?P<body>[^}]*)\}\s*(#.*)?$")
VERSION = re.compile(r'^(?P<name>[\w.-]+)\s*=\s*"(?P<value>[^"]+)"\s*(#.*)?$')


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


def normalized(alias: str) -> str:
    """A catalog alias as Gradle compares it: `-`, `_` and `.` are one separator."""
    return re.sub(r"[-_.]", ".", alias)


def read_floors(text: str) -> dict[str, str]:
    """The floors in a version catalog, as ``group:module`` to the minimum version."""
    versions: dict[str, str] = {}
    entries: list[tuple[str, str]] = []
    section = ""
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            section = stripped.split("#")[0].strip()
            continue
        if section == "[versions]":
            match = VERSION.match(stripped)
            if match:
                versions[normalized(match.group("name"))] = match.group("value")
        elif section == "[libraries]":
            match = LIBRARY.match(stripped)
            if match and FLOOR_ALIAS.match(match.group("alias")):
                entries.append((match.group("alias"), match.group("body")))
    floors: dict[str, str] = {}
    for alias, body in entries:
        module = re.search(r'module\s*=\s*"([^"]+)"', body)
        reference = re.search(r'version\.ref\s*=\s*"([^"]+)"', body)
        literal = re.search(r'version\s*=\s*"([^"]+)"', body)
        version = literal.group(1) if literal else None
        if reference:
            version = versions.get(normalized(reference.group(1)))
        if not module or not version:
            raise SystemExit(f"{alias}: a floor needs a module and a version")
        floors[module.group(1)] = version
    return floors


def read_projects(text: str) -> list[str]:
    """The projects Gradle's `projects` report lists in this build, in order."""
    projects: list[str] = []
    task = None
    for line in text.splitlines():
        task_match = TASK.match(line)
        if task_match:
            task = task_match.group("path")
            continue
        match = PROJECT.match(line)
        if task == ":projects" and match and match.group("path") not in projects:
            projects.append(match.group("path"))
    return projects


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


def reported_tasks(text: str) -> set[str]:
    """The paths of every task that ran, from the plain task headers."""
    return {match.group("path") for match in map(TASK.match, text.splitlines()) if match}


def checked_configurations(reports: dict[str, dict[str, Resolution]],
                           modules: list[str], tasks: set[str]) -> tuple[list[str], list[str]]:
    """The configurations the floors must hold in, and what is missing from the report."""
    checked, missing = [], []
    for task, name in REQUIRED:
        key = f"{task} {name}"
        (checked if key in reports else missing).append(key)
    if not modules:
        missing.append("the modules: Gradle's projects report lists none")
    modules = [ROOT] + modules
    missing.extend(f"{module}:dependencies ({'a module Gradle lists' if module else 'the root project'})"
                   for module in modules if f"{module}:dependencies" not in tasks)
    for name in PER_MODULE:
        found = [f"{module}:dependencies {name}" for module in modules
                 if f"{module}:dependencies {name}" in reports]
        checked.extend(found)
        if not found:
            missing.append(f"<every module>:dependencies {name}")
    for prefix in PER_MODULE_PREFIXES:
        found = sorted(key for module in modules for key in reports
                       if key.startswith(f"{module}:dependencies {prefix}"))
        checked.extend(found)
        if not found:
            missing.append(f"<every module>:dependencies {prefix}*")
    return checked, missing


def check(text: str, floors: dict[str, str]) -> tuple[list[str], list[str], list[str]]:
    """The problems, a table of each floored package in each checked configuration, and those."""
    problems: list[str] = []
    table: list[str] = []
    if not floors:
        problems.append(f"{CATALOG} has no buildToolFloor-* library, so there is nothing to check")
    reports = read_report(text, floors)
    checked, missing = checked_configurations(reports, read_projects(text), reported_tasks(text))
    problems.extend(f"{key}: not in the Gradle report, so no floor is known to reach it"
                    for key in missing)
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
            if resolution.requested_unknown or any(below(version, floor)
                                                   for version in resolution.requested):
                raises = True
        if not raises:
            problems.append(f"{package} (floor {floor}): everything that asks for it already asks "
                            f"for {floor} or later, so the floor is no longer needed; remove it")
    return problems, table, checked


def run_gradle(command: list[str]) -> str:
    """Runs one Gradle command and returns its output; stops the check if Gradle fails."""
    print("$ " + " ".join(command), flush=True)
    run = subprocess.run(command, capture_output=True, text=True)
    if run.returncode != 0:
        sys.stdout.write(run.stdout[-4000:] + run.stderr[-4000:])
        raise SystemExit(f"Gradle failed ({run.returncode}), so nothing was checked")
    return run.stdout


def main() -> None:
    """Parses the arguments, then runs the self-test or the check."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--report", help="a saved log of the Gradle commands, instead of running them")
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
        projects = run_gradle(PROJECTS_COMMAND)
        text = projects + run_gradle(reports_command(read_projects(projects)))
    problems, table, checked = check(text, floors)
    print(f"{'package':45} {'floor':9} {'asked for':>9} -> resolved  configuration")
    print("\n".join(table))
    for problem in problems:
        print(f"PROBLEM {problem}")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write("### Build-tool floors\n\n")
            summary.write(f"{len(floors)} floors, {len(checked)} configurations, "
                          f"{len(problems)} problem(s)\n")
    if problems:
        print(f"::error::The build-tool floors have {len(problems)} problem(s); see the log.")
        sys.exit(1)
    print(f"Every floor holds: {len(floors)} floors, {len(checked)} configurations. That says "
          "nothing resolves below them; each floor's catalog comment names its advisory.")


SELF_TEST_CATALOG = """[versions]
buildToolFloor-lib = "1.86"  # a comment
other = "1.0"

[libraries]
buildToolFloor-lib = { module = "org.example:lib", version.ref = "buildToolFloor-lib" }
buildToolFloor_other = { module = "org.example:other", version = "4.5.14" }  # a comment
unrelated = { module = "org.example:unrelated", version.ref = "other" }
"""

LIB_OK = "+--- org.example:lib:1.79 -> 1.86\n+--- org.example:lib:1.86 (c)\n"
OTHER_OK = "|    \\--- org.example:other:{strictly 4.5} -> 4.5.14\n"
MODULES = [":module-a", ":module-b"]


def self_test_report(lib: str = LIB_OK, other: str = OTHER_OK, lint: bool = True,
                     accessors: bool = True, swift: str | None = None, root: str | None = None,
                     second_lint: str | None = None, projects: list[str] | None = None,
                     dokka: str | None = None, test_platform: bool = True,
                     result_listener: str | None = None, test_platform_core: str | None = None,
                     dotted: str | None = None, root_dokka: str | None = None,
                     publication: str | None = None, root_publication: str | None = None,
                     root_report: bool = True) -> str:
    """A log of the two checked commands: every module listed, then every report.

    `lib` and `other` are the graphs of the two floored packages everywhere; the other arguments
    replace one configuration's graph, drop a report, or change the module list. The second module
    is an application's: it alone has configurations of the unified test platform, one of them with
    dots in its name. The root project's report holds Dokka's generator and publication plugins
    alone.
    """
    listed = MODULES if projects is None else projects
    hierarchy = "".join(f"+--- Project '{module}'\n" for module in listed)
    locations = "".join(f"project '{module}' - /{module[1:]}\n" for module in listed)
    sections = [
        "> Task :projects\n\nRoot project 'root'\n\nProject hierarchy:\n\nRoot project 'root'\n"
        f"{hierarchy}\nProject locations:\n\n{locations}\nIncluded builds:\n\n"
        "\\--- Included build ':build-logic'\n",
        "> Task :build-logic:compileKotlin\nw: a compiler warning\n",
        "> Task :buildEnvironment\nDaemon JVM: a JDK\n\nRoot project 'root'\n\nclasspath\n"
        + (f"{lib}{other}" if root is None else root),
    ]
    if root_report:
        sections.append(
            "> Task :dependencies\n\nRoot project 'root'\n\n"
            "dokkaHtmlGeneratorRuntimeResolver~internal - [Internal Dokka Configuration] Dokka.\n"
            f"{other if root_dokka is None else root_dokka}\n"
            "dokkaHtmlPublicationPluginResolver~internal - [Internal Dokka Configuration] Dokka.\n"
            f"{lib if root_publication is None else root_publication}\n")
    for index, module in enumerate(MODULES):
        lint_graph = lib if index == 0 or second_lint is None else second_lint
        sections.append(
            f"> Task {module}:dependencies\n\nProject '{module}'\n\n"
            + (f"androidLintTool\n{lint_graph}\n" if lint else "")
            + "androidLintToolDeclarable (n)\nNo dependencies\n\n"
            + f"swiftExportClasspathResolvable\n{other if swift is None else swift}\n"
            + f"kotlinBouncyCastleConfiguration - Bouncy Castle.\n{lib}\n"
            + "dokkaHtmlGeneratorRuntimeResolver~internal - [Internal Dokka Configuration] Dokka.\n"
            + f"{other if dokka is None else dokka}\n"
            + "dokkaHtmlPublicationPluginResolver~internal - [Internal Dokka Configuration] Dokka.\n"
            + f"{lib if publication is None else publication}\n"
            + ("" if index == 0 or not test_platform else
               "unified-test-platform-core - A configuration to resolve the Unified Test Platform.\n"
               "\\--- com.example:core:1.0\n     "
               f"{other if test_platform_core is None else test_platform_core}\n"
               "unified-test-platform-android-test-plugin-result-listener-gradle - The same.\n"
               f"{lib if result_listener is None else result_listener}\n"
               "unified-test-platform-plugin.with.dots - The same.\n"
               f"{lib if dotted is None else dotted}\n"))
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
    ("a package below its floor in the Swift export configurations only",
     self_test_report(swift="\\--- org.example:other:{strictly 4.5} -> 4.5.13\n"),
     ":module-a:dependencies swiftExportClasspathResolvable: org.example:other resolves to 4.5.13"),
    ("a package below its floor in Dokka's generator only",
     self_test_report(dokka="\\--- org.example:other:4.5.6\n"),
     ":module-a:dependencies dokkaHtmlGeneratorRuntimeResolver~internal: org.example:other resolves"
     " to 4.5.6"),
    ("a package below its floor in one unified test platform configuration only",
     self_test_report(result_listener="+--- org.example:lib:1.79\n"),
     ":module-b:dependencies unified-test-platform-android-test-plugin-result-listener-gradle: "
     "org.example:lib resolves to 1.79"),
    ("a package below its floor in another unified test platform configuration only",
     self_test_report(test_platform_core="\\--- org.example:other:4.5.6\n"),
     ":module-b:dependencies unified-test-platform-core: org.example:other resolves to 4.5.6"),
    ("a package below its floor in a configuration with dots in its name",
     self_test_report(dotted="+--- org.example:lib:1.79\n"),
     ":module-b:dependencies unified-test-platform-plugin.with.dots: org.example:lib resolves to 1.79"),
    ("no module with a unified test platform configuration",
     self_test_report(test_platform=False),
     "<every module>:dependencies unified-test-platform-*: not in the Gradle report"),
    ("a package below its floor in the root build script's classpath only",
     self_test_report(root="+--- org.example:lib:1.79\n" + OTHER_OK),
     ":buildEnvironment classpath: org.example:lib resolves to 1.79"),
    ("a package below its floor in the root project's Dokka generator only",
     self_test_report(root_dokka="\\--- org.example:other:4.5.6\n"),
     ":dependencies dokkaHtmlGeneratorRuntimeResolver~internal: org.example:other resolves to 4.5.6"),
    ("a package below its floor in Dokka's publication plugins only",
     self_test_report(publication="+--- org.example:lib:1.79\n"),
     ":module-a:dependencies dokkaHtmlPublicationPluginResolver~internal: org.example:lib resolves"
     " to 1.79"),
    ("a package below its floor in the root project's Dokka publication plugins only",
     self_test_report(root_publication="+--- org.example:lib:1.79\n"),
     ":dependencies dokkaHtmlPublicationPluginResolver~internal: org.example:lib resolves to 1.79"),
    ("the root project without a report",
     self_test_report(root_report=False),
     ":dependencies (the root project): not in the Gradle report"),
    ("a package below its floor in the second module only",
     self_test_report(second_lint="+--- org.example:lib:1.79\n"),
     ":module-b:dependencies androidLintTool: org.example:lib resolves to 1.79"),
    ("versions compare as numbers, 4.5.6 below 4.5.14",
     self_test_report(other="\\--- org.example:other:4.5.6\n"),
     "org.example:other resolves to 4.5.6, below its floor 4.5.14"),
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
    ("a module Gradle lists without a report",
     self_test_report(projects=MODULES + [":module-c"]),
     ":module-c:dependencies (a module Gradle lists): not in the Gradle report"),
    ("no module listed",
     self_test_report(projects=[]),
     "the modules: Gradle's projects report lists none"),
    ("a floor nothing resolves",
     self_test_report(other=""),
     "org.example:other (floor 4.5.14) resolves in no"),
    ("a floor that raises nothing",
     self_test_report(lib="+--- org.example:lib:1.86\n"),
     "org.example:lib (floor 1.86): everything that asks for it already asks for 1.86 or later"),
    ("a constraint is not a request",
     self_test_report(lib="+--- org.example:lib:1.86\n+--- org.example:lib:1.79 -> 1.86 (c)\n"),
     "org.example:lib (floor 1.86): everything that asks for it already asks for 1.86 or later"),
    ("a newer version from a plugin still passes when another asks for less",
     self_test_report(lib="+--- org.example:lib:1.79 -> 1.90\n+--- org.example:lib:1.90\n"), None),
]

# Gradle's projects report for a nested project and an included build, with the lower-case
# locations section that must not add the project twice.
PROJECTS_REPORT = """> Task :projects

Project hierarchy:

Root project 'root'
+--- Project ':a' - A description
\\--- Project ':samples'
     \\--- Project ':samples:app'

Project locations:

project ':a' - /a
project ':samples:app' - /samples/app

Included builds:

\\--- Included build ':build-logic'
"""


def self_test() -> None:
    """Checks every case in SELF_TEST_CASES against SELF_TEST_CATALOG, and the parsers alone."""
    failures = 0
    floors = read_floors(SELF_TEST_CATALOG)
    parsed = [
        ("the catalog's floors, with `_` aliases and trailing comments", floors,
         {"org.example:lib": "1.86", "org.example:other": "4.5.14"}),
        ("the projects report, nested projects included and included builds left out",
         read_projects(PROJECTS_REPORT), [":a", ":samples", ":samples:app"]),
    ]
    for name, actual, expected in parsed:
        passed = actual == expected
        print(f"{'ok  ' if passed else 'FAIL'} {name}" + ("" if passed else f": got {actual}"))
        failures += 0 if passed else 1
    for name, report, expected_problem in SELF_TEST_CASES:
        problems, _, _ = check(report, floors)
        if expected_problem is None:
            passed = not problems
        else:
            passed = any(expected_problem in problem for problem in problems)
        print(f"{'ok  ' if passed else 'FAIL'} {name}" + ("" if passed else f": {problems}"))
        failures += 0 if passed else 1
    problems, _, _ = check(self_test_report(), {})
    empty = any("has no buildToolFloor-* library" in problem for problem in problems)
    print(f"{'ok  ' if empty else 'FAIL'} a catalog without floors fails")
    failures += 0 if empty else 1
    if failures:
        raise SystemExit(f"{failures} self-test case(s) failed")
    print(f"All {len(SELF_TEST_CASES) + len(parsed) + 1} self-test cases passed.")


if __name__ == "__main__":
    main()
