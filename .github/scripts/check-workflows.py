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
     repository guard as a conjunct of its own, or holds a write permission and runs an action
     (a checkout or a reusable workflow included); or a step runs Gradle other than as the
     unprivileged build user, with only Gradle arguments, between a step that prepares that user
     and a step under always() that stops it; or a step after that build runs an action, or runs
     without the absolute shell below or with a first command that is not an absolute path.
  9. Releases (RELEASING.md).
     - Who holds the secrets: only the jobs listed below use the `release` environment, each with
       a condition that starts with the Dependabot guard, as its own conjunct, followed by one
       bracketed term that requires main; no job of a release workflow has an `||` in that term.
       The Maven secrets, in any syntax, appear only in the environment of a step of those jobs
       that publishes to Central, and no workflow passes `toJSON(secrets)`; a step holding a
       release secret neither traces itself (`set -x`, `--debug`) nor expands one in its script.
     - The Swift package's deploy key appears once per release workflow, in the environment of the
       one step that pushes the Swift package (release.yml's publish job, after the step that
       releases on Central and on a real run only; release-resume.yml's finish job, after the
       resume check and before this repository's tag). That step runs no build, writes the key
       with umask 077 and mode 600 under RUNNER_TEMP, and connects with IdentitiesOnly and
       host-key checking on against a known_hosts file; a later step removes the key under
       always(). No step of a release workflow pushes with force (`--force`, `-f`, a `+` refspec,
       `--mirror`).
     - The framework: only release.yml's framework job builds and zips it, on a GitHub-hosted
       macOS runner it checks it is on, with `contents: read` alone and no secret; nothing reads
       its outputs. The draft job holds `contents: write` alone and no secret, runs on a real run
       only after the framework, keeps no checkout credentials, and gives its token only to the
       step that creates the draft release. publish needs verify, the framework and the draft,
       under the exact condition below. The resume path takes the framework from the release and
       builds nothing.
     - The documentation site: each release workflow has a job that calls docs-publish.yml at the
       released tag, after finish, holding exactly the Pages and OIDC permissions and
       `contents: read`, passing no secret, and skipped on a dry run.
     - What they run: only the Central steps run a Maven Central publish task, and only
       release.yml releases, waiting until Central says PUBLISHED; the snapshot job's publish step
       runs only for a -SNAPSHOT version; no job of either release workflow, nor the snapshot job,
       restores a cache (`actions/cache`, setup-gradle, the setup-project action, a setup action's
       `cache:` input).
     - What they rest on: release.yml's verify job checks out all history and tags, and runs
       `release.py check-version`, `api-compat.py check-release` and the published-artifacts
       check, none of them skippable; no other job reads verify's outputs or checks out a ref of
       its own, so publish signs, and finish tags, the run's own commit.
     - How they start: release.yml and release-resume.yml are started by hand alone, grant nothing
       by default and share the concurrency group `release`, never cancelled; release.yml's
       dry_run input is a boolean defaulting to true, its publish steps each run in one mode only,
       and a job that can write (but the draft and the docs jobs above) needs the publish and
       never runs on a dry run. release-resume.yml exists beside it, runs no Gradle and reads no
       secret but the deploy key.
     - In every workflow, a checkout keeps its credentials only in a job that holds
       `contents: write`.

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
    ("release.yml", "docs"): {"contents": "read", "pages": "write", "id-token": "write"},
    ("release-resume.yml", "docs"): {"contents": "read", "pages": "write", "id-token": "write"},
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
MAVEN_SECRETS = ("MAVEN_CENTRAL_USERNAME", "MAVEN_CENTRAL_PASSWORD", "GPG_KEY_CONTENTS",
                 "SIGNING_PASSWORD")
SWIFT_KEY = "SWIFT_PACKAGE_DEPLOY_KEY"
RELEASE_SECRETS = MAVEN_SECRETS + (SWIFT_KEY,)
RELEASE_WORKFLOWS = ("release.yml", "release-resume.yml")
# The one step per release workflow that may hold the Swift package's deploy key, and how it uses
# it: written under RUNNER_TEMP with mode 600, host-key checking on, removed by a later step.
SWIFT_PUBLISHING_JOBS = {("release.yml", "publish"), ("release-resume.yml", "finish")}
SWIFT_PUBLISH = "python3 scripts/swift-release.py publish"
SWIFT_KEY_FILE = '"$RUNNER_TEMP/swift-package-deploy-key"'
SWIFT_KEY_WRITE = "(umask 077 && printenv SWIFT_PACKAGE_DEPLOY_KEY > \"$key\")"
SWIFT_SSH_OPTIONS = ("-o IdentitiesOnly=yes", "-o StrictHostKeyChecking=yes", "-o UserKnownHostsFile=")
HOST_KEY_CHECKING_OFF = re.compile(r"StrictHostKeyChecking\s*=?\s*(?!yes\b)\w", re.IGNORECASE)
FORCED_PUSH = re.compile(r"--force|--mirror|\bpush\b[^\n]*\s(-[A-Za-z]*f\b|\+\S)")
# The framework: built and zipped by one job on GitHub's macOS runner, staged on a draft release by
# another before the approval, and never built again.
FRAMEWORK_JOB = ("release.yml", "framework")
FRAMEWORK_STEPS = ("python3 scripts/swift-release.py build", "python3 scripts/check-bridge-header.py",
                   "python3 scripts/swift-release.py zip", "bash scripts/test-swift-package.sh",
                   "python3 scripts/swift-release.py read-manifest")
BUILDS_FRAMEWORK = re.compile(r"swift-release\.py (build|zip)\b|test-swift-package|xcodebuild")
HOSTED_GUARD_IF = "runner.environment != 'github-hosted'"
# This repository's tag and GitHub Release, which come after the Swift package's tag.
TAGS_THIS_REPOSITORY = r"release\.py (push-tag|publish-github-release)"
DRAFT_JOB = ("release.yml", "draft")
DRAFT_RELEASE = "python3 scripts/swift-release.py draft-release"
PUBLISH_IF = (DEPENDABOT_GUARD + " && (github.repository == 'nesfeder/supabase-offline-kt'"
              " && github.ref == 'refs/heads/main' && !cancelled() && needs.verify.result == 'success'"
              " && needs.framework.result == 'success'"
              " && (needs.draft.result == 'success') == !inputs.dry_run)")
# Each release workflow publishes the documentation site at the released tag, after finish.
DOCS_WORKFLOW = "$/.github/workflows/docs-publish.yml"
DOCS_PERMISSIONS = {"contents": "read", "pages": "write", "id-token": "write"}
DOCS_REF = "refs/tags/${{ needs.finish.outputs.version }}"
DOCS_IF = {"release.yml": DEPENDABOT_GUARD + " && (!inputs.dry_run)",
           "release-resume.yml": DEPENDABOT_GUARD + " && (github.repository =="
                                 " 'nesfeder/supabase-offline-kt' && github.ref == 'refs/heads/main')"}
# Jobs that may not restore a cache: every job of the release workflows, and the snapshot job.
CACHE_FREE_JOBS = {("ci.yml", "snapshot")}
MAIN_ONLY = "github.ref == 'refs/heads/main'"
DRY_RUN = "inputs.dry_run"
REAL_RUN = "${{ !inputs.dry_run }}"
WAIT_FOR_PUBLISHED = "-PmavenCentralDeploymentValidation=PUBLISHED"
SNAPSHOT_ONLY = "steps.version.outputs.snapshot == 'true'"
# The guards verify must run, as the start of a step's script.
VERIFY_STEPS = ("python3 scripts/release.py check-version", "python3 scripts/api-compat.py check-release",
                "python3 scripts/check-published-artifacts.py")
CACHE_ACTIONS = ("actions/cache", "gradle/actions/setup-gradle", "$/.github/actions/setup-project")
# A Gradle task that can publish to Maven Central: the aggregate `publish` or any Central task.
CENTRAL_TASK = re.compile(r"\bpublish(?:\b|\w*MavenCentral\w*)")
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
# The one way a listed workflow may run Gradle: as the unprivileged build user, after the step that
# prepares it and before the step that stops it, so the build never holds the runner's tokens.
BUILD_USER_SCRIPT = "bash .github/scripts/build-as-unprivileged-user.sh"
# After the build, nothing is found through the PATH the build ran beside: the shell and the first
# command are absolute, and collect is the script run by /usr/bin/bash.
ABSOLUTE_SHELL = "/usr/bin/bash --noprofile --norc -e -o pipefail {0}"
COLLECT = "/" + "usr/bin/" + BUILD_USER_SCRIPT + " collect "
RUNS_GRADLE = re.compile(r"gradlew|run-gradle|gradle-wrapper|GradleWrapperMain"
                         r"|(^|[\s;&|(`/])gradle(\s|$)")
GRADLE_ARGUMENT = re.compile(r"[-:A-Za-z0-9=._]+")


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


def environment_name(job: dict) -> object:
    """The name of the environment a job uses, or None."""
    environment = job.get("environment")
    return environment.get("name") if isinstance(environment, dict) else environment


def steps_of(job: dict) -> list[dict]:
    """The job's steps, each a mapping."""
    steps = job.get("steps") if isinstance(job.get("steps"), list) else []
    return [step if isinstance(step, dict) else {} for step in steps]


def secret_references(text: str, secret: str) -> int:
    """How often a text names the secret: `secrets.NAME` or `secrets['NAME']`."""
    return len(re.findall(rf"secrets(?:\.{secret}\b|\[\s*['\"]{secret}['\"]\s*\])", text))


def guard_term(condition: object) -> str:
    """The bracketed term after the Dependabot guard in a job's condition, or an empty string."""
    text = " ".join(str(condition or "").split())
    prefix = DEPENDABOT_GUARD + " && ("
    return text[len(prefix):-1] if text.startswith(prefix) and text.endswith(")") else ""


def runs_central_publish(step: dict) -> bool:
    """Whether a step runs Gradle with a task that can publish to Maven Central."""
    run = str(step.get("run", ""))
    return "gradle" in run and bool(CENTRAL_TASK.search(run))


def restores_cache(step: dict) -> bool:
    """Whether a step restores a cache: a cache action, or a setup action asked to cache."""
    uses = str(step.get("uses", ""))
    with_ = step.get("with") if isinstance(step.get("with"), dict) else {}
    return (any(uses.startswith(action) for action in CACHE_ACTIONS)
            or (uses.startswith("actions/setup-") and "cache" in with_))


def release_job_problems(name: str, job_id: str, job: dict) -> list[str]:
    """Rule 9, for one job of any workflow."""
    problems = []
    key = (name, job_id)
    text = json.dumps(job)
    where = f"{name}: job {job_id}"
    if (environment_name(job) == RELEASE_ENVIRONMENT) != (key in RELEASE_ENVIRONMENT_JOBS):
        problems.append(f"{where} uses the environment {environment_name(job)!r}; only"
                        f" {sorted(RELEASE_ENVIRONMENT_JOBS)} use `{RELEASE_ENVIRONMENT}`")
    if key in RELEASE_ENVIRONMENT_JOBS and (
            not starts_with_guard(job.get("if")) or MAIN_ONLY not in guard_term(job.get("if"))
            or "||" in guard_term(job.get("if"))):
        problems.append(f"{where} uses the release environment, and its condition does not start"
                        " with the Dependabot guard, or does not require main, or has an `||`")
    if key == ("ci.yml", "snapshot") and not all(
            part in str(job.get("if")) for part in ("github.event_name == 'push'",
                                                    "vars.PUBLISH_SNAPSHOTS == 'on'")):
        problems.append(f"{where} may run on something but a push to main, or without"
                        " PUBLISH_SNAPSHOTS")
    if "toJSON(secrets)" in text.replace(" ", ""):
        problems.append(f"{where} passes toJSON(secrets), every secret at once")
    guarded = " ".join(str(job.get("if") or "").split()).startswith(DEPENDABOT_GUARD)
    if name in RELEASE_WORKFLOWS and guarded and (
            not starts_with_guard(job.get("if")) or "||" in guard_term(job.get("if"))):
        problems.append(f"{where}: its condition does not keep the Dependabot guard as its own"
                        " conjunct before one bracketed term, or has an `||`")
    steps = steps_of(job)
    for secret in MAVEN_SECRETS:
        in_steps = sum(secret_references(json.dumps(step.get("env", {})), secret) for step in steps
                       if runs_central_publish(step))
        if secret_references(text, secret) != in_steps or (in_steps and key not in PUBLISHING_JOBS):
            problems.append(f"{where} reads the secret {secret} outside the environment of a"
                            " publishing step")
    problems += swift_key_problems(name, job_id, job, steps)
    permissions = job.get("permissions") if isinstance(job.get("permissions"), dict) else {}
    for step in steps:
        run = str(step.get("run", ""))
        with_ = step.get("with") if isinstance(step.get("with"), dict) else {}
        if any(secret_references(json.dumps(step.get("env", {})), secret) for secret in RELEASE_SECRETS):
            if re.search(r"set -[a-z]*x|set -o xtrace|--debug|\$\{?(ORG_GRADLE_PROJECT_|SWIFT_PACKAGE_DEPLOY_KEY)",
                         run):
                problems.append(f"{where}: a step holding the release secrets traces itself or"
                                " expands them in its script")
        if name in RELEASE_WORKFLOWS and FORCED_PUSH.search(run):
            problems.append(f"{where}: a step pushes with force; a tag or a branch is never moved")
        if name == "release.yml" and job_id != "finish" and re.search(TAGS_THIS_REPOSITORY, run):
            problems.append(f"{where} tags this repository or publishes its GitHub Release, which finish"
                            " alone does, after the Swift package")
        if name in RELEASE_WORKFLOWS and key != FRAMEWORK_JOB and BUILDS_FRAMEWORK.search(run):
            problems.append(f"{where} builds or zips the Swift package's framework, which the framework"
                            " job alone does, once")
        if runs_central_publish(step) and key not in PUBLISHING_JOBS:
            problems.append(f"{where} publishes to Maven Central")
        if "publishAndReleaseToMavenCentral" in run and (
                name != "release.yml" or step.get("if") != REAL_RUN):
            problems.append(f"{where} runs publishAndReleaseToMavenCentral outside release.yml's"
                            f" real run (`if: {REAL_RUN}`)")
        if "publishAndReleaseToMavenCentral" in run and WAIT_FOR_PUBLISHED not in run:
            problems.append(f"{where} releases without {WAIT_FOR_PUBLISHED}, so finish could tag a"
                            " version Central has not published")
        if (re.search(r"\bpublishToMavenCentral\b", run) and name == "release.yml"
                and step.get("if") != DRY_RUN):
            problems.append(f"{where} runs publishToMavenCentral outside the dry run"
                            f" (`if: {DRY_RUN}`)")
        if key == ("ci.yml", "snapshot") and runs_central_publish(step) and step.get("if") != SNAPSHOT_ONLY:
            problems.append(f"{where} publishes without the snapshot-only condition"
                            f" (`if: {SNAPSHOT_ONLY}`)")
        uses = str(step.get("uses", ""))
        if (uses.startswith("actions/checkout@") and with_.get("persist-credentials") is not False
                and permissions.get("contents") != "write"):
            problems.append(f"{where} keeps a checkout's credentials without holding contents:"
                            " write")
        if (name in RELEASE_WORKFLOWS or key in CACHE_FREE_JOBS) and restores_cache(step):
            problems.append(f"{where} restores a cache ({uses}), next to the release secrets")
        if (name == "release.yml" and job_id != "verify" and uses.startswith("actions/checkout@")
                and "ref" in with_):
            problems.append(f"{where} checks out the ref {with_['ref']!r}, not the run's own commit")
    if name == "release.yml" and job_id != "verify" and "needs.verify.outputs" in text:
        problems.append(f"{where} reads verify's outputs; verify runs the build and the tests, so"
                        " nothing it produces may reach the jobs that sign and tag")
    if name == "release.yml" and job_id != "framework" and "needs.framework.outputs" in text:
        problems.append(f"{where} reads the framework job's outputs; it runs the build, and hands on"
                        " its zip alone")
    needs = job.get("needs")
    needs = [needs] if isinstance(needs, str) else (needs or [])
    if key == ("release.yml", "publish"):
        if not {"verify", "framework", "draft"} <= set(needs):
            problems.append(f"{where} does not need verify, the framework and the draft")
        if " ".join(str(job.get("if", "")).split()) != PUBLISH_IF:
            problems.append(f"{where} runs if {job.get('if')!r}, not exactly {PUBLISH_IF!r}")
    if key == ("release.yml", "verify"):
        problems += verify_problems(where, job, steps)
    if key == FRAMEWORK_JOB:
        problems += framework_problems(where, job, steps)
    if key == DRAFT_JOB:
        problems += draft_problems(where, job, steps, needs)
    calls_docs = job.get("uses") == DOCS_WORKFLOW
    if name in RELEASE_WORKFLOWS and calls_docs:
        problems += docs_problems(name, where, job, needs)
    if (name == "release.yml" and "write" in permissions.values() and key != DRAFT_JOB
            and not calls_docs and ("publish" not in needs or job.get("if") != REAL_RUN)):
        problems.append(f"{where} can write, and does not need publish with `if: {REAL_RUN}`")
    if name == "release-resume.yml" and (
            re.search(r"\bgradlew?\b", text)
            or "secrets" in re.sub(rf"secrets\.{SWIFT_KEY}\b", "", text)):
        problems.append(f"{where} runs Gradle or reads a secret; the resume path only tags and"
                        " announces a version Maven Central has published")
    return problems


def swift_key_problems(name: str, job_id: str, job: dict, steps: list[dict]) -> list[str]:
    """Rule 9: the deploy key reaches one step, which pushes the Swift package and nothing else."""
    where = f"{name}: job {job_id}"
    holders = [index for index, step in enumerate(steps)
               if secret_references(json.dumps(step.get("env", {})), SWIFT_KEY)]
    problems = []
    if secret_references(json.dumps(job), SWIFT_KEY) != len(holders) or len(holders) > 1 or (
            holders and (name, job_id) not in SWIFT_PUBLISHING_JOBS):
        problems.append(f"{where} reads the secret {SWIFT_KEY} outside the environment of the one step"
                        " that publishes the Swift package")
    for index in holders[:1]:
        step = steps[index]
        run = str(step.get("run", ""))
        if not run.count(SWIFT_PUBLISH) == 1 or "--dry-run" in run:
            problems.append(f"{where}: the step holding {SWIFT_KEY} does not push the Swift package")
        if RUNS_GRADLE.search(run) or BUILDS_FRAMEWORK.search(run):
            problems.append(f"{where}: the step holding {SWIFT_KEY} also runs a build")
        if (SWIFT_KEY_WRITE not in run or f"chmod 600 \"$key\"" not in run
                or f"key={SWIFT_KEY_FILE}" not in run):
            problems.append(f"{where}: the step holding {SWIFT_KEY} does not write it with umask 077"
                            f" and mode 600 to {SWIFT_KEY_FILE}")
        if not all(option in run for option in SWIFT_SSH_OPTIONS) or HOST_KEY_CHECKING_OFF.search(run):
            problems.append(f"{where}: the step holding {SWIFT_KEY} does not connect with host-key"
                            " checking on against a pinned known_hosts file")
        before = [str(earlier.get("run", "")) for earlier in steps[:index]]
        after = [str(later.get("run", "")) for later in steps[index + 1:]]
        if name == "release.yml" and (step.get("if") != REAL_RUN or not any(
                "publishAndReleaseToMavenCentral" in text for text in before)):
            problems.append(f"{where} pushes the Swift package before Maven Central says PUBLISHED, or on"
                            " a dry run")
        if name == "release-resume.yml" and (
                not any("release.py check-resume" in text for text in before)
                or not any("release.py push-tag" in text for text in after)
                or any(re.search(TAGS_THIS_REPOSITORY, text) for text in before)):
            problems.append(f"{where} pushes the Swift package before the resume check, or after this"
                            " repository's tag")
        removed = [later for later in steps[index + 1:]
                   if str(later.get("if", "")).startswith("always()")
                   and str(later.get("run", "")).strip() == f"rm -f {SWIFT_KEY_FILE}"]
        if not removed:
            problems.append(f"{where} does not remove the deploy key under always() after the step that"
                            " uses it")
    return problems


def framework_problems(where: str, job: dict, steps: list[dict]) -> list[str]:
    """Rule 9: the framework job, on GitHub's macOS runner, holds nothing and skips no check."""
    problems = []
    if not re.fullmatch(r"macos-\d+", str(job.get("runs-on"))):
        problems.append(f"{where} runs on {job.get('runs-on')!r}, not a GitHub-hosted macOS label")
    first = steps[0] if steps else {}
    if first.get("if") != HOSTED_GUARD_IF or "exit 1" not in str(first.get("run", "")):
        problems.append(f"{where} does not start by checking that it is on a GitHub-hosted runner")
    if job.get("permissions") != {"contents": "read"}:
        problems.append(f"{where} holds {job.get('permissions')}, not exactly {{'contents': 'read'}}")
    if re.search(r"\bsecrets\b", json.dumps(job)) or "environment" in job:
        problems.append(f"{where} reads a secret or uses an environment")
    for command in FRAMEWORK_STEPS:
        running = [step for step in steps if str(step.get("run", "")).strip().startswith(command)
                   or f"\n{command}" in str(step.get("run", ""))]
        if not running or any("if" in step or step.get("continue-on-error", False) is not False
                              for step in running):
            problems.append(f"{where} does not run `{command}` in a step that always runs")
    return problems


def draft_problems(where: str, job: dict, steps: list[dict], needs: list) -> list[str]:
    """Rule 9: the draft job writes a draft release and nothing else, on a real run only."""
    problems = []
    if job.get("permissions") != {"contents": "write"} or job.get("if") != REAL_RUN or "framework" not in needs:
        problems.append(f"{where} does not hold exactly contents: write, run only on a real run"
                        f" (`if: {REAL_RUN}`) and need the framework")
    if re.search(r"\bsecrets\b", json.dumps(job)) or "environment" in job:
        problems.append(f"{where} reads a secret or uses an environment")
    for step in steps:
        uses = str(step.get("uses", ""))
        with_ = step.get("with") if isinstance(step.get("with"), dict) else {}
        if uses.startswith("actions/checkout@") and with_.get("persist-credentials") is not False:
            problems.append(f"{where} keeps a checkout's credentials")
        if "github.token" in json.dumps(step) and not str(step.get("run", "")).strip().startswith(DRAFT_RELEASE):
            problems.append(f"{where} gives its token to a step that does not create the draft release")
    return problems


def docs_problems(name: str, where: str, job: dict, needs: list) -> list[str]:
    """Rule 9: the documentation site is published at the tag, after finish, with Pages alone."""
    problems = []
    if job.get("permissions") != DOCS_PERMISSIONS:
        problems.append(f"{where} holds {job.get('permissions')}, not exactly {DOCS_PERMISSIONS}")
    if "secrets" in job:
        problems.append(f"{where} passes secrets to the documentation site's workflow")
    if needs != ["finish"]:
        problems.append(f"{where} needs {needs}, not finish alone")
    if (job.get("with") or {}).get("ref") != DOCS_REF:
        problems.append(f"{where} builds {(job.get('with') or {}).get('ref')!r}, not the released tag {DOCS_REF!r}")
    if " ".join(str(job.get("if", "")).split()) != DOCS_IF[name]:
        problems.append(f"{where} runs if {job.get('if')!r}, not {DOCS_IF[name]!r}")
    return problems


def verify_problems(where: str, job: dict, steps: list[dict]) -> list[str]:
    """release.yml's verify: all history and tags, and every guard, none of them skippable."""
    problems = []
    checkouts = [step for step in steps if str(step.get("uses", "")).startswith("actions/checkout@")]
    if not checkouts or any((step.get("with") or {}).get("fetch-depth") != 0 for step in checkouts):
        problems.append(f"{where} does not check out all history and tags (`fetch-depth: 0`), so"
                        " the version guards would find no tag and pass any version")
    if job.get("continue-on-error", False) is not False:
        problems.append(f"{where} may continue on error")
    for command in VERIFY_STEPS:
        running = [step for step in steps if str(step.get("run", "")).strip().startswith(command)]
        if not running or any("if" in step or step.get("continue-on-error", False) is not False
                              for step in running):
            problems.append(f"{where} does not run `{command}` as a step of its own that always"
                            " runs")
    return problems


def release_problems(name: str, document: dict) -> list[str]:
    """Rule 9, for one workflow."""
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
    if "toJSON(secrets)" in json.dumps(document.get("env", {})).replace(" ", ""):
        problems.append(f"{name}: its environment passes toJSON(secrets), every secret at once")
    for job_id, job in jobs.items():
        problems += release_job_problems(name, job_id, job if isinstance(job, dict) else {})
    if name == "release.yml":
        if "verify" not in jobs:
            problems.append(f"{name}: has no verify job")
        inputs = (document.get("on") or {}).get("workflow_dispatch") if isinstance(
            document.get("on"), dict) else None
        dry_run = ((inputs or {}).get("inputs") or {}).get("dry_run") if isinstance(inputs, dict) else None
        if not isinstance(dry_run, dict) or dry_run.get("type") != "boolean" or dry_run.get(
                "default") is not True:
            problems.append(f"{name}: its dry_run input is {dry_run!r}, not a boolean defaulting to"
                            " true")
        for job_id in ("framework", "draft"):
            if job_id not in jobs:
                problems.append(f"{name}: has no {job_id} job")
    if name in RELEASE_WORKFLOWS:
        if not any(isinstance(job, dict) and job.get("uses") == DOCS_WORKFLOW for job in jobs.values()):
            problems.append(f"{name}: does not publish the documentation site ({DOCS_WORKFLOW})")
        if not any(isinstance(job, dict) and any(
                secret_references(json.dumps(step.get("env", {})), SWIFT_KEY) for step in steps_of(job))
                for job in jobs.values()):
            problems.append(f"{name}: does not publish the Swift package")
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
        runs_action = "uses" in job or any(isinstance(step, dict) and "uses" in step
                                           for step in steps)
        if writes and runs_action:
            problems.append(f"{name}: job {job_id} holds a write permission and runs an action")
        problems += build_user_problems(f"{name}: job {job_id}", steps)
    if reads_secrets(document):
        problems.append(f"{name}: reads a secret")
    return problems


def build_user_problems(where: str, steps: list) -> list[str]:
    """Rule 8: Gradle runs only as the build user, prepared before the build and stopped after."""
    steps = [step if isinstance(step, dict) else {} for step in steps]
    runs = [" ".join(str(step.get("run", "")).split()) for step in steps]
    build = BUILD_USER_SCRIPT + " build "
    problems = []
    for index, text in enumerate(runs):
        if not text.startswith(build):
            if RUNS_GRADLE.search(text):
                problems.append(f"{where} runs Gradle outside the unprivileged build user")
            continue
        if not all(GRADLE_ARGUMENT.fullmatch(argument) for argument in text[len(build):].split()):
            problems.append(f"{where} passes the build user more than Gradle arguments")
        prepared = [before for before in range(index)
                    if runs[before] == BUILD_USER_SCRIPT + " prepare"]
        if not prepared or steps[prepared[-1]].get("continue-on-error", False) is not False:
            problems.append(f"{where} builds without first preparing the build user")
        collected = [after for after in range(index + 1, len(runs))
                     if runs[after].startswith(COLLECT)]
        condition = " ".join(str(steps[collected[0]].get("if", "")).split()) if collected else ""
        if (not collected or not condition.startswith("always()")
                or steps[collected[0]].get("continue-on-error", False) is not False):
            problems.append(f"{where} does not stop the build user under always() after the build")
        # An action has neither a shell nor a run line, so it fails this too.
        for after in range(index + 1, len(steps)):
            if steps[after].get("shell") != ABSOLUTE_SHELL or not runs[after].startswith("/"):
                problems.append(f"{where} has a step after the build that is not an absolute"
                                " command in the absolute shell")
                break
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
        problems += release_problems(path.name, document)
        problems += scheduled_problems(path.name, document)
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
      - id: version
        run: python3 scripts/release.py snapshot-version
      - if: {SNAPSHOT_ONLY}
        env:
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
# The step that pushes the Swift package with the deploy key, in either release workflow.
SWIFT_STEP = """\
        env:
          SWIFT_PACKAGE_DEPLOY_KEY: ${{ secrets.SWIFT_PACKAGE_DEPLOY_KEY }}
        run: |
          key="$RUNNER_TEMP/swift-package-deploy-key"
          (umask 077 && printenv SWIFT_PACKAGE_DEPLOY_KEY > "$key")
          chmod 600 "$key"
          export GIT_SSH_COMMAND="ssh -i $key -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$h"
          python3 scripts/swift-release.py publish --remote git@github.com:o/r.git
      - if: always()
        run: rm -f "$RUNNER_TEMP/swift-package-deploy-key"
"""
COMPLIANT_DOCS_PUBLISH = """\
on: {workflow_call: {inputs: {ref: {type: string}}}, workflow_dispatch: {}}
permissions: {}
jobs:
  build: {runs-on: ubuntu-latest, permissions: {contents: read}, steps: [{run: echo}]}
"""
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
      - run: python3 scripts/api-compat.py check-release
      - run: python3 scripts/check-published-artifacts.py
  framework:
    needs: verify
    if: {RELEASE_GUARD_IF}
    runs-on: macos-26
    permissions: {{contents: read}}
    steps:
      - if: {HOSTED_GUARD_IF}
        run: exit 1
      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567
        with: {{persist-credentials: false}}
      - run: python3 scripts/swift-release.py build --out o
      - run: python3 scripts/check-bridge-header.py
      - run: python3 scripts/swift-release.py zip --framework f --out z
      - run: bash scripts/test-swift-package.sh --framework f
      - run: |
          python3 scripts/swift-release.py publish --dry-run --out t
          python3 scripts/swift-release.py read-manifest --tree t
  draft:
    needs: framework
    if: ${{{{ !inputs.dry_run }}}}
    runs-on: ubuntu-latest
    permissions: {{contents: write}}
    steps:
      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567
        with: {{persist-credentials: false}}
      - env: {{GH_TOKEN: '${{{{ github.token }}}}'}}
        run: {DRAFT_RELEASE} --zip z
  publish:
    needs: [verify, framework, draft]
    if: >-
      {PUBLISH_IF}
    runs-on: ubuntu-latest
    environment: release
    permissions: {{contents: read}}
    steps:
      - run: python3 scripts/swift-release.py publish --dry-run --out t
      - if: inputs.dry_run
        env:
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{{{ secrets.GPG_KEY_CONTENTS }}}}
        run: ./gradlew publishToMavenCentral
      - if: ${{{{ !inputs.dry_run }}}}
        env:
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{{{ secrets.GPG_KEY_CONTENTS }}}}
        run: ./gradlew publishAndReleaseToMavenCentral {WAIT_FOR_PUBLISHED}
      - name: Publish the Swift package
        if: ${{{{ !inputs.dry_run }}}}
{SWIFT_STEP}  finish:
    needs: [verify, publish]
    if: ${{{{ !inputs.dry_run }}}}
    runs-on: ubuntu-latest
    permissions: {{contents: write}}
    outputs: {{version: '${{{{ steps.version.outputs.version }}}}'}}
    steps:
      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567
        with: {{persist-credentials: true}}
      - id: version
        run: python3 scripts/release.py release-version
      - env: {{SHA: '${{{{ github.sha }}}}', VERSION: '${{{{ steps.version.outputs.version }}}}'}}
        run: python3 scripts/release.py push-tag
  docs:
    needs: finish
    if: >-
      {DOCS_IF["release.yml"]}
    permissions: {{contents: read, pages: write, id-token: write}}
    uses: {DOCS_WORKFLOW}
    with: {{ref: '{DOCS_REF}'}}
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
    permissions: {{contents: write}}
    outputs: {{version: '${{{{ steps.version.outputs.version }}}}'}}
    steps:
      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567
        with: {{persist-credentials: true}}
      - run: python3 scripts/release.py check-resume
      - env: {{GH_TOKEN: '${{{{ github.token }}}}'}}
        run: python3 scripts/swift-release.py download-framework
      - name: Publish the Swift package
{SWIFT_STEP}      - run: python3 scripts/release.py push-tag
  docs:
    needs: finish
    if: {RELEASE_GUARD_IF}
    permissions: {{contents: read, pages: write, id-token: write}}
    uses: {DOCS_WORKFLOW}
    with: {{ref: '{DOCS_REF}'}}
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
    steps:
      - uses: $/.github/actions/setup
      - run: {BUILD_USER_SCRIPT} prepare
      - id: build
        continue-on-error: true
        run: >-
          {BUILD_USER_SCRIPT} build
          :a:testAndroidHostTest --continue
      - if: always() && steps.versions.outputs.newer == 'true'
        shell: {ABSOLUTE_SHELL}
        run: /usr/bin/{BUILD_USER_SCRIPT} collect "$RUNNER_TEMP/results" testAndroidHostTest a
      - shell: {ABSOLUTE_SHELL}
        run: /usr/bin/python3 -I -c pass
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
    # Rule 9: releases.
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
     "does not start with the Dependabot guard, or does not require main"),
    ("the release environment off main", "release.yml",
     COMPLIANT_RELEASE.replace(" && github.ref == 'refs/heads/main'", ""),
     "does not start with the Dependabot guard, or does not require main"),
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
    # The review of the release workflow: what gives the gate and the guards their meaning.
    ("a cache restored in publish", "release.yml",
     COMPLIANT_RELEASE.replace("      - if: inputs.dry_run\n",
                               "      - uses: actions/cache/restore@0123456789abcdef0123456789abcdef01234567\n"
                               "        with: {path: ~/.gradle, key: gradle}\n      - if: inputs.dry_run\n"),
     "job publish restores a cache (actions/cache/restore"),
    ("setup-java asked to cache in verify", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/release.py check-version\n",
                               "      - uses: actions/setup-java@0123456789abcdef0123456789abcdef01234567\n"
                               "        with: {distribution: temurin, java-version: '17', cache: gradle}\n"
                               "      - run: python3 scripts/release.py check-version\n"),
     "job verify restores a cache (actions/setup-java"),
    ("setup-gradle in the snapshot job", "ci.yml",
     COMPLIANT_CI.replace("      - id: version\n",
                          "      - uses: gradle/actions/setup-gradle@0123456789abcdef0123456789abcdef01234567\n"
                          "      - id: version\n"),
     "job snapshot restores a cache (gradle/actions/setup-gradle"),
    ("the setup-project action in the resume job", "release-resume.yml",
     COMPLIANT_RESUME.replace("      - run: python3 scripts/release.py check-resume",
                              "      - uses: $/.github/actions/setup-project\n"
                              "      - run: python3 scripts/release.py check-resume"),
     "job finish restores a cache ($/.github/actions/setup-project)"),
    ("verify without all history and tags", "release.yml",
     COMPLIANT_RELEASE.replace("with: {fetch-depth: 0, persist-credentials: false}",
                               "with: {persist-credentials: false}"),
     "does not check out all history and tags"),
    ("verify without the release check", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/api-compat.py check-release\n", ""),
     "does not run `python3 scripts/api-compat.py check-release`"),
    ("verify's version guard skipped", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/release.py check-version\n",
                               "      - if: false\n        run: python3 scripts/release.py check-version\n"),
     "does not run `python3 scripts/release.py check-version`"),
    ("verify's version guard replaced by an echo", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/release.py check-version\n",
                               "      - run: echo version=0.1.0 >> $GITHUB_OUTPUT\n"),
     "does not run `python3 scripts/release.py check-version`"),
    ("verify without the published-artifacts check", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/check-published-artifacts.py\n", ""),
     "does not run `python3 scripts/check-published-artifacts.py`"),
    ("publish not needing verify", "release.yml",
     COMPLIANT_RELEASE.replace("  publish:\n    needs: [verify, framework, draft]\n",
                               "  publish:\n    needs: [framework, draft]\n"),
     "job publish does not need verify"),
    ("the real publish not waiting for PUBLISHED", "release.yml",
     COMPLIANT_RELEASE.replace(f" {WAIT_FOR_PUBLISHED}", ""),
     "releases without -PmavenCentralDeploymentValidation=PUBLISHED"),
    ("the snapshot publish without its snapshot-only condition", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {SNAPSHOT_ONLY}\n", "      - if: always()\n"),
     "publishes without the snapshot-only condition"),
    ("publish checking out verify's commit", "release.yml",
     COMPLIANT_RELEASE.replace("      - if: inputs.dry_run\n",
                               "      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567\n"
                               "        with: {ref: '${{ needs.verify.outputs.sha }}', persist-credentials: false}\n"
                               "      - if: inputs.dry_run\n"),
     "job publish checks out the ref"),
    ("finish tagging the commit verify reports", "release.yml",
     COMPLIANT_RELEASE.replace("SHA: '${{ github.sha }}'", "SHA: '${{ needs.verify.outputs.sha }}'"),
     "job finish reads verify's outputs"),
    ("toJSON(secrets) in verify", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/release.py check-version\n",
                               "      - env: {ALL: '${{ toJSON(secrets) }}'}\n"
                               "        run: python3 scripts/release.py check-version\n"),
     "job verify passes toJSON(secrets)"),
    ("a release secret by index in verify", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/release.py check-version\n",
                               "      - env: {KEY: \"${{ secrets['GPG_KEY_CONTENTS'] }}\"}\n"
                               "        run: python3 scripts/release.py check-version\n"),
     "job verify reads the secret GPG_KEY_CONTENTS"),
    ("a step holding the secrets traces itself", "release.yml",
     COMPLIANT_RELEASE.replace("run: ./gradlew publishAndReleaseToMavenCentral",
                               "run: set -x; ./gradlew publishAndReleaseToMavenCentral"),
     "traces itself or expands them"),
    ("a step holding the secrets echoes one", "ci.yml",
     COMPLIANT_CI.replace("run: ./gradlew publishToMavenCentral",
                          "run: echo \"$ORG_GRADLE_PROJECT_mavenCentralUsername\"; ./gradlew publishToMavenCentral"),
     "traces itself or expands them"),
    ("the resume path running the system Gradle", "release-resume.yml",
     COMPLIANT_RESUME.replace("python3 scripts/release.py check-resume", "gradle publish"),
     "the resume path only tags"),
    ("a release secret in a step of publish that does not publish", "release.yml",
     COMPLIANT_RELEASE.replace("      - if: inputs.dry_run\n",
                               "      - env: {KEY: '${{ secrets.SIGNING_PASSWORD }}'}\n        run: echo done\n"
                               "      - if: inputs.dry_run\n"),
     "job publish reads the secret SIGNING_PASSWORD outside the environment of a publishing step"),
    ("the aggregate publish task in finish", "release.yml",
     COMPLIANT_RELEASE.replace("        run: python3 scripts/release.py push-tag",
                               "        run: ./gradlew publish && python3 scripts/release.py push-tag"),
     "job finish publishes to Maven Central"),
    ("an `|| true` inside the release guard", "release.yml",
     COMPLIANT_RELEASE.replace("github.ref == 'refs/heads/main')", "github.ref == 'refs/heads/main' || true)"),
     "or has an `||`"),
    # The Swift package, its deploy key and its framework, and the documentation site.
    ("the deploy key read by another job", "release.yml",
     COMPLIANT_RELEASE.replace("      - env: {SHA: '${{ github.sha }}', VERSION:",
                               "      - env: {KEY: '${{ secrets.SWIFT_PACKAGE_DEPLOY_KEY }}', SHA: '${{ github.sha }}', VERSION:"),
     "job finish reads the secret SWIFT_PACKAGE_DEPLOY_KEY outside"),
    ("the deploy key in the dry run's staging step", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/swift-release.py publish --dry-run --out t\n      - if: inputs.dry_run",
                               "      - env: {KEY: '${{ secrets.SWIFT_PACKAGE_DEPLOY_KEY }}'}\n"
                               "        run: python3 scripts/swift-release.py publish --dry-run --out t\n"
                               "      - if: inputs.dry_run"),
     "job publish reads the secret SWIFT_PACKAGE_DEPLOY_KEY outside"),
    ("the deploy key in a step that also runs Gradle", "release.yml",
     COMPLIANT_RELEASE.replace("python3 scripts/swift-release.py publish --remote",
                               "./gradlew help && python3 scripts/swift-release.py publish --remote"),
     "the step holding SWIFT_PACKAGE_DEPLOY_KEY also runs a build"),
    ("the deploy key with host-key checking off", "release.yml",
     COMPLIANT_RELEASE.replace("-o StrictHostKeyChecking=yes", "-o StrictHostKeyChecking=no"),
     "does not connect with host-key checking on"),
    ("the deploy key with new host keys accepted", "release-resume.yml",
     COMPLIANT_RESUME.replace("-o StrictHostKeyChecking=yes", "-o StrictHostKeyChecking=yes -o StrictHostKeyChecking=accept-new"),
     "does not connect with host-key checking on"),
    ("the deploy key without a pinned known_hosts file", "release.yml",
     COMPLIANT_RELEASE.replace(" -o UserKnownHostsFile=$h", ""),
     "does not connect with host-key checking on"),
    ("the Swift package pushed with force", "release.yml",
     COMPLIANT_RELEASE.replace("publish --remote git@github.com:o/r.git",
                               "publish --remote git@github.com:o/r.git\n          git push --force origin HEAD"),
     "a step pushes with force"),
    ("a forced refspec in the resume", "release-resume.yml",
     COMPLIANT_RESUME.replace("      - run: python3 scripts/release.py push-tag",
                              "      - run: git push origin +HEAD:refs/tags/0.1.0"),
     "a step pushes with force"),
    ("the Swift package's tag pushed before the Central step", "release.yml",
     COMPLIANT_RELEASE.replace("      - if: ${{ !inputs.dry_run }}\n        env:\n          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_KEY_CONTENTS }}\n"
                               f"        run: ./gradlew publishAndReleaseToMavenCentral {WAIT_FOR_PUBLISHED}\n", "")
     .replace("  finish:\n", "      - if: ${{ !inputs.dry_run }}\n        env:\n"
              "          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_KEY_CONTENTS }}\n"
              f"        run: ./gradlew publishAndReleaseToMavenCentral {WAIT_FOR_PUBLISHED}\n  finish:\n", 1),
     "pushes the Swift package before Maven Central says PUBLISHED"),
    ("the Swift package pushed on a dry run", "release.yml",
     COMPLIANT_RELEASE.replace("      - name: Publish the Swift package\n        if: ${{ !inputs.dry_run }}\n",
                               "      - name: Publish the Swift package\n"),
     "pushes the Swift package before Maven Central says PUBLISHED, or on a dry run"),
    ("the resume pushing the Swift package after this repository's tag", "release-resume.yml",
     COMPLIANT_RESUME.replace("      - run: python3 scripts/release.py push-tag\n", "")
     .replace("      - name: Publish the Swift package\n",
              "      - run: python3 scripts/release.py push-tag\n      - name: Publish the Swift package\n"),
     "or after this repository's tag"),
    ("the resume tagging this repository before the Swift package", "release-resume.yml",
     COMPLIANT_RESUME.replace("      - name: Publish the Swift package\n",
                              "      - run: python3 scripts/release.py publish-github-release\n"
                              "      - name: Publish the Swift package\n"),
     "or after this repository's tag"),
    ("publish tagging this repository", "release.yml",
     COMPLIANT_RELEASE.replace("      - name: Publish the Swift package\n",
                               "      - run: python3 scripts/release.py push-tag\n"
                               "      - name: Publish the Swift package\n"),
     "job publish tags this repository"),
    ("the deploy key not removed under always()", "release.yml",
     COMPLIANT_RELEASE.replace("      - if: always()\n        run: rm -f", "      - if: success()\n        run: rm -f"),
     "does not remove the deploy key under always()"),
    ("the deploy key expanded in the script", "release-resume.yml",
     COMPLIANT_RESUME.replace("(umask 077 && printenv SWIFT_PACKAGE_DEPLOY_KEY > \"$key\")",
                              "(umask 077 && echo \"$SWIFT_PACKAGE_DEPLOY_KEY\" > \"$key\")"),
     "traces itself or expands them"),
    ("the deploy key written without its mode", "release.yml",
     COMPLIANT_RELEASE.replace('          chmod 600 "$key"\n', ""),
     "does not write it with umask 077 and mode 600"),
    ("the resume reading another secret beside the deploy key", "release-resume.yml",
     COMPLIANT_RESUME.replace("      - run: python3 scripts/release.py push-tag",
                              "      - env: {T: '${{ secrets.MAVEN_CENTRAL_PASSWORD }}'}\n"
                              "        run: python3 scripts/release.py push-tag"),
     "the resume path only tags"),
    ("the resume building the framework", "release-resume.yml",
     COMPLIANT_RESUME.replace("python3 scripts/swift-release.py download-framework",
                              "python3 scripts/swift-release.py build --out o"),
     "builds or zips the Swift package's framework"),
    ("publish zipping the framework again", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/swift-release.py publish --dry-run --out t\n      - if: inputs.dry_run",
                               "      - run: python3 scripts/swift-release.py zip --framework f --out z2\n"
                               "      - if: inputs.dry_run"),
     "job publish builds or zips"),
    ("the framework job on the self-hosted runner", "release.yml",
     COMPLIANT_RELEASE.replace("runs-on: macos-26", "runs-on: [self-hosted, macOS, offline-kt]"),
     "job framework names the self-hosted runner"),
    ("the framework job on Linux", "release.yml",
     COMPLIANT_RELEASE.replace("runs-on: macos-26", "runs-on: ubuntu-latest"),
     "not a GitHub-hosted macOS label"),
    ("the framework job without its hosted-runner check", "release.yml",
     COMPLIANT_RELEASE.replace(f"      - if: {HOSTED_GUARD_IF}\n        run: exit 1\n", ""),
     "does not start by checking that it is on a GitHub-hosted runner"),
    ("the framework job holding the release environment", "release.yml",
     COMPLIANT_RELEASE.replace("    runs-on: macos-26\n", "    runs-on: macos-26\n    environment: release\n"),
     "job framework uses the environment 'release'"),
    ("the framework job reading a secret", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/check-bridge-header.py",
                               "      - env: {T: '${{ secrets.SIGNING_PASSWORD }}'}\n"
                               "        run: python3 scripts/check-bridge-header.py"),
     "job framework reads a secret or uses an environment"),
    ("the framework job able to write", "release.yml",
     COMPLIANT_RELEASE.replace("    runs-on: macos-26\n    permissions: {contents: read}",
                               "    runs-on: macos-26\n    permissions: {contents: write}"),
     "job framework holds"),
    ("the framework's Swift tests skipped", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: bash scripts/test-swift-package.sh",
                               "      - if: false\n        run: bash scripts/test-swift-package.sh"),
     "does not run `bash scripts/test-swift-package.sh`"),
    ("another job reading the framework job's outputs", "release.yml",
     COMPLIANT_RELEASE.replace("run: {} --zip z".format(DRAFT_RELEASE),
                               "run: {} --zip ${{{{ needs.framework.outputs.zip }}}}".format(DRAFT_RELEASE)),
     "reads the framework job's outputs"),
    ("the draft job on a dry run", "release.yml",
     COMPLIANT_RELEASE.replace("  draft:\n    needs: framework\n    if: ${{ !inputs.dry_run }}\n",
                               "  draft:\n    needs: framework\n"),
     "job draft does not hold exactly contents: write"),
    ("the draft job with another permission", "release.yml",
     COMPLIANT_RELEASE.replace("    permissions: {contents: write}\n    steps:\n      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567\n"
                               "        with: {persist-credentials: false}\n      - env: {GH_TOKEN",
                               "    permissions: {contents: write, pull-requests: write}\n    steps:\n"
                               "      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567\n"
                               "        with: {persist-credentials: false}\n      - env: {GH_TOKEN"),
     "job draft does not hold exactly contents: write"),
    ("the draft job keeping its checkout's credentials", "release.yml",
     COMPLIANT_RELEASE.replace("        with: {persist-credentials: false}\n      - env: {GH_TOKEN",
                               "        with: {persist-credentials: true}\n      - env: {GH_TOKEN"),
     "job draft keeps a checkout's credentials"),
    ("the draft job's token in another step", "release.yml",
     COMPLIANT_RELEASE.replace(f"        run: {DRAFT_RELEASE} --zip z",
                               f"        run: gh release create 0.1.0 && {DRAFT_RELEASE} --zip z"),
     "gives its token to a step that does not create the draft release"),
    ("publish not needing the draft", "release.yml",
     COMPLIANT_RELEASE.replace("needs: [verify, framework, draft]", "needs: [verify, framework]"),
     "does not need verify, the framework and the draft"),
    ("publish without verify's result in its condition", "release.yml",
     COMPLIANT_RELEASE.replace(" && needs.verify.result == 'success'", ""),
     "job publish runs if"),
    ("no documentation site in the release", "release.yml",
     COMPLIANT_RELEASE[:COMPLIANT_RELEASE.index("  docs:")],
     "release.yml: does not publish the documentation site"),
    ("the documentation job holding contents: write", "release.yml",
     COMPLIANT_RELEASE.replace("permissions: {contents: read, pages: write, id-token: write}",
                               "permissions: {contents: write, pages: write, id-token: write}"),
     "job docs holds"),
    ("the documentation job on a dry run", "release.yml",
     COMPLIANT_RELEASE.replace("&& (!inputs.dry_run)", "&& (true)"),
     "job docs runs if"),
    ("the documentation job passing secrets", "release-resume.yml",
     COMPLIANT_RESUME.replace("    uses: $/.github/workflows/docs-publish.yml\n",
                              "    uses: $/.github/workflows/docs-publish.yml\n    secrets: inherit\n"),
     "passes secrets to the documentation site's workflow"),
    ("the documentation built from main, not the tag", "release.yml",
     COMPLIANT_RELEASE.replace(f"with: {{ref: '{DOCS_REF}'}}", "with: {ref: main}"),
     "not the released tag"),
    ("the documentation published before finish", "release-resume.yml",
     COMPLIANT_RESUME.replace("  docs:\n    needs: finish\n", "  docs:\n    needs: []\n"),
     "not finish alone"),
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
    ("a reporting job that calls a reusable workflow", "upstream-canary.yml",
     COMPLIANT_CANARY[:COMPLIANT_CANARY.index("  report:")]
     + "  report:\n    needs: canary\n"
     + f"    if: {REPOSITORY_GUARD} && (needs.canary.outputs.newer == 'true')\n"
     + "    permissions: {issues: write}\n    uses: $/.github/workflows/docs-check.yml\n",
     "job report holds a write permission and runs an action"),
    ("the build runs the Gradle wrapper directly", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"{BUILD_USER_SCRIPT} build\n", "./gradlew\n"),
     "runs Gradle outside the unprivileged build user"),
    ("the build runs run-gradle.sh as the runner user", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"{BUILD_USER_SCRIPT} build\n",
                              "bash .github/scripts/run-gradle.sh\n"),
     "runs Gradle outside the unprivileged build user"),
    ("a command after the build user's Gradle arguments", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(":a:testAndroidHostTest --continue\n",
                              ":a:testAndroidHostTest --continue; curl example.com\n"),
     "passes the build user more than Gradle arguments"),
    ("no step prepares the build user", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"      - run: {BUILD_USER_SCRIPT} prepare\n", ""),
     "builds without first preparing the build user"),
    ("the build user is prepared after the build", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"      - run: {BUILD_USER_SCRIPT} prepare\n", "").replace(
         "  report:\n",
         f"      - run: {BUILD_USER_SCRIPT} prepare\n  report:\n"),
     "builds without first preparing the build user"),
    ("preparing the build user may continue on error", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"      - run: {BUILD_USER_SCRIPT} prepare\n",
                              f"      - run: {BUILD_USER_SCRIPT} prepare\n"
                              "        continue-on-error: true\n"),
     "builds without first preparing the build user"),
    ("the build user is stopped only when the build passed", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("      - if: always() && steps", "      - if: steps"),
     "does not stop the build user under always()"),
    ("stopping the build user may continue on error", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("      - if: always() && steps",
                              "      - continue-on-error: true\n        if: always() && steps"),
     "does not stop the build user under always()"),
    ("no step stops the build user", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("      - if: always() && steps.versions.outputs.newer == 'true'\n"
                              f"        shell: {ABSOLUTE_SHELL}\n"
                              f"        run: /usr/bin/{BUILD_USER_SCRIPT} collect"
                              " \"$RUNNER_TEMP/results\" testAndroidHostTest a\n", ""),
     "does not stop the build user under always()"),
    ("the build user is stopped through the inherited PATH", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"        run: /usr/bin/{BUILD_USER_SCRIPT} collect",
                              f"        run: {BUILD_USER_SCRIPT} collect"),
     "does not stop the build user under always()"),
    ("a step after the build in the default shell", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"      - shell: {ABSOLUTE_SHELL}\n        run: /usr/bin/python3",
                              "      - run: /usr/bin/python3"),
     "a step after the build that is not an absolute command"),
    ("a step after the build finds python3 through the PATH", "upstream-canary.yml",
     COMPLIANT_CANARY.replace("run: /usr/bin/python3 -I -c pass", "run: python3 -I -c pass"),
     "a step after the build that is not an absolute command"),
    ("an action after the build", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"      - shell: {ABSOLUTE_SHELL}\n"
                              "        run: /usr/bin/python3 -I -c pass\n",
                              "      - uses: $/.github/actions/setup\n"),
     "a step after the build that is not an absolute command"),
    ("the image's own gradle command", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"      - run: {BUILD_USER_SCRIPT} prepare\n",
                              f"      - run: gradle -p . help\n"
                              f"      - run: {BUILD_USER_SCRIPT} prepare\n"),
     "runs Gradle outside the unprivileged build user"),
    ("the image's gradle by its path", "upstream-canary.yml",
     COMPLIANT_CANARY.replace(f"      - run: {BUILD_USER_SCRIPT} prepare\n",
                              f"      - run: /usr/bin/gradle help\n"
                              f"      - run: {BUILD_USER_SCRIPT} prepare\n"),
     "runs Gradle outside the unprivileged build user"),
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
    (root / ".github" / "workflows" / "release.yml").write_text(COMPLIANT_RELEASE)
    (root / ".github" / "workflows" / "release-resume.yml").write_text(COMPLIANT_RESUME)
    (root / ".github" / "workflows" / "docs-publish.yml").write_text(COMPLIANT_DOCS_PUBLISH)
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
            target = root / ".github" / "workflows" / file_name
            if content is None:
                target.unlink()
            else:
                # A replacement that matched nothing would test the compliant file again.
                if target.exists() and target.read_text() == content:
                    print(f"FAIL {name}: the case changes nothing in {file_name}")
                    failures += 1
                    continue
                target.write_text(content)
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
