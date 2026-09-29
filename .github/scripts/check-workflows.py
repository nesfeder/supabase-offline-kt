#!/usr/bin/env python3
"""Checks the structure of this repository's workflows that no linter checks.

Usage (from the repository root; needs mikefarah yq on the PATH to read YAML):
  check-workflows.py              check .github/
  check-workflows.py --self-test  prove the check catches each kind of violation

It fails when:
  1. a workflow triggers on pull_request_target or workflow_run;
  2. a workflow triggers on push without a literal branch or tag filter, so Dependabot's own
     branches would start it;
  3. any job but ci.yml's ios-tests names the self-hosted runner, or runs on anything but a
     literal GitHub-hosted label; ios-tests must fall back to a hosted label;
  4. ci.yml's final-status does not need every other job, or does not run with always();
  5. a `uses: $/...` reference names an action or workflow that does not exist.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

LOCAL_RUNNER_LABEL = re.compile(r"(?<![A-Za-z0-9_-])(offline-kt|self-hosted)(?![A-Za-z0-9_-])")
HOSTED_LABEL = re.compile(r"(ubuntu|macos|windows)-[A-Za-z0-9.-]+")
ROUTED_JOB = ("ci.yml", "ios-tests")
FAN_IN = ("ci.yml", "final-status")


def load_yaml(path: Path) -> object:
    """Reads a YAML file through yq, which keeps the `on` key a string."""
    converted = subprocess.run(["yq", "-o=json", ".", str(path)], check=True,
                               capture_output=True, text=True)
    return json.loads(converted.stdout)


def trigger_problems(name: str, triggers: object) -> list[str]:
    """Rules 1 and 2."""
    if isinstance(triggers, str):
        triggers = {triggers: None}
    elif isinstance(triggers, list):
        triggers = {trigger: None for trigger in triggers}
    if not isinstance(triggers, dict):
        return [f"{name}: `on` is not a string, list or mapping"]
    problems = [f"{name}: triggers on {event}" for event in ("pull_request_target", "workflow_run")
                if event in triggers]
    if "push" in triggers:
        push = triggers["push"]
        branches = push.get("branches") if isinstance(push, dict) else None
        tags = push.get("tags") if isinstance(push, dict) else None
        literal = isinstance(branches, list) and branches and all(
            isinstance(branch, str) and not re.search(r"[*?\[!]", branch) for branch in branches)
        if isinstance(push, dict) and "branches-ignore" in push:
            problems.append(f"{name}: push uses branches-ignore")
        elif not literal and not (branches is None and tags):
            problems.append(f"{name}: push is not filtered to literal branches or to tags")
    return problems


def runner_problems(name: str, job_id: str, job: dict) -> list[str]:
    """Rule 3."""
    if "uses" in job:
        return []
    runs_on = job.get("runs-on")
    if (name, job_id) == ROUTED_JOB:
        if not (isinstance(runs_on, str)
                and "needs.changes.outputs.ios-runner == 'local'" in runs_on
                and re.search(r"\|\|\s*'(macos-[A-Za-z0-9.-]+)'\s*}}\s*$", runs_on)):
            return [f"{name}: {job_id} must route to the Mac only when ios-runner is local, and"
                    " fall back to a hosted macOS label"]
        return []
    problems = []
    if LOCAL_RUNNER_LABEL.search(json.dumps(job)):
        problems.append(f"{name}: job {job_id} names the self-hosted runner")
    if not (isinstance(runs_on, str) and HOSTED_LABEL.fullmatch(runs_on)):
        problems.append(f"{name}: job {job_id} runs on {runs_on!r}, not a literal hosted label")
    return problems


def fan_in_problems(name: str, jobs: dict) -> list[str]:
    """Rule 4."""
    if name != FAN_IN[0]:
        return []
    fan_in = jobs.get(FAN_IN[1])
    if not isinstance(fan_in, dict):
        return [f"{name}: no {FAN_IN[1]} job"]
    needs = fan_in.get("needs")
    needs = [needs] if isinstance(needs, str) else (needs or [])
    others = set(jobs) - {FAN_IN[1]}
    problems = []
    if set(needs) != others:
        problems.append(f"{name}: {FAN_IN[1]} needs {sorted(needs)}, not every other job"
                        f" {sorted(others)}")
    condition = str(fan_in.get("if", "")).strip()
    if condition.startswith("${{"):
        condition = condition[3:].strip()
    if not condition.startswith("always()"):
        problems.append(f"{name}: {FAN_IN[1]} does not run with always()")
    return problems


def self_reference_problems(root: Path, name: str, document: object) -> list[str]:
    """Rule 5: every `$/` reference in the document names a path that exists."""
    problems = []

    def visit_steps(node: object) -> None:
        if isinstance(node, dict):
            uses = node.get("uses")
            if isinstance(uses, str) and uses.startswith("$/"):
                target = root / uses[2:]
                if not ((target / "action.yml").is_file() or (target / "action.yaml").is_file()):
                    problems.append(f"{name}: `uses: {uses}` names nothing in the repository")
            for value in node.values():
                visit_steps(value)
        elif isinstance(node, list):
            for item in node:
                visit_steps(item)

    jobs = document.get("jobs") if isinstance(document, dict) else None
    for job in (jobs or {}).values():
        if isinstance(job, dict):
            uses = job.get("uses")
            if isinstance(uses, str) and uses.startswith("$/") and not (root / uses[2:]).is_file():
                problems.append(f"{name}: `uses: {uses}` names nothing in the repository")
            visit_steps(job.get("steps"))
    runs = document.get("runs") if isinstance(document, dict) else None
    if isinstance(runs, dict):
        visit_steps(runs.get("steps"))
    return problems


def check_tree(root: Path) -> list[str]:
    """Runs every rule over the workflows and actions under root/.github."""
    problems = []
    for path in sorted((root / ".github" / "workflows").glob("*.y*ml")):
        document = load_yaml(path)
        if not isinstance(document, dict):
            problems.append(f"{path.name}: not a mapping")
            continue
        problems += trigger_problems(path.name, document.get("on"))
        jobs = document.get("jobs") or {}
        for job_id, job in jobs.items():
            problems += runner_problems(path.name, job_id, job if isinstance(job, dict) else {})
        problems += fan_in_problems(path.name, jobs)
        problems += self_reference_problems(root, path.name, document)
    for path in sorted((root / ".github" / "actions").glob("*/action.y*ml")):
        name = str(path.relative_to(root))
        document = load_yaml(path)
        if LOCAL_RUNNER_LABEL.search(json.dumps(document)):
            problems.append(f"{name}: names the self-hosted runner")
        problems += self_reference_problems(root, name, document)
    return problems


COMPLIANT_CI = """\
on:
  pull_request:
  push:
    branches: [main]
  merge_group:
jobs:
  changes: {runs-on: ubuntu-slim, steps: [{uses: $/.github/actions/setup}]}
  ios-tests:
    needs: changes
    runs-on: >-
      ${{ needs.changes.outputs.ios-runner == 'local'
      && fromJSON('["self-hosted", "offline-kt"]') || 'macos-26' }}
    steps: [{run: echo}]
  docs: {needs: changes, uses: $/.github/workflows/docs-check.yml}
  final-status:
    if: always() && github.event.action != 'labeled'
    needs: [changes, ios-tests, docs]
    runs-on: ubuntu-slim
    steps: [{run: echo}]
"""
COMPLIANT_DOCS = """\
on:
  workflow_call:
  push:
    tags: ['v*']
jobs:
  check: {runs-on: ubuntu-latest, steps: [{run: echo supabase-offline-kt}]}
"""
COMPLIANT_ACTION = "runs: {using: composite, steps: [{run: echo, shell: bash}]}\n"

# Each case: what it proves, the file it replaces or adds, its content, and a phrase the report
# must contain.
CASES: list[tuple[str, str, str, str]] = [
    ("pull_request_target", "extra.yml",
     "on: pull_request_target\njobs: {a: {runs-on: ubuntu-latest}}\n", "pull_request_target"),
    ("workflow_run", "extra.yml",
     "on: {workflow_run: {workflows: [CI]}}\njobs: {a: {runs-on: ubuntu-latest}}\n",
     "workflow_run"),
    ("unfiltered push", "extra.yml", "on: push\njobs: {a: {runs-on: ubuntu-latest}}\n",
     "push is not filtered"),
    ("push with a branch glob", "extra.yml",
     "on: {push: {branches: ['**']}}\njobs: {a: {runs-on: ubuntu-latest}}\n",
     "push is not filtered"),
    ("push with branches-ignore", "extra.yml",
     "on: {push: {branches-ignore: [main]}}\njobs: {a: {runs-on: ubuntu-latest}}\n",
     "branches-ignore"),
    ("another workflow on the Mac", "extra.yml",
     "on: workflow_dispatch\njobs: {a: {runs-on: [self-hosted, macOS, offline-kt]}}\n",
     "names the self-hosted runner"),
    ("docs job on an expression", "docs-check.yml",
     "on: workflow_call\njobs: {check: {runs-on: '${{ vars.RUNNER }}'}}\n",
     "not a literal hosted label"),
    ("another ci.yml job on the Mac", "ci.yml",
     COMPLIANT_CI.replace("changes: {runs-on: ubuntu-slim",
                          "changes: {runs-on: [self-hosted, offline-kt]"),
     "job changes names the self-hosted runner"),
    ("ios-tests with no hosted fallback", "ci.yml",
     COMPLIANT_CI.replace(" || 'macos-26' }}", " }}"), "fall back to a hosted macOS label"),
    ("fan-in missing a job", "ci.yml",
     COMPLIANT_CI.replace("needs: [changes, ios-tests, docs]", "needs: [changes, docs]"),
     "not every other job"),
    ("fan-in without always()", "ci.yml",
     COMPLIANT_CI.replace("if: always() && ", "if: "), "does not run with always()"),
    ("misspelled $/ action", "ci.yml",
     COMPLIANT_CI.replace("$/.github/actions/setup", "$/.github/actions/setpu"),
     "names nothing in the repository"),
    ("misspelled $/ workflow", "ci.yml",
     COMPLIANT_CI.replace("docs-check.yml", "docs-chek.yml"), "names nothing in the repository"),
]


def write_compliant_tree(root: Path) -> None:
    """Writes a small .github tree that passes every rule."""
    (root / ".github" / "workflows").mkdir(parents=True)
    (root / ".github" / "actions" / "setup").mkdir(parents=True)
    (root / ".github" / "workflows" / "ci.yml").write_text(COMPLIANT_CI)
    (root / ".github" / "workflows" / "docs-check.yml").write_text(COMPLIANT_DOCS)
    (root / ".github" / "actions" / "setup" / "action.yml").write_text(COMPLIANT_ACTION)


def self_test() -> None:
    """Plants each violation beside a compliant tree and proves it is reported."""
    failures = 0
    with tempfile.TemporaryDirectory() as work:
        clean = Path(work) / "compliant"
        write_compliant_tree(clean)
        problems = check_tree(clean)
        print(f"{'ok  ' if not problems else 'FAIL'} compliant tree: {problems or 'passes'}")
        failures += bool(problems)
        for index, (name, file_name, content, expected) in enumerate(CASES):
            root = Path(work) / f"case-{index}"
            write_compliant_tree(root)
            (root / ".github" / "workflows" / file_name).write_text(content)
            problems = check_tree(root)
            caught = any(expected in problem for problem in problems)
            print(f"{'ok  ' if caught else 'FAIL'} {name}: {'caught' if caught else problems}")
            failures += not caught
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s).")
        sys.exit(1)
    print(f"SELF-TEST PASSED: {len(CASES)} violations caught and the compliant tree passed.")


def main() -> None:
    """Runs the self-test or checks the repository."""
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return
    if sys.argv[1:]:
        raise SystemExit("usage: check-workflows.py [--self-test]")
    problems = check_tree(Path.cwd())
    for problem in problems:
        print(f"  VIOLATION {problem}")
    if problems:
        print(f"::error::{len(problems)} workflow structure violation(s); see the log.")
        sys.exit(1)
    print("  Workflow structure: no violations.")


if __name__ == "__main__":
    main()
