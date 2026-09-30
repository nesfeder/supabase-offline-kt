#!/usr/bin/env python3
"""Checks a Kover XML report before it goes to Codecov: it measured something, and names real files.

Usage (from the repository root):
  check-coverage-report.py <report.xml>   check the report against the files git tracks
  check-coverage-report.py --self-test    prove the check catches each kind of bad report

It fails when the report cannot be read, lists no source file, covers no line, or names a file
(<package>/<source file>) that ends no tracked path or more than one. Codecov resolves a report's
files by the end of their path in the same way, so a file this check cannot resolve would be
dropped from the dashboard. Whether the figure is high enough is koverVerify's job, not this one.
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path
from xml.etree import ElementTree


def report_problems(report: Path, tracked: list[str]) -> tuple[list[str], list[str]]:
    """Returns what is wrong with the report, and a summary of it for the log."""
    try:
        root = ElementTree.parse(report).getroot()
    except (OSError, ElementTree.ParseError) as error:
        return [f"{report} cannot be read: {error}"], []
    source_files = [f"{package.get('name')}/{source.get('name')}"
                    for package in root.iter("package") for source in package.findall("sourcefile")]
    counters = {counter.get("type"): (int(counter.get("missed", "0")),
                                      int(counter.get("covered", "0")))
                for counter in root.findall("counter")}
    problems = []
    if not source_files:
        problems.append("the report lists no source file")
    missed, covered = counters.get("LINE", (0, 0))
    if covered == 0:
        problems.append(f"the report covers no line ({missed} missed): the coverage data never"
                        " reached it")
    for name in source_files:
        matches = [path for path in tracked if path == name or path.endswith("/" + name)]
        if not matches:
            problems.append(f"{name} ends no tracked path")
        elif len(matches) > 1:
            problems.append(f"{name} ends {len(matches)} tracked paths: {', '.join(matches)}")
    branch_missed, branch_covered = counters.get("BRANCH", (0, 0))
    resolved = ", each resolved to one tracked path" if not problems else ""
    summary = [f"{len(source_files)} source files{resolved}",
               f"lines: {covered} of {missed + covered} covered",
               f"branches: {branch_covered} of {branch_missed + branch_covered} covered"]
    return problems, summary


def main() -> None:
    """Runs the self-test, or checks the report named on the command line."""
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return
    if len(sys.argv) != 2:
        raise SystemExit("usage: check-coverage-report.py <report.xml> | --self-test")
    tracked = subprocess.run(["git", "ls-files"], check=True, capture_output=True,
                             text=True).stdout.splitlines()
    problems, summary = report_problems(Path(sys.argv[1]), tracked)
    for line in summary:
        print(f"  {line}")
    for problem in problems:
        print(f"  PROBLEM {problem}")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path and summary:
        with open(summary_path, "a", encoding="utf-8") as job_summary:
            job_summary.write("### Coverage report\n\n")
            job_summary.write("".join(f"- {line}\n" for line in summary))
    if problems:
        print(f"::error::The coverage report has {len(problems)} problem(s); see the log.")
        sys.exit(1)


PACKAGE = "io/github/example"
TRACKED = [f"lib/src/commonMain/kotlin/{PACKAGE}/Client.kt",
           f"lib/src/androidMain/kotlin/{PACKAGE}/Client.android.kt",
           f"lib/src/commonMain/kotlin/{PACKAGE}/Shared.kt",
           f"lib/src/iosMain/kotlin/{PACKAGE}/Shared.kt"]


def report_xml(files: list[str], missed: int = 3, covered: int = 7) -> str:
    """A Kover-shaped report of the given source files and root line counter."""
    sources = "".join(f'<sourcefile name="{name}"><counter type="LINE" missed="0" covered="1"/>'
                      "</sourcefile>" for name in files)
    return (f'<?xml version="1.0" ?><report name="self-test"><package name="{PACKAGE}">{sources}'
            f'</package><counter type="LINE" missed="{missed}" covered="{covered}"/>'
            '<counter type="BRANCH" missed="1" covered="1"/></report>')


# Each case: what it proves, the report's text (None for no file), and a phrase the problems must
# contain, or None when the report must pass.
CASES: list[tuple[str, str | None, str | None]] = [
    ("a good report", report_xml(["Client.kt", "Client.android.kt"]), None),
    ("no report file", None, "cannot be read"),
    ("not XML", "coverage: 94%", "cannot be read"),
    ("no source file", report_xml([]), "lists no source file"),
    ("no covered line, as Kover 0.9.10 wrote under the configuration cache",
     report_xml(["Client.kt"], missed=10, covered=0), "covers no line"),
    ("a generated file git does not track", report_xml(["Client.kt", "Database_Impl.kt"]),
     "Database_Impl.kt ends no tracked path"),
    ("a name two tracked files end with", report_xml(["Shared.kt"]), "ends 2 tracked paths"),
]


def self_test() -> None:
    """Checks every case in CASES against the TRACKED file list."""
    failures = 0
    with tempfile.TemporaryDirectory() as work:
        for index, (name, text, expected) in enumerate(CASES):
            report = Path(work) / f"report-{index}.xml"
            if text is not None:
                report.write_text(text, encoding="utf-8")
            problems, _ = report_problems(report, TRACKED)
            if expected is None:
                ok = not problems
            else:
                ok = any(expected in problem for problem in problems)
            failures += not ok
            print(f"{'ok  ' if ok else 'FAIL'} {name}: {problems or 'passes'}")
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s).")
        sys.exit(1)
    print(f"SELF-TEST PASSED: {len(CASES)} cases.")


if __name__ == "__main__":
    main()
