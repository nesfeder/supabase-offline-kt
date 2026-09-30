#!/usr/bin/env python3
"""Checks the structure of this repository's workflows that no linter checks.

Usage (from the repository root; needs mikefarah yq on the PATH to read YAML):
  check-workflows.py              check .github/
  check-workflows.py --self-test  prove the check catches each kind of violation

It fails when:
  1. a workflow triggers on pull_request_target or workflow_run, or ci.yml's triggers differ from
     the set below;
  2. a workflow triggers on push without a literal branch or tag filter, so Dependabot's own
     branches would start it, or filters on a Dependabot branch or on every tag;
  3. any job but ci.yml's ios-tests names the self-hosted runner, anywhere in the job, or runs on
     anything but a literal GitHub-hosted label; a job calls a reusable workflow other than one of
     this repository's at the same commit (`$/`); or ios-tests' routing differs from the one below;
  4. ci.yml's final-status does not need every other job but the coverage jobs, needs one of them
     (a Codecov outage must never block a merge), or its condition differs from the one below;
  5. a `uses: $/...` reference names an action or workflow that does not exist;
  6. a job not listed below may mint an OIDC token (`id-token: write`, or `write-all`), or a
     workflow's default permissions do; or a listed job holds other permissions than its own, or
     its condition does not start with the Dependabot guard as a conjunct of its own, so a run
     Dependabot pushed or started never holds the token.
  7. ci.yml's ios-tests takes IOS_RUNNER from anything but changes' ios-runner, does not start with
     the self-hosted guard and the home record, or does not end with the clean-up and the
     comparison, both under always(); or a step sets IOS_RUNNER for itself; or the job or one of
     those four steps may continue on error.
  8. a workflow other than ci.yml runs on a schedule and is not listed below; or a listed one
     triggers on anything but its schedule and a manual run, grants default permissions, has
     other jobs than its own, or reads a secret (a stored token would bypass the permissions
     below); or one of its jobs holds other permissions than its own, does not start with the
     repository guard as a conjunct of its own, or holds a write permission and runs an action,
     a checkout included.

The check stops accidents, not intent: a pull request runs its own copy of this script.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SELF_HOSTED = re.compile(r"(?<![A-Za-z0-9_-])(offline-kt|self-hosted)(?![A-Za-z0-9_-])",
                         re.IGNORECASE)
HOSTED_LABEL = re.compile(
    r"ubuntu-latest|ubuntu-slim|ubuntu-\d+\.\d+(-arm)?"
    r"|macos-latest|macos-\d+(-intel|-large|-xlarge)?"
    r"|windows-latest|windows-\d+(-arm)?"
)
ROUTED_JOB = ("ci.yml", "ios-tests")
ROUTED_RUNS_ON = ("${{ needs.changes.outputs.ios-runner == 'local' && fromJSON('[\"self-hosted\","
                  " \"macOS\", \"ARM64\", \"offline-kt\"]') || 'macos-26' }}")
FAN_IN = ("ci.yml", "final-status")
FAN_IN_IF = ("always() && (github.event.action != 'labeled'"
             " || github.event.label.name == 'full-ci')")
IOS_RUNNER_ENV = "${{ needs.changes.outputs.ios-runner }}"
LOCAL_ONLY = "env.IOS_RUNNER == 'local'"
ALWAYS_LOCAL = "always() && env.IOS_RUNNER == 'local'"
SELF_HOSTED_GUARD_IF = "runner.environment != 'github-hosted' && env.IOS_RUNNER != 'local'"
HOME_SNAPSHOT = "$/.github/actions/home-snapshot"
CLEAN_UP = "bash .github/scripts/clean-up-local-runner.sh"
# Jobs of ci.yml the fan-in must not need: a failure there must never block a merge.
FAN_IN_EXEMPT = {"coverage-upload", "coverage-rehearsal"}
# The jobs that may mint an OIDC token, each for one service, with exactly these permissions:
# Codecov (a job that runs no project code; ci.yml says why) and GitHub Pages.
OIDC_JOBS = {
    ("ci.yml", "coverage-upload"): {"contents": "read", "id-token": "write"},
    ("docs-publish.yml", "deploy"): {"pages": "write", "id-token": "write"},
}
# How every such job's condition begins; the rule for the iOS tests' runner is the same.
DEPENDABOT_GUARD = ("github.actor != 'dependabot[bot]'"
                    " && github.triggering_actor != 'dependabot[bot]'")
CI_TRIGGERS = {"pull_request", "push", "merge_group", "schedule", "workflow_dispatch"}
CI_PULL_REQUEST_TYPES = ["opened", "synchronize", "reopened", "ready_for_review", "labeled"]
# Workflows that only their schedule or a maintainer can start, never a pull request, a push or
# Dependabot, with each job's exact permissions. Every other scheduled workflow but ci.yml fails.
# The canary builds an unreviewed upstream release with a read-only token; only `report`, which
# checks out and runs nothing from that build, may write.
SCHEDULED_WORKFLOWS = {
    "upstream-canary.yml": {"canary": {"contents": "read"}, "report": {"issues": "write"}},
}
SCHEDULED_TRIGGERS = {"schedule", "workflow_dispatch"}
REPOSITORY_GUARD = "github.repository == 'nesfeder/supabase-offline-kt'"


def load_yaml(path: Path) -> object:
    """Reads a YAML file through yq, which keeps the `on` key a string."""
    converted = subprocess.run(["yq", "-o=json", ".", str(path)], check=True,
                               capture_output=True, text=True)
    return json.loads(converted.stdout)


def trigger_mapping(triggers: object) -> dict | None:
    """The `on` value as a mapping of event to its settings, or None when it has no valid shape."""
    if isinstance(triggers, str):
        return {triggers: None}
    if isinstance(triggers, list):
        return {trigger: None for trigger in triggers}
    return triggers if isinstance(triggers, dict) else None


def trigger_problems(name: str, triggers: object) -> list[str]:
    """Rules 1 and 2."""
    triggers = trigger_mapping(triggers)
    if triggers is None:
        return [f"{name}: `on` is not a string, list or mapping"]
    problems = [f"{name}: triggers on {event}" for event in ("pull_request_target", "workflow_run")
                if event in triggers]
    if name == "ci.yml":
        if set(triggers) != CI_TRIGGERS:
            problems.append(f"ci.yml: triggers {sorted(triggers)}, not {sorted(CI_TRIGGERS)}")
        pull_request = triggers.get("pull_request")
        types = pull_request.get("types") if isinstance(pull_request, dict) else None
        if types != CI_PULL_REQUEST_TYPES:
            problems.append(f"ci.yml: pull_request types {types}, not {CI_PULL_REQUEST_TYPES}")
    if "push" in triggers:
        push = triggers["push"]
        branches = push.get("branches") if isinstance(push, dict) else None
        tags = push.get("tags") if isinstance(push, dict) else None
        literal = isinstance(branches, list) and branches and all(
            isinstance(branch, str) and not re.search(r"[*?\[!]", branch)
            and not branch.startswith("dependabot/") for branch in branches)
        tagged = branches is None and isinstance(tags, list) and tags and all(
            isinstance(tag, str) and re.match(r"[A-Za-z0-9]", tag) for tag in tags)
        if isinstance(push, dict) and "branches-ignore" in push:
            problems.append(f"{name}: push uses branches-ignore")
        elif not literal and not tagged:
            problems.append(f"{name}: push is not filtered to literal branches or to tags")
    return problems


def runner_problems(name: str, job_id: str, job: dict) -> list[str]:
    """Rule 3."""
    if (name, job_id) == ROUTED_JOB:
        if job.get("runs-on") != ROUTED_RUNS_ON:
            return [f"{name}: {job_id} runs on {job.get('runs-on')!r}, not the reviewed routing"]
        return []
    problems = []
    if SELF_HOSTED.search(json.dumps(job)):
        problems.append(f"{name}: job {job_id} names the self-hosted runner")
    if "uses" in job:
        if not str(job["uses"]).startswith("$/"):
            problems.append(f"{name}: job {job_id} calls {job['uses']}, not a workflow of this"
                            " commit")
        return problems
    runs_on = job.get("runs-on")
    if not (isinstance(runs_on, str) and HOSTED_LABEL.fullmatch(runs_on)):
        problems.append(f"{name}: job {job_id} runs on {runs_on!r}, not a literal hosted label")
    return problems


def protection_problems(name: str, job_id: str, job: dict) -> list[str]:
    """Rule 7: the steps that protect the self-hosted runner are where they must be."""
    if (name, job_id) != ROUTED_JOB:
        return []
    where = f"{name}: {job_id}"
    problems = []
    env = job.get("env") if isinstance(job.get("env"), dict) else {}
    if env.get("IOS_RUNNER") != IOS_RUNNER_ENV:
        problems.append(f"{where} takes IOS_RUNNER from {env.get('IOS_RUNNER')!r}, not"
                        f" {IOS_RUNNER_ENV!r}")
    steps = job.get("steps") if isinstance(job.get("steps"), list) else []
    steps = [step if isinstance(step, dict) else {} for step in steps]
    if len(steps) < 4:
        return problems + [f"{where} has too few steps to hold its protections"]

    def snapshot(step: dict, mode: str) -> bool:
        return step.get("uses") == HOME_SNAPSHOT and step.get("with") == {"mode": mode}

    guard, record, clean_up, compare = steps[0], steps[1], steps[-2], steps[-1]
    for step in steps:
        if isinstance(step.get("env"), dict) and "IOS_RUNNER" in step["env"]:
            problems.append(f"{where}: a step sets IOS_RUNNER for itself")
    # A step or job that may continue on error would let the job pass with a red comparison.
    for label, node in (("the job", job), ("the guard", guard), ("the record", record),
                        ("the clean-up", clean_up), ("the comparison", compare)):
        if node.get("continue-on-error", False) is not False:
            problems.append(f"{where}: {label} may continue on error")
    if guard.get("if") != SELF_HOSTED_GUARD_IF or "exit 1" not in str(guard.get("run")):
        problems.append(f"{where} does not start with the self-hosted guard")
    if not snapshot(record, "record") or record.get("if") != LOCAL_ONLY:
        problems.append(f"{where} does not record the home directory right after the guard")
    if clean_up.get("run") != CLEAN_UP or clean_up.get("if") != ALWAYS_LOCAL:
        problems.append(f"{where} does not clean up under always() as its last step but one")
    if not snapshot(compare, "compare") or compare.get("if") != ALWAYS_LOCAL:
        problems.append(f"{where} does not compare the home directory under always() last")
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
    others = set(jobs) - {FAN_IN[1]} - FAN_IN_EXEMPT
    problems = []
    if set(needs) - FAN_IN_EXEMPT != others:
        problems.append(f"{name}: {FAN_IN[1]} needs {sorted(needs)}, not every other job"
                        f" {sorted(others)}")
    for job_id in sorted(set(needs) & FAN_IN_EXEMPT):
        problems.append(f"{name}: {FAN_IN[1]} needs {job_id}, which must never block a merge")
    if fan_in.get("if") != FAN_IN_IF:
        problems.append(f"{name}: {FAN_IN[1]} runs if {fan_in.get('if')!r}, not {FAN_IN_IF!r}")
    return problems


def grants_oidc(permissions: object) -> bool:
    """Whether a `permissions` value lets the job mint an OIDC token."""
    if isinstance(permissions, str):
        return permissions == "write-all"
    return isinstance(permissions, dict) and permissions.get("id-token") == "write"


def starts_with_guard(condition: object, guard: str = DEPENDABOT_GUARD) -> bool:
    """Whether a job's `if` is the guard alone or `guard && (...)`, with one bracketed term."""
    text = " ".join(str(condition or "").split())
    if text.startswith("${{") and text.endswith("}}"):
        text = " ".join(text[3:-2].split())
    if text == guard:
        return True
    prefix = guard + " && ("
    if not (text.startswith(prefix) and text.endswith(")")):
        return False
    term = text[len(prefix) - 1:]
    depth = 0
    for index, char in enumerate(term):
        depth += {"(": 1, ")": -1}.get(char, 0)
        if depth == 0:
            return index == len(term) - 1
    return False


def oidc_problems(name: str, document: dict) -> list[str]:
    """Rule 6."""
    problems = []
    if grants_oidc(document.get("permissions")):
        problems.append(f"{name}: its default permissions can mint an OIDC token")
    for job_id, job in (document.get("jobs") or {}).items():
        if not isinstance(job, dict) or not grants_oidc(job.get("permissions")):
            continue
        allowed = OIDC_JOBS.get((name, job_id))
        if allowed is None:
            problems.append(f"{name}: job {job_id} can mint an OIDC token, and is not one of"
                            " the jobs allowed to")
            continue
        if job.get("permissions") != allowed:
            problems.append(f"{name}: job {job_id} holds {job.get('permissions')}, not exactly"
                            f" {allowed}")
        if not starts_with_guard(job.get("if")):
            problems.append(f"{name}: job {job_id} can mint an OIDC token, and its condition"
                            " does not start with the Dependabot guard")
    return problems


def scheduled_problems(name: str, document: dict) -> list[str]:
    """Rule 8."""
    triggers = trigger_mapping(document.get("on")) or {}
    allowed_jobs = SCHEDULED_WORKFLOWS.get(name)
    if allowed_jobs is None:
        if name != "ci.yml" and "schedule" in triggers:
            return [f"{name}: runs on a schedule and is not one of the listed scheduled workflows"]
        return []
    problems = []
    if set(triggers) != SCHEDULED_TRIGGERS:
        problems.append(f"{name}: triggers {sorted(triggers)}, not {sorted(SCHEDULED_TRIGGERS)}")
    if document.get("permissions") != {}:
        problems.append(f"{name}: default permissions {document.get('permissions')!r}, not {{}}")
    jobs = document.get("jobs") if isinstance(document.get("jobs"), dict) else {}
    if set(jobs) != set(allowed_jobs):
        problems.append(f"{name}: jobs {sorted(jobs)}, not {sorted(allowed_jobs)}")
    for job_id, permissions in allowed_jobs.items():
        job = jobs.get(job_id)
        if not isinstance(job, dict):
            continue
        if job.get("permissions") != permissions:
            problems.append(f"{name}: job {job_id} holds {job.get('permissions')}, not exactly"
                            f" {permissions}")
        if not starts_with_guard(job.get("if"), REPOSITORY_GUARD):
            problems.append(f"{name}: job {job_id} runs if {job.get('if')!r}, which does not"
                            f" start with {REPOSITORY_GUARD!r}")
        held = job.get("permissions")
        writes = held == "write-all" or (isinstance(held, dict) and "write" in held.values())
        steps = job.get("steps") if isinstance(job.get("steps"), list) else []
        if writes and any(isinstance(step, dict) and "uses" in step for step in steps):
            problems.append(f"{name}: job {job_id} holds a write permission and runs an action")
    if reads_secrets(document):
        problems.append(f"{name}: reads a secret")
    return problems


def reads_secrets(document: dict) -> bool:
    """Whether any expression in the workflow names `secrets`, or a job passes secrets on."""
    expressions = re.findall(r"\$\{\{(.*?)\}\}", json.dumps(document), re.DOTALL)
    if any(re.search(r"\bsecrets\b", expression) for expression in expressions):
        return True
    jobs = document.get("jobs") if isinstance(document.get("jobs"), dict) else {}
    return any(isinstance(job, dict) and "secrets" in job for job in jobs.values())


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
            problems += protection_problems(path.name, job_id, job if isinstance(job, dict) else {})
        problems += fan_in_problems(path.name, jobs)
        problems += self_reference_problems(root, path.name, document)
        problems += oidc_problems(path.name, document)
        problems += scheduled_problems(path.name, document)
    for path in sorted((root / ".github" / "actions").glob("*/action.y*ml")):
        name = str(path.relative_to(root))
        document = load_yaml(path)
        runs = document.get("runs") if isinstance(document, dict) else None
        if SELF_HOSTED.search(json.dumps(runs)):
            problems.append(f"{name}: names the self-hosted runner in what it runs")
        problems += self_reference_problems(root, name, document)
    return problems


COMPLIANT_CI = f"""\
on:
  pull_request:
    types: [opened, synchronize, reopened, ready_for_review, labeled]
  push:
    branches: [main]
  merge_group:
  schedule:
    - cron: '23 5 * * 1'
  workflow_dispatch:
jobs:
  changes: {{runs-on: ubuntu-slim, steps: [{{uses: $/.github/actions/setup}}]}}
  ios-tests:
    needs: changes
    runs-on: >-
      {ROUTED_RUNS_ON}
    env:
      IOS_RUNNER: {IOS_RUNNER_ENV}
    steps:
      - if: {SELF_HOSTED_GUARD_IF}
        run: exit 1
      - if: {LOCAL_ONLY}
        uses: {HOME_SNAPSHOT}
        with: {{mode: record}}
      - run: echo
      - if: {ALWAYS_LOCAL}
        run: {CLEAN_UP}
      - if: {ALWAYS_LOCAL}
        uses: {HOME_SNAPSHOT}
        with: {{mode: compare}}
  docs: {{needs: changes, uses: $/.github/workflows/docs-check.yml}}
  coverage-upload:
    needs: changes
    if: >-
      github.actor != 'dependabot[bot]' && github.triggering_actor != 'dependabot[bot]'
      && (github.event_name == 'push' || github.event.repository.visibility == 'public')
    runs-on: ubuntu-latest
    permissions: {{contents: read, id-token: write}}
    steps: [{{run: echo}}]
  coverage-rehearsal:
    needs: changes
    runs-on: ubuntu-latest
    permissions: {{contents: read}}
    steps: [{{run: echo}}]
  final-status:
    if: {FAN_IN_IF}
    needs: [changes, ios-tests, docs]
    runs-on: ubuntu-slim
    steps: [{{run: echo}}]
"""
COMPLIANT_DOCS = """\
on:
  workflow_call:
  push:
    tags: ['v*']
jobs:
  check: {runs-on: ubuntu-latest, steps: [{run: echo supabase-offline-kt}]}
"""
COMPLIANT_CANARY = f"""\
on:
  schedule:
    - cron: '0 7 * * 1'
  workflow_dispatch:
    inputs:
      pretend_pinned: {{type: string}}
permissions: {{}}
jobs:
  canary:
    if: {REPOSITORY_GUARD}
    runs-on: ubuntu-latest
    permissions: {{contents: read}}
    steps: [{{uses: $/.github/actions/setup}}]
  report:
    needs: canary
    if: {REPOSITORY_GUARD} && (needs.canary.outputs.newer == 'true')
    runs-on: ubuntu-latest
    permissions: {{issues: write}}
    steps:
      - env: {{GH_TOKEN: '${{{{ github.token }}}}'}}
        run: echo
"""
COMPLIANT_ACTION = "runs: {using: composite, steps: [{run: echo, shell: bash}]}\n"
ROUTING_LINE = "      ${{ needs.changes.outputs.ios-runner == 'local'"

# The first line of the compliant coverage-upload condition, which several cases rewrite.
GUARD_LINE = f"      {DEPENDABOT_GUARD}\n"

# Each case: what it proves, the file it replaces or adds, its content, and a phrase the report
# must contain. The later ones are the second review's counter-examples.
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
    ("another workflow on the self-hosted runner", "extra.yml",
     "on: workflow_dispatch\njobs: {a: {runs-on: [self-hosted, macOS, offline-kt]}}\n",
     "names the self-hosted runner"),
    ("docs job on an expression", "docs-check.yml",
     "on: workflow_call\njobs: {check: {runs-on: '${{ vars.RUNNER }}'}}\n",
     "not a literal hosted label"),
    ("another ci.yml job on the self-hosted runner", "ci.yml",
     COMPLIANT_CI.replace("changes: {runs-on: ubuntu-slim",
                          "changes: {runs-on: [self-hosted, offline-kt]"),
     "job changes names the self-hosted runner"),
    ("ios-tests with no hosted fallback", "ci.yml",
     COMPLIANT_CI.replace(" || 'macos-26' }}", " }}"), "not the reviewed routing"),
    ("fan-in missing a job", "ci.yml",
     COMPLIANT_CI.replace("needs: [changes, ios-tests, docs]", "needs: [changes, docs]"),
     "not every other job"),
    ("fan-in without always()", "ci.yml",
     COMPLIANT_CI.replace("if: always() && ", "if: "), "final-status runs if"),
    ("misspelled $/ action", "ci.yml",
     COMPLIANT_CI.replace("$/.github/actions/setup", "$/.github/actions/setpu"),
     "names nothing in the repository"),
    ("misspelled $/ workflow", "ci.yml",
     COMPLIANT_CI.replace("docs-check.yml", "docs-chek.yml"), "names nothing in the repository"),
    ("remote reusable workflow passes the label as an input", "extra.yml",
     "on: {pull_request: {}}\njobs:\n  a:\n    uses: someone/else/.github/workflows/w.yml@0123456"
     "789abcdef0123456789abcdef01234567\n    with: {runner: '[\"self-hosted\",\"offline-kt\"]'}\n",
     "names the self-hosted runner"),
    ("this repository's reusable workflow at another ref", "extra.yml",
     "on: {pull_request: {}}\njobs:\n  a:\n    uses: nesfeder/supabase-offline-kt/.github/"
     "workflows/evil.yml@some-branch\n    with: {runner: ubuntu-latest}\n",
     "not a workflow of this commit"),
    ("label in a matrix, upper case", "extra.yml",
     "on: workflow_dispatch\njobs:\n  a:\n    strategy: {matrix: {os: [OFFLINE-KT]}}\n"
     "    runs-on: ${{ matrix.os }}\n    steps: [{run: echo}]\n",
     "names the self-hosted runner"),
    ("ios-tests routing widened but still ending in a hosted label", "ci.yml",
     COMPLIANT_CI.replace("${{ needs.changes.outputs.ios-runner == 'local'",
                          "${{ (needs.changes.outputs.ios-runner == 'local' || true)"),
     "not the reviewed routing"),
    ("a hosted-looking label the runner could be given", "extra.yml",
     "on: workflow_dispatch\njobs:\n  a:\n    runs-on: macos-offline-kt\n"
     "    steps: [{run: echo}]\n", "not a literal hosted label"),
    ("fan-in if: always() && false", "ci.yml",
     COMPLIANT_CI.replace("if: always() && (", "if: always() && false && ("),
     "final-status runs if"),
    ("ci.yml gains a create trigger", "ci.yml",
     COMPLIANT_CI.replace("  merge_group:\n", "  merge_group:\n  create:\n"), "ci.yml: triggers"),
    ("ci.yml gains issue_comment", "ci.yml",
     COMPLIANT_CI.replace("  merge_group:\n", "  merge_group:\n  issue_comment:\n"),
     "ci.yml: triggers"),
    ("ci.yml pull_request gains a type", "ci.yml",
     COMPLIANT_CI.replace("ready_for_review, labeled]", "ready_for_review, labeled, edited]"),
     "pull_request types"),
    ("push filtered to a literal dependabot branch", "extra.yml",
     "on: {push: {branches: ['dependabot/gradle/x']}}\njobs: {a: {runs-on: ubuntu-latest}}\n",
     "push is not filtered"),
    ("push with a tags glob only", "extra.yml",
     "on: {push: {tags: ['**']}}\njobs: {a: {runs-on: ubuntu-latest}}\n", "push is not filtered"),
    ("fan-in renamed", "ci.yml", COMPLIANT_CI.replace("  final-status:\n", "  fan-in:\n"),
     "no final-status job"),
    (".yaml file carrying ios-tests", "ci.yaml",
     "on: {pull_request: {}}\njobs:\n  ios-tests:\n    runs-on: ${{ needs.changes.outputs.ios-runner"
     " == 'local' && fromJSON('[\"offline-kt\"]') || 'macos-26' }}\n    steps: [{run: echo}]\n",
     "names the self-hosted runner"),
    ("fan-in needs the coverage upload", "ci.yml",
     COMPLIANT_CI.replace("needs: [changes, ios-tests, docs]",
                          "needs: [changes, ios-tests, docs, coverage-upload]"),
     "which must never block a merge"),
    ("another ci.yml job can mint an OIDC token", "ci.yml",
     COMPLIANT_CI.replace("changes: {runs-on: ubuntu-slim,",
                          "changes: {runs-on: ubuntu-slim, permissions: {id-token: write},"),
     "job changes can mint an OIDC token"),
    ("a job with write-all", "extra.yml",
     "on: workflow_dispatch\njobs:\n  a:\n    runs-on: ubuntu-latest\n    permissions: write-all\n"
     "    steps: [{run: echo}]\n", "job a can mint an OIDC token"),
    ("a coverage-upload job in another workflow", "extra.yml",
     "on: workflow_dispatch\njobs:\n  coverage-upload:\n    runs-on: ubuntu-latest\n"
     "    permissions: {id-token: write}\n    steps: [{run: echo}]\n",
     "job coverage-upload can mint an OIDC token"),
    ("OIDC in a workflow's default permissions", "ci.yml",
     COMPLIANT_CI.replace("jobs:\n", "permissions: {contents: read, id-token: write}\njobs:\n", 1),
     "default permissions can mint an OIDC token"),
    ("fan-in needs the coverage rehearsal", "ci.yml",
     COMPLIANT_CI.replace("needs: [changes, ios-tests, docs]",
                          "needs: [changes, ios-tests, docs, coverage-rehearsal]"),
     "needs coverage-rehearsal, which must never block a merge"),
    ("the OIDC job without the Dependabot guard", "ci.yml",
     COMPLIANT_CI.replace(GUARD_LINE + "      && (", "      (", 1),
     "does not start with the Dependabot guard"),
    ("the Dependabot guard only one side of an or", "ci.yml",
     COMPLIANT_CI.replace(GUARD_LINE + "      && (",
                          GUARD_LINE + "      || (", 1),
     "does not start with the Dependabot guard"),
    ("the guard's term followed by an unbracketed or", "ci.yml",
     COMPLIANT_CI.replace("visibility == 'public')\n", "visibility == 'public') || (true)\n", 1),
     "does not start with the Dependabot guard"),
    ("the Dependabot guard missing its triggering-actor half", "ci.yml",
     COMPLIANT_CI.replace(" && github.triggering_actor != 'dependabot[bot]'\n", "\n", 1),
     "does not start with the Dependabot guard"),
    ("the OIDC job gains a permission", "ci.yml",
     COMPLIANT_CI.replace("permissions: {contents: read, id-token: write}",
                          "permissions: {contents: read, id-token: write, pull-requests: write}", 1),
     "not exactly"),
    ("the rehearsal gains the OIDC permission", "ci.yml",
     COMPLIANT_CI.replace("permissions: {contents: read}\n",
                          "permissions: {contents: read, id-token: write}\n", 1),
     "job coverage-rehearsal can mint an OIDC token"),
    # The third review's probes of the steps that protect the self-hosted runner.
    ("ios-tests without its self-hosted guard", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {SELF_HOSTED_GUARD_IF}\n        run: exit 1\n", ""),
     "does not start with the self-hosted guard"),
    ("home record switched off", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {LOCAL_ONLY}\n", "      - if: false\n"),
     "does not record the home directory"),
    ("IOS_RUNNER fed from another output", "ci.yml",
     COMPLIANT_CI.replace(f"IOS_RUNNER: {IOS_RUNNER_ENV}",
                          "IOS_RUNNER: ${{ needs.changes.outputs.kotlin }}"),
     "takes IOS_RUNNER from"),
    ("clean-up without always()", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {ALWAYS_LOCAL}\n        run:",
                          f"      - if: {LOCAL_ONLY}\n        run:"),
     "does not clean up under always()"),
    ("home comparison removed", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {ALWAYS_LOCAL}\n        uses: {HOME_SNAPSHOT}\n"
                          "        with: {mode: compare}\n", ""),
     "does not compare the home directory"),
    ("the comparison may continue on error", "ci.yml",
     COMPLIANT_CI.replace("        with: {mode: compare}\n",
                          "        with: {mode: compare}\n        continue-on-error: true\n"),
     "the comparison may continue on error"),
    ("the record sets IOS_RUNNER for itself", "ci.yml",
     COMPLIANT_CI.replace("        with: {mode: record}\n",
                          "        with: {mode: record}\n        env: {IOS_RUNNER: local}\n"),
     "a step sets IOS_RUNNER for itself"),
    ("ios-tests may continue on error", "ci.yml",
     COMPLIANT_CI.replace("    env:\n      IOS_RUNNER:",
                          "    continue-on-error: ${{ true }}\n    env:\n      IOS_RUNNER:"),
     "the job may continue on error"),
    # The scheduled workflows: only their schedule and a maintainer start them.
    ("the canary gains a pull_request trigger", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("  workflow_dispatch:\n", "  pull_request:\n  workflow_dispatch:\n"),
     "upstream-canary.yml: triggers"),
    ("the canary gains a push to main", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("  workflow_dispatch:\n",
                              "  push:\n    branches: [main]\n  workflow_dispatch:\n"),
     "upstream-canary.yml: triggers"),
    ("the canary loses its schedule", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("  schedule:\n    - cron: '0 7 * * 1'\n", ""),
     "upstream-canary.yml: triggers"),
    ("the canary grants default permissions", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("permissions: {}\n", "permissions: {contents: write}\n"),
     "default permissions"),
    ("the build job gains issues: write", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("permissions: {contents: read}",
                              "permissions: {contents: read, issues: write}"),
     "job canary holds a write permission and runs an action"),
    ("the build job holds write-all", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("permissions: {contents: read}", "permissions: write-all"),
     "job canary holds a write permission and runs an action"),
    ("the build job writes contents", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("permissions: {contents: read}", "permissions: {contents: write}"),
     "job canary holds"),
    ("the build job without permissions", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("    permissions: {contents: read}\n", ""),
     "job canary holds None"),
    ("the report job gains a permission", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("permissions: {issues: write}",
                              "permissions: {issues: write, pull-requests: write}"),
     "job report holds"),
    ("the report job checks out the repository", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("    steps:\n      - env:",
                              "    steps:\n      - uses: actions/checkout@v7\n      - env:"),
     "job report holds a write permission and runs an action"),
    ("the build job without its repository guard", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"    if: {REPOSITORY_GUARD}\n", ""), "job canary runs if None"),
    ("the report job without its repository guard", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"    if: {REPOSITORY_GUARD} && (needs",
                              "    if: (needs"),
     "job report runs if"),
    ("the repository guard only one side of an or", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"    if: {REPOSITORY_GUARD} && (needs",
                              f"    if: {REPOSITORY_GUARD} || (needs"),
     "job report runs if"),
    ("the report job reads a personal access token", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("github.token", "secrets.MAINTAINER_PAT"),
     "upstream-canary.yml: reads a secret"),
    ("a job passes secrets on", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("    needs: canary\n", "    needs: canary\n    secrets: inherit\n"),
     "upstream-canary.yml: reads a secret"),
    ("the canary gains a third job", "upstream-canary.yml",
     COMPLIANT_CANARY + "  other: {runs-on: ubuntu-latest, steps: [{run: echo}]}\n",
     "upstream-canary.yml: jobs"),
    ("another workflow on a schedule", "extra.yml",
     "on: {schedule: [{cron: '0 0 * * 0'}]}\njobs: {a: {runs-on: ubuntu-latest}}\n",
     "not one of the listed scheduled workflows"),
]


def write_compliant_tree(root: Path) -> None:
    """Writes a small .github tree that passes every rule."""
    (root / ".github" / "workflows").mkdir(parents=True)
    (root / ".github" / "workflows" / "ci.yml").write_text(COMPLIANT_CI)
    (root / ".github" / "workflows" / "docs-check.yml").write_text(COMPLIANT_DOCS)
    (root / ".github" / "workflows" / "upstream-canary.yml").write_text(COMPLIANT_CANARY)
    for action in ("setup", "home-snapshot"):
        (root / ".github" / "actions" / action).mkdir(parents=True)
        (root / ".github" / "actions" / action / "action.yml").write_text(COMPLIANT_ACTION)


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
