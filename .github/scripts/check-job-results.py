#!/usr/bin/env python3
"""Checks every CI job's result against what the changes job planned for this run.

Usage:
  check-job-results.py              read the needs context from $NEEDS (JSON), print the table
                                    to the log and the job summary, and exit 1 on any mismatch
  check-job-results.py --self-test  check the comparison against a table of runs

A job that was due must have succeeded; a job that was not due must have been skipped. Anything
else fails, and so does an empty or malformed plan.
"""

from __future__ import annotations

import json
import os
import sys
from typing import Callable

Plan = dict[str, object]

# Every job ci.yml's final-status needs, and when it is due. Adding a job to ci.yml without adding
# it here fails the run: an unknown job is a mismatch.
DUE: dict[str, Callable[[Plan], bool]] = {
    "changes": lambda plan: True,
    "formatting": lambda plan: bool(plan["formatting"]),
    "static-checks": lambda plan: bool(plan["kotlin"] or plan["workflows"]),
    "jvm-tests": lambda plan: bool(plan["kotlin"]),
    "ios-tests": lambda plan: bool(plan["kotlin"] and plan["ios-allowed"]),
    "docs": lambda plan: bool(plan["docs"]),
}
BOOLEAN_OUTPUTS = ("kotlin", "docs", "workflows", "formatting", "ios-allowed", "ios-held")
RUNNER_NAMES = {"local": "the self-hosted runner (label offline-kt)",
                "hosted": "GitHub's macos-26 runner"}


def read_plan(outputs: object) -> tuple[Plan | None, list[str]]:
    """Parses the changes job's outputs into a plan, or returns why they are unusable."""
    if not isinstance(outputs, dict):
        return None, ["the changes job left no outputs"]
    problems = []
    plan: Plan = {}
    for key in BOOLEAN_OUTPUTS:
        value = outputs.get(key)
        if value not in ("true", "false"):
            problems.append(f"output {key} is {value!r}, not true or false")
        plan[key] = value == "true"
    runner = outputs.get("ios-runner")
    if runner not in RUNNER_NAMES:
        problems.append(f"output ios-runner is {runner!r}, not local or hosted")
    plan["ios-runner"] = runner
    plan["ios-hold"] = outputs.get("ios-hold") or ""
    if not problems:
        if plan["ios-held"] != (plan["kotlin"] and not plan["ios-allowed"]):
            problems.append("output ios-held disagrees with kotlin and ios-allowed")
        if plan["ios-allowed"] == bool(plan["ios-hold"]):
            problems.append("output ios-hold disagrees with ios-allowed")
    return (None if problems else plan), problems


def check(needs: dict) -> tuple[bool, list[str]]:
    """Compares each job's result with the plan; returns whether it passed and the report."""
    lines = ["### Jobs against the plan", "",
             "| Job | Planned | Result | Verdict |", "|---|---|---|---|"]
    changes = needs.get("changes") or {}
    plan, problems = read_plan(changes.get("outputs"))
    passed = not problems
    unknown = sorted(set(needs) - set(DUE))
    missing = sorted(set(DUE) - set(needs))
    if unknown:
        problems.append(f"jobs with no plan: {', '.join(unknown)}")
    if missing:
        problems.append(f"planned jobs missing from needs: {', '.join(missing)}")
    passed = passed and not unknown and not missing

    for job in sorted(set(needs) | set(DUE), key=lambda name: list(DUE).index(name)
                      if name in DUE else len(DUE)):
        result = (needs.get(job) or {}).get("result", "absent")
        if job not in DUE or plan is None:
            due_text, expected = "unknown", None
        else:
            due = DUE[job](plan)
            held = job == "ios-tests" and plan["ios-held"]
            due_text = "due" if due else ("held" if held else "not due")
            expected = "success" if due else "skipped"
        ok = expected is not None and result == expected
        passed = passed and ok
        verdict = "ok" if ok else f"**expected {expected or 'a plan'}**"
        lines.append(f"| {job} | {due_text} | {result} | {verdict} |")

    lines.append("")
    if plan is not None:
        if plan["kotlin"]:
            where = RUNNER_NAMES[str(plan["ios-runner"])]
            lines.append(f"iOS tests: {'ran on ' + where if plan['ios-allowed'] else 'held'}.")
        if plan["ios-held"]:
            lines.append(f"**The iOS tests were due and held ({plan['ios-hold']}).** This check is"
                         " named apart from `Final status`, so it cannot satisfy the required"
                         " check.")
    lines.extend(f"- {problem}" for problem in problems)
    return passed, lines


def main() -> None:
    """Runs the self-test, or checks the run described by $NEEDS."""
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return
    if sys.argv[1:]:
        raise SystemExit("usage: check-job-results.py [--self-test]")
    try:
        needs = json.loads(os.environ.get("NEEDS", ""))
    except json.JSONDecodeError:
        needs = None
    if not isinstance(needs, dict):
        print("::error::NEEDS is not a JSON object")
        sys.exit(1)
    passed, lines = check(needs)
    report = "\n".join(lines) + "\n"
    print("\n".join("  " + line for line in lines))
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(report)
    plan, _ = read_plan((needs.get("changes") or {}).get("outputs"))
    if plan is not None and plan["ios-held"]:
        print(f"::warning title=iOS tests not run::The iOS tests were due and held"
              f" ({plan['ios-hold']}). This run's fan-in is not the required Final status.")
    if not passed:
        print("::error::A job's result does not match the plan; see the table above.")
        sys.exit(1)


def outputs(**overrides: str) -> dict[str, str]:
    """A well-formed changes output for the self-test, with some values replaced."""
    base = {"kotlin": "true", "docs": "true", "workflows": "true", "formatting": "false",
            "ios-runner": "hosted", "ios-allowed": "true", "ios-hold": "", "ios-held": "false"}
    base.update(overrides)
    return base


def needs_of(plan_outputs: dict | None, **results: str) -> dict:
    """A needs context for the self-test: every job succeeded unless given another result."""
    base = {job: {"result": "success", "outputs": {}} for job in DUE}
    base["formatting"]["result"] = "skipped"
    base["changes"]["outputs"] = plan_outputs
    for job, result in results.items():
        job = job.replace("_", "-")
        if result == "absent":
            base.pop(job)
        else:
            base.setdefault(job, {"outputs": {}})["result"] = result
    return base


HELD = outputs(**{"ios-allowed": "false", "ios-hold": "draft pull request", "ios-held": "true"})
DOCS_ONLY = outputs(kotlin="false", workflows="false")
CASES: list[tuple[str, dict, bool]] = [
    ("everything due and green", needs_of(outputs()), True),
    ("formatting configured and green", needs_of(outputs(formatting="true"), formatting="success"),
     True),
    ("docs-only change: tests skipped", needs_of(DOCS_ONLY, static_checks="skipped",
     jvm_tests="skipped", ios_tests="skipped"), True),
    ("iOS held on a draft, skipped", needs_of(HELD, ios_tests="skipped"), True),
    ("iOS on the self-hosted runner, green", needs_of(outputs(**{"ios-runner": "local"})), True),
    ("iOS due but skipped", needs_of(outputs(), ios_tests="skipped"), False),
    ("iOS held but ran", needs_of(HELD), False),
    ("a test job failed", needs_of(outputs(), jvm_tests="failure"), False),
    ("a job was cancelled", needs_of(outputs(), ios_tests="cancelled"), False),
    ("formatting due but skipped", needs_of(outputs(formatting="true")), False),
    ("docs not due but ran", needs_of(outputs(docs="false")), False),
    ("static checks not due but ran", needs_of(DOCS_ONLY, jvm_tests="skipped",
     ios_tests="skipped"), False),
    ("docs due but skipped", needs_of(outputs(), docs="skipped"), False),
    ("changes failed, no outputs", needs_of(None, changes="failure", static_checks="skipped",
     jvm_tests="skipped", ios_tests="skipped", docs="skipped"), False),
    ("empty outputs, everything skipped", needs_of({}, static_checks="skipped",
     jvm_tests="skipped", ios_tests="skipped", docs="skipped"), False),
    ("malformed boolean", needs_of(outputs(kotlin="yes")), False),
    ("unknown runner", needs_of(outputs(**{"ios-runner": "mac"})), False),
    ("held disagrees with allowed", needs_of(outputs(**{"ios-held": "true"})), False),
    ("hold reason without a hold", needs_of(outputs(**{"ios-hold": "draft pull request"})),
     False),
    ("a job missing from needs", needs_of(outputs(), docs="absent"), False),
    ("a job with no plan", needs_of(outputs(), swift_package="success"), False),
]


def self_test() -> None:
    """Checks the comparison against every case in CASES."""
    failures = 0
    for name, needs, expected in CASES:
        passed, lines = check(needs)
        ok = passed == expected
        failures += not ok
        print(f"{'ok  ' if ok else 'FAIL'} {name}: {'passes' if passed else 'fails'}")
        if not ok:
            print("\n".join("     " + line for line in lines))
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s).")
        sys.exit(1)
    print(f"SELF-TEST PASSED: {len(CASES)} cases.")


if __name__ == "__main__":
    main()
