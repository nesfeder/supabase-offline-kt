#!/usr/bin/env python3
"""Counts the tests a Gradle test task ran, per module, from its JUnit XML results.

Usage: count-test-results.py <heading> <test task> <module>...

Prints a Markdown table to the log and, on GitHub Actions, to the job summary. It only reports:
the Gradle step has already failed the job if a test failed.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from xml.etree import ElementTree

COUNTED = ("tests", "skipped", "failures", "errors")


def count_results(results_directory: Path) -> dict[str, int] | None:
    """Sums the counts of every suite file in a task's results directory, or None if there are none."""
    suite_files = sorted(results_directory.glob("*.xml"))
    if not suite_files:
        return None
    totals = dict.fromkeys(COUNTED, 0)
    for suite_file in suite_files:
        suite = ElementTree.parse(suite_file).getroot()
        for key in COUNTED:
            totals[key] += int(suite.get(key, "0"))
    return totals


def main() -> None:
    heading, task, *modules = sys.argv[1:]
    lines = [f"### {heading}", "", "| Module | Tests | Skipped | Failed |", "|---|---:|---:|---:|"]
    grand_total = dict.fromkeys(COUNTED, 0)
    for module in modules:
        totals = count_results(Path(module) / "build" / "test-results" / task)
        if totals is None:
            lines.append(f"| {module} | no results | | |")
            continue
        for key in COUNTED:
            grand_total[key] += totals[key]
        failed = totals["failures"] + totals["errors"]
        lines.append(f"| {module} | {totals['tests']} | {totals['skipped']} | {failed} |")
    failed = grand_total["failures"] + grand_total["errors"]
    lines.append(f"| **Total** | **{grand_total['tests']}** | {grand_total['skipped']} | {failed} |")

    report = "\n".join(lines) + "\n"
    print(report)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(report + "\n")


if __name__ == "__main__":
    main()
