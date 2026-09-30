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
  8. Releases (RELEASING.md). Only the jobs listed below use the `release` environment, each with
     a condition that starts with the Dependabot guard and requires main; the release secrets
     appear only in the environment of a step of those jobs that publish; only those steps run a
     Maven Central publish task, and only release.yml releases. release.yml and release-resume.yml
     are started by hand alone, grant nothing by default and share the concurrency group `release`,
     never cancelled; release.yml's dry_run input is a boolean defaulting to true, its publish steps
     each run in one mode only, and a job that can write needs the publish and never runs on a dry
     run. release-resume.yml exists beside it, and runs no Gradle and reads no secret. In every
     workflow, a checkout keeps its credentials only in a job that holds `contents: write`.

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
FAN_IN_EXEMPT = {"coverage-upload", "coverage-rehearsal", "snapshot"}
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
# The jobs that use the `release` environment (its approval and its secrets), and which of them
# publish to Maven Central with the release secrets. Release resume only waits for the approval.
RELEASE_ENVIRONMENT = "release"
RELEASE_ENVIRONMENT_JOBS = {("release.yml", "publish"), ("release-resume.yml", "finish"),
                            ("ci.yml", "snapshot")}
PUBLISHING_JOBS = {("release.yml", "publish"), ("ci.yml", "snapshot")}
RELEASE_SECRETS = ("MAVEN_CENTRAL_USERNAME", "MAVEN_CENTRAL_PASSWORD", "GPG_KEY_CONTENTS",
                   "SIGNING_PASSWORD", "SWIFT_PACKAGE_DEPLOY_KEY")
RELEASE_WORKFLOWS = ("release.yml", "release-resume.yml")
MAIN_ONLY = "github.ref == 'refs/heads/main'"
DRY_RUN = "inputs.dry_run"
REAL_RUN = "${{ !inputs.dry_run }}"
CI_PULL_REQUEST_TYPES = ["opened", "synchronize", "reopened", "ready_for_review", "labeled"]


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


def starts_with_guard(condition: object) -> bool:
    """Whether a job's `if` is the Dependabot guard alone or `guard && (...)`, one bracketed term."""
    text = " ".join(str(condition or "").split())
    if text.startswith("${{") and text.endswith("}}"):
        text = " ".join(text[3:-2].split())
    if text == DEPENDABOT_GUARD:
        return True
    prefix = DEPENDABOT_GUARD + " && ("
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


def environment_name(job: dict) -> object:
    """The name of the environment a job uses, or None."""
    environment = job.get("environment")
    return environment.get("name") if isinstance(environment, dict) else environment


def steps_of(job: dict) -> list[dict]:
    """The job's steps, each a mapping."""
    steps = job.get("steps") if isinstance(job.get("steps"), list) else []
    return [step if isinstance(step, dict) else {} for step in steps]


def release_problems(name: str, document: dict) -> list[str]:
    """Rule 8, for one workflow."""
    problems = []
    jobs = document.get("jobs") if isinstance(document.get("jobs"), dict) else {}
    if name in RELEASE_WORKFLOWS:
        triggers = document.get("on")
        if triggers not in ("workflow_dispatch", ["workflow_dispatch"]) and not (
                isinstance(triggers, dict) and set(triggers) == {"workflow_dispatch"}):
            problems.append(f"{name}: triggers {triggers!r}, not workflow_dispatch alone")
        if document.get("permissions") != {}:
            problems.append(f"{name}: its default permissions are {document.get('permissions')!r},"
                            " not {}")
        if document.get("concurrency") != {"group": "release", "cancel-in-progress": False}:
            problems.append(f"{name}: its concurrency is {document.get('concurrency')!r}, not the"
                            " group `release`, never cancelled")
    for job_id, job in jobs.items():
        job = job if isinstance(job, dict) else {}
        key = (name, job_id)
        text = json.dumps(job)
        if (environment_name(job) == RELEASE_ENVIRONMENT) != (key in RELEASE_ENVIRONMENT_JOBS):
            problems.append(f"{name}: job {job_id} uses the environment"
                            f" {environment_name(job)!r}; only {sorted(RELEASE_ENVIRONMENT_JOBS)}"
                            f" use `{RELEASE_ENVIRONMENT}`")
        if key in RELEASE_ENVIRONMENT_JOBS and (
                not starts_with_guard(job.get("if")) or MAIN_ONLY not in str(job.get("if"))):
            problems.append(f"{name}: job {job_id} uses the release environment, and its condition"
                            " does not start with the Dependabot guard or does not require main")
        if key == ("ci.yml", "snapshot") and not all(
                part in str(job.get("if")) for part in ("github.event_name == 'push'",
                                                        "vars.PUBLISH_SNAPSHOTS == 'on'")):
            problems.append(f"{name}: job {job_id} may run on something but a push to main, or"
                            " without PUBLISH_SNAPSHOTS")
        for secret in RELEASE_SECRETS:
            reference = f"secrets.{secret}"
            in_steps = sum(json.dumps(step.get("env", {})).count(reference) for step in steps_of(job))
            if text.count(reference) != in_steps or (in_steps and key not in PUBLISHING_JOBS):
                problems.append(f"{name}: job {job_id} reads the secret {secret} outside the"
                                " environment of a publishing step")
        for step in steps_of(job):
            run = str(step.get("run", ""))
            if "ToMavenCentral" in run and key not in PUBLISHING_JOBS:
                problems.append(f"{name}: job {job_id} publishes to Maven Central")
            if "publishAndReleaseToMavenCentral" in run and (
                    name != "release.yml" or step.get("if") != REAL_RUN):
                problems.append(f"{name}: job {job_id} runs publishAndReleaseToMavenCentral"
                                f" outside release.yml's real run (`if: {REAL_RUN}`)")
            if (re.search(r"\bpublishToMavenCentral\b", run) and name == "release.yml"
                    and step.get("if") != DRY_RUN):
                problems.append(f"{name}: job {job_id} runs publishToMavenCentral outside the dry"
                                f" run (`if: {DRY_RUN}`)")
            uses = str(step.get("uses", ""))
            with_ = step.get("with") if isinstance(step.get("with"), dict) else {}
            permissions = job.get("permissions") if isinstance(job.get("permissions"), dict) else {}
            if (uses.startswith("actions/checkout@") and with_.get("persist-credentials") is not False
                    and permissions.get("contents") != "write"):
                problems.append(f"{name}: job {job_id} keeps a checkout's credentials without"
                                " holding contents: write")
        if name == "release.yml":
            permissions = job.get("permissions") if isinstance(job.get("permissions"), dict) else {}
            needs = job.get("needs")
            needs = [needs] if isinstance(needs, str) else (needs or [])
            if "write" in permissions.values() and ("publish" not in needs
                                                    or job.get("if") != REAL_RUN):
                problems.append(f"{name}: job {job_id} can write, and does not need publish with"
                                f" `if: {REAL_RUN}`")
        if name == "release-resume.yml" and ("gradlew" in text or "secrets." in text):
            problems.append(f"{name}: job {job_id} runs Gradle or reads a secret; the resume path"
                            " only tags and announces a version Maven Central has published")
    if name == "release.yml":
        inputs = (document.get("on") or {}).get("workflow_dispatch") if isinstance(
            document.get("on"), dict) else None
        dry_run = ((inputs or {}).get("inputs") or {}).get("dry_run") if isinstance(inputs, dict) else None
        if not isinstance(dry_run, dict) or dry_run.get("type") != "boolean" or dry_run.get(
                "default") is not True:
            problems.append(f"{name}: its dry_run input is {dry_run!r}, not a boolean defaulting to"
                            " true")
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
            problems += protection_problems(path.name, job_id, job if isinstance(job, dict) else {})
        problems += fan_in_problems(path.name, jobs)
        problems += self_reference_problems(root, path.name, document)
        problems += oidc_problems(path.name, document)
        problems += release_problems(path.name, document)
    workflows = root / ".github" / "workflows"
    if (workflows / "release.yml").is_file() and not (workflows / "release-resume.yml").is_file():
        problems.append("release.yml has no resume path: release-resume.yml is missing")
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
  snapshot:
    needs: changes
    if: >-
      github.actor != 'dependabot[bot]' && github.triggering_actor != 'dependabot[bot]'
      && (github.event_name == 'push' && github.ref == 'refs/heads/main'
      && vars.PUBLISH_SNAPSHOTS == 'on')
    runs-on: ubuntu-latest
    environment: release
    permissions: {{contents: read}}
    steps:
      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567
        with: {{persist-credentials: false}}
      - env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{{{ secrets.MAVEN_CENTRAL_USERNAME }}}}
        run: ./gradlew publishToMavenCentral
  final-status:
    if: {FAN_IN_IF}
    needs: [changes, ios-tests, docs]
    runs-on: ubuntu-slim
    steps: [{{run: echo}}]
"""
RELEASE_GUARD_IF = """>-
      github.actor != 'dependabot[bot]' && github.triggering_actor != 'dependabot[bot]'
      && (github.repository == 'nesfeder/supabase-offline-kt' && github.ref == 'refs/heads/main')"""
COMPLIANT_RELEASE = f"""\
on:
  workflow_dispatch:
    inputs:
      dry_run: {{type: boolean, default: true}}
permissions: {{}}
concurrency: {{group: release, cancel-in-progress: false}}
jobs:
  verify:
    if: {RELEASE_GUARD_IF}
    runs-on: ubuntu-latest
    permissions: {{contents: read}}
    steps:
      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567
        with: {{fetch-depth: 0, persist-credentials: false}}
      - run: python3 scripts/release.py check-version
  publish:
    needs: verify
    if: {RELEASE_GUARD_IF}
    runs-on: ubuntu-latest
    environment: release
    permissions: {{contents: read}}
    steps:
      - if: inputs.dry_run
        env:
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{{{ secrets.GPG_KEY_CONTENTS }}}}
        run: ./gradlew publishToMavenCentral
      - if: ${{{{ !inputs.dry_run }}}}
        env:
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{{{ secrets.GPG_KEY_CONTENTS }}}}
        run: ./gradlew publishAndReleaseToMavenCentral
  finish:
    needs: [verify, publish]
    if: ${{{{ !inputs.dry_run }}}}
    runs-on: ubuntu-latest
    permissions: {{contents: write, pull-requests: write}}
    steps:
      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567
        with: {{persist-credentials: true}}
      - run: python3 scripts/release.py push-tag
"""
COMPLIANT_RESUME = f"""\
on:
  workflow_dispatch:
    inputs:
      sha: {{type: string, required: true}}
permissions: {{}}
concurrency: {{group: release, cancel-in-progress: false}}
jobs:
  finish:
    if: {RELEASE_GUARD_IF}
    runs-on: ubuntu-latest
    environment: release
    permissions: {{contents: write, pull-requests: write}}
    steps:
      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567
        with: {{persist-credentials: true}}
      - run: python3 scripts/release.py check-resume
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
ROUTING_LINE = "      ${{ needs.changes.outputs.ios-runner == 'local'"

# The first line of the compliant coverage-upload condition, which several cases rewrite.
GUARD_LINE = f"      {DEPENDABOT_GUARD}\n"

# Each case: what it proves, the file it replaces or adds, its content, and a phrase the report
# must contain. The later ones are the second review's counter-examples.
CASES: list[tuple[str, str, str | None, str]] = [
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
    # Rule 8: releases.
    ("the release workflow on a pull request", "release.yml",
     COMPLIANT_RELEASE.replace("on:\n  workflow_dispatch:", "on:\n  pull_request:\n  workflow_dispatch:"),
     "not workflow_dispatch alone"),
    ("the resume workflow on a push", "release-resume.yml",
     COMPLIANT_RESUME.replace("on:\n", "on:\n  push: {branches: [main]}\n"),
     "not workflow_dispatch alone"),
    ("a release that cancels one in progress", "release.yml",
     COMPLIANT_RELEASE.replace("cancel-in-progress: false", "cancel-in-progress: true"),
     "never cancelled"),
    ("releases in different concurrency groups", "release-resume.yml",
     COMPLIANT_RESUME.replace("group: release", "group: resume"), "never cancelled"),
    ("a release workflow granting permissions by default", "release.yml",
     COMPLIANT_RELEASE.replace("permissions: {}\n", "permissions: {contents: read}\n", 1),
     "its default permissions"),
    ("dry_run defaulting to false", "release.yml",
     COMPLIANT_RELEASE.replace("default: true", "default: false"), "its dry_run input"),
    ("dry_run as a string", "release.yml",
     COMPLIANT_RELEASE.replace("type: boolean", "type: string"), "its dry_run input"),
    ("the finish job running on a dry run", "release.yml",
     COMPLIANT_RELEASE.replace("  finish:\n    needs: [verify, publish]\n    if: ${{ !inputs.dry_run }}\n",
                               "  finish:\n    needs: [verify, publish]\n"),
     "does not need publish"),
    ("the finish job not waiting for the publish", "release.yml",
     COMPLIANT_RELEASE.replace("needs: [verify, publish]", "needs: [verify]"), "does not need publish"),
    ("the real publish on a dry run too", "release.yml",
     COMPLIANT_RELEASE.replace("      - if: ${{ !inputs.dry_run }}\n", "      - if: always()\n"),
     "runs publishAndReleaseToMavenCentral outside"),
    ("the dry run in the real run too", "release.yml",
     COMPLIANT_RELEASE.replace("      - if: inputs.dry_run\n", "      - if: always()\n"),
     "runs publishToMavenCentral outside the dry run"),
    ("the snapshot job releasing", "ci.yml",
     COMPLIANT_CI.replace("run: ./gradlew publishToMavenCentral", "run: ./gradlew publishAndReleaseToMavenCentral"),
     "outside release.yml's real run"),
    ("a publish in a job without the release environment", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/release.py check-version",
                               "      - run: ./gradlew publishToMavenCentral"),
     "job verify publishes to Maven Central"),
    ("a release secret in the job's environment", "release.yml",
     COMPLIANT_RELEASE.replace("    environment: release\n",
                               "    environment: release\n    env: {KEY: '${{ secrets.SIGNING_PASSWORD }}'}\n"),
     "reads the secret SIGNING_PASSWORD outside"),
    ("a release secret in a job that does not publish", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/release.py check-version",
                               "      - env: {KEY: '${{ secrets.MAVEN_CENTRAL_PASSWORD }}'}\n"
                               "        run: python3 scripts/release.py check-version"),
     "job verify reads the secret MAVEN_CENTRAL_PASSWORD"),
    ("the release environment on the verify job", "release.yml",
     COMPLIANT_RELEASE.replace("    permissions: {contents: read}\n    steps:\n      - uses",
                               "    environment: release\n    permissions: {contents: read}\n    steps:\n      - uses", 1),
     "job verify uses the environment 'release'"),
    ("the publish job without the release environment", "release.yml",
     COMPLIANT_RELEASE.replace("    environment: release\n", ""), "job publish uses the environment None"),
    ("the release environment named in a mapping elsewhere", "docs-check.yml",
     "on: workflow_call\njobs:\n  check:\n    runs-on: ubuntu-latest\n"
     "    environment: {name: release}\n    steps: [{run: echo}]\n",
     "job check uses the environment 'release'"),
    ("the release environment without the Dependabot guard", "release-resume.yml",
     COMPLIANT_RESUME.replace("github.actor != 'dependabot[bot]' && github.triggering_actor != 'dependabot[bot]'\n      && ", ""),
     "does not start with the Dependabot guard or does not require main"),
    ("the release environment off main", "release.yml",
     COMPLIANT_RELEASE.replace(" && github.ref == 'refs/heads/main'", ""),
     "does not start with the Dependabot guard or does not require main"),
    ("the snapshot job without its variable", "ci.yml",
     COMPLIANT_CI.replace("\n      && vars.PUBLISH_SNAPSHOTS == 'on')", ")"),
     "without PUBLISH_SNAPSHOTS"),
    ("the snapshot job needed by the fan-in", "ci.yml",
     COMPLIANT_CI.replace("needs: [changes, ios-tests, docs]", "needs: [changes, ios-tests, docs, snapshot]"),
     "needs snapshot, which must never block a merge"),
    ("the resume path running Gradle", "release-resume.yml",
     COMPLIANT_RESUME.replace("python3 scripts/release.py check-resume", "./gradlew publishToMavenCentral"),
     "the resume path only tags"),
    ("the resume path reading a secret", "release-resume.yml",
     COMPLIANT_RESUME.replace("      - run: python3 scripts/release.py check-resume",
                              "      - env: {KEY: '${{ secrets.GPG_KEY_CONTENTS }}'}\n"
                              "        run: python3 scripts/release.py check-resume"),
     "the resume path only tags"),
    ("a release with no resume path", "release-resume.yml", None, "release-resume.yml is missing"),
    ("a checkout keeping its credentials in a read-only job", "release.yml",
     COMPLIANT_RELEASE.replace("with: {fetch-depth: 0, persist-credentials: false}", "with: {fetch-depth: 0}"),
     "keeps a checkout's credentials"),
]


def write_compliant_tree(root: Path) -> None:
    """Writes a small .github tree that passes every rule."""
    (root / ".github" / "workflows").mkdir(parents=True)
    (root / ".github" / "workflows" / "ci.yml").write_text(COMPLIANT_CI)
    (root / ".github" / "workflows" / "docs-check.yml").write_text(COMPLIANT_DOCS)
    (root / ".github" / "workflows" / "release.yml").write_text(COMPLIANT_RELEASE)
    (root / ".github" / "workflows" / "release-resume.yml").write_text(COMPLIANT_RESUME)
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
            if content is None:
                (root / ".github" / "workflows" / file_name).unlink()
            else:
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
