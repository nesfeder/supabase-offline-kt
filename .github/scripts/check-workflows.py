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
  3. any job names a self-hosted runner, anywhere in the job, or runs on anything but a literal
     GitHub-hosted label; or a job calls a reusable workflow other than one of this repository's at
     the same commit (`$/`);
  4. ci.yml's final-status does not need every other job but the coverage jobs, needs one of them
     (a Codecov outage must never block a merge), or its condition differs from the one below;
  5. a `uses: $/...` reference names an action or workflow that does not exist;
  6. a job not listed below may mint an OIDC token (`id-token: write`, or `write-all`), or a
     workflow's default permissions do; or a listed job holds other permissions than its own, or
     its condition does not start with the Dependabot guard as a conjunct of its own, so a run
     Dependabot pushed or started never holds the token.
  7. ci.yml's ios-tests does not start with the step that stops it on any runner but GitHub's
     own, or the job or that step may continue on error.
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
       resume check and before this repository's tag). That step runs no build and pushes
       nothing by itself (the script does), writes the key with umask 077 and mode 600 under
       RUNNER_TEMP and uses that file only for ssh, and connects with IdentitiesOnly and host-key
       checking on against the known_hosts file the script writes; a later step whose condition is
       exactly always() removes it, and no other step touches it. No step of a release workflow
       pushes with force (`--force`, `-f`, a `+` refspec, quoted or not, `--mirror`), and no job
       or step of one may continue on error. A Central step runs with no Gradle daemon and the
       Kotlin compiler in-process, so no build process outlives it into the key's step.
     - The framework: only release.yml's framework job builds and zips it, on a GitHub-hosted
       macOS runner it checks it is on, with `contents: read` alone and no secret; nothing reads
       its outputs. The draft job holds `contents: write` alone and no secret, runs on a real run
       only after the framework, keeps no checkout credentials, and gives its token only to a step
       that runs the one command that creates the draft release. No job of release.yml but finish
       runs `gh release`, `git push` or `git tag`. publish needs verify, the framework and the draft,
       under the exact condition below. The resume path takes the framework from the release and
       builds nothing.
     - The documentation site: each release workflow has a job that calls docs-publish.yml at the
       released tag, after finish, holding exactly the Pages and OIDC permissions and
       `contents: read`, passing no secret, and skipped on a dry run. The Swift API reference it
       places under /swift/ is built (build-swift-docs.sh) and uploaded (artifact swift-reference,
       one tar) by release.yml's framework job alone, which holds no secret and no environment;
       release.yml's docs job names that artifact, and the resume's, whose run has none, names none.
       No other job of a release workflow downloads an artifact but the framework's zip, by name
       alone (no other run, no token) into the runner's temporary directory, and none holds an
       Actions permission. docs-publish.yml's build job holds `contents: read` alone, downloads only
       the artifact the caller names, when it names one, from the calling run with the default token,
       into the runner's temporary directory, and directly after that unpacks it into site/swift with
       the one pinned step (`mkdir -p` and `tar -xf … -C site/swift --strip-components=1`, which
       writes files and runs nothing); no other step runs, sources, `uses:` or works in anything
       under the site, or names that temporary directory.
     - What they run: only the Central steps run a Maven Central publish task, and only
       release.yml releases, waiting until Central says PUBLISHED; the snapshot job's publish step
       runs only for a -SNAPSHOT version; no job of either release workflow, nor the snapshot job,
       restores a cache (`actions/cache`, setup-gradle, the setup-project action, a setup action's
       `cache:` input) or uses any action but checkout, setup-java, the wrapper validation and the
       artifact upload and download (no local composite action, no other cache action), and a
       release calls no reusable workflow but docs-publish.yml. Both finish jobs hold exactly
       `contents: write`, and no step of a release workflow pushes to main.
     - What they rest on: release.yml's verify job checks out all history and tags, and runs
       `release.py check-version`, `api-compat.py check-release` and the published-artifacts
       check, none of them skippable; no other job reads verify's outputs or checks out a ref of
       its own, so publish signs, and finish tags, the run's own commit.
     - How they start: release.yml and release-resume.yml are started by hand alone, grant nothing
       by default and share the concurrency group `release`, never canceled; release.yml's
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
IOS_JOB = ("ci.yml", "ios-tests")
FAN_IN = ("ci.yml", "final-status")
FAN_IN_IF = "always()"
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
# How every such job's condition begins.
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
SWIFT_SSH_OPTIONS = ("-o IdentitiesOnly=yes", "-o StrictHostKeyChecking=yes", "-o UserKnownHostsFile=$known_hosts")
HOST_KEY_CHECKING_OFF = re.compile(r"StrictHostKeyChecking\s*=?\s*(?!yes\b)\w", re.IGNORECASE)
FORCED_PUSH = re.compile(r"--force|--mirror|\bpush\b[^\n]*\s(-[A-Za-z]*f\b|[\"']?\+\S)")
# What only release.yml's finish job may run: this repository's tags and GitHub Release. The Swift
# package's push goes through scripts/swift-release.py, never a command of the workflow's own.
REPOSITORY_WRITES = re.compile(r"(?<![\w./@-])(gh\b[^\n;&|]*?\srelease|git\b[^\n;&|]*?\s(push|tag))\b")
# A Central step's build leaves no Gradle or Kotlin daemon running after it, holding its secrets,
# when the next step writes the deploy key.
CENTRAL_DAEMON_FLAGS = ("--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process")
SWIFT_KEY_FILE_NAME = "swift-package-deploy-key"
KNOWN_HOSTS_WRITE = 'python3 scripts/swift-release.py known-hosts > "$known_hosts"'
# One command, with nothing chained or substituted after it.
CHAINED = re.compile(r"[;&|`\n]|\$\(")
# The framework: built and zipped by one job on GitHub's macOS runner, staged on a draft release by
# another before the approval, and never built again.
FRAMEWORK_JOB = ("release.yml", "framework")
FRAMEWORK_STEPS = ("python3 scripts/swift-release.py build", "python3 scripts/check-bridge-header.py",
                   "python3 scripts/swift-release.py zip", "bash scripts/test-swift-package.sh",
                   "python3 scripts/swift-release.py read-manifest", "bash scripts/build-swift-docs.sh")
BUILDS_FRAMEWORK = re.compile(r"swift-release\.py (build|zip)\b|test-swift-package|xcodebuild")
# The Swift API reference: built from the framework the framework job tested, by that job alone,
# and handed to the documentation site as this artifact of the run.
SWIFT_REFERENCE = "swift-reference"
BUILDS_REFERENCE = re.compile(r"build-swift-docs\.sh|\bdocc\b")
# The one artifact a release job may download: the framework's zip, hashed and never opened, by
# name from this run (no `run-id`, no token) into the runner's temporary directory.
FRAMEWORK_ARTIFACT = "swift-framework"
FRAMEWORK_DOWNLOAD = {"name": FRAMEWORK_ARTIFACT, "path": "${{ runner.temp }}/swift-release"}
# docs-publish.yml's build job: what its one download takes, and where it puts it.
DOCS_BUILD_JOB = ("docs-publish.yml", "build")
REFERENCE_IF = "inputs.swift-reference != ''"
REFERENCE_DOWNLOAD = {"name": "${{ inputs.swift-reference }}",
                      "path": "${{ runner.temp }}/swift-reference-artifact"}
# The artifact is one tar (DocC's file names hold colons, which the artifact store refuses). This
# step, to the letter, is the one that may write under the site: it writes files and runs nothing.
REFERENCE_UNPACK = {
    "name": "Unpack the Swift API reference",
    "if": REFERENCE_IF,
    "run": ("mkdir -p site/swift\n"
            "tar -xf \"$RUNNER_TEMP/swift-reference-artifact/swift-reference.tar\""
            " -C site/swift --strip-components=1"),
}
# The runner's temporary directory, where the tar lies, however it is spelled.
NAMES_RUNNER_TEMP = re.compile(r"RUNNER_TEMP|runner\s*(?:\.\s*temp|\[\s*'temp')|/_temp\b"
                               r"|swift-reference-artifact", re.IGNORECASE)
# A test of a path (`[[ -f site/… ]]`) reads the site; anything else naming it may run or move it.
SITE_TEST = re.compile(r"\[\[\s*!?\s*-[fde]\s+site/\S*")
# The site directory named as a path, however it is spelled: `site/x`, `./site/x`, `"$X/site/x"`,
# `cd site`, `pushd "site"`; prose such as "this site." is not a path.
TOUCHES_SITE = re.compile(r"(?:^|[\s\"'=:(/]|\./)site(?:/|[\"'\s;)&|]|$)", re.MULTILINE)
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
# The only actions a job of a release workflow, or the snapshot job, may use: none restores a cache
# (setup-java only when asked to, which is refused separately), and none is a local composite action.
RELEASE_ACTIONS = ("actions/checkout@", "actions/setup-java@", "gradle/actions/wrapper-validation@",
                   "actions/upload-artifact@", "actions/download-artifact@")
PUSH_TO_MAIN = re.compile(r"\bpush\b[^\n]*[\s:](refs/heads/)?main\b")
# A Gradle task that can publish to Maven Central: the aggregate `publish` or any Central task.
CENTRAL_TASK = re.compile(r"\bpublish(?:\b|\w*MavenCentral\w*)")
CI_PULL_REQUEST_TYPES = ["opened", "synchronize", "reopened"]
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


def hosted_guard_problems(name: str, job_id: str, job: dict) -> list[str]:
    """Rule 7: the iOS tests stop before their checkout on anything but GitHub's own runner."""
    if (name, job_id) != IOS_JOB:
        return []
    where = f"{name}: {job_id}"
    problems = []
    guard = steps_of(job)[0] if steps_of(job) else {}
    # A guard that may continue on error, or in a job that may, would let the job go on anyway.
    for label, node in (("the job", job), ("the guard", guard)):
        if node.get("continue-on-error", False) is not False:
            problems.append(f"{where}: {label} may continue on error")
    if guard.get("if") != HOSTED_GUARD_IF or "exit 1" not in str(guard.get("run")):
        problems.append(f"{where} does not start by checking that it is on a GitHub-hosted runner")
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
    if name in RELEASE_WORKFLOWS and ("continue-on-error" in job
                                      or any("continue-on-error" in step for step in steps)):
        problems.append(f"{where} may continue on error; no job or step of a release may")
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
        if name == "release.yml" and job_id != "finish" and REPOSITORY_WRITES.search(run):
            problems.append(f"{where} runs `gh release`, `git push` or `git tag`, which finish alone may")
        if name in RELEASE_WORKFLOWS and runs_central_publish(step) and not all(
                flag in run for flag in CENTRAL_DAEMON_FLAGS):
            problems.append(f"{where}: a Central step leaves a Gradle or Kotlin daemon running after it"
                            f" ({' '.join(CENTRAL_DAEMON_FLAGS)} missing)")
        if (name in RELEASE_WORKFLOWS and SWIFT_KEY_FILE_NAME in run
                and not secret_references(json.dumps(step.get("env", {})), SWIFT_KEY)
                and run.strip() != f"rm -f {SWIFT_KEY_FILE}"):
            problems.append(f"{where}: a step touches the deploy key's file outside the step that uses it"
                            " and the one that removes it")
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
        if ((name in RELEASE_WORKFLOWS or key in CACHE_FREE_JOBS) and uses
                and not uses.startswith(RELEASE_ACTIONS)):
            problems.append(f"{where} uses {uses}, which is not one of the actions a release job may use"
                            f" ({', '.join(action.rstrip('@') for action in RELEASE_ACTIONS)})")
        if name in RELEASE_WORKFLOWS and PUSH_TO_MAIN.search(run):
            problems.append(f"{where}: a step pushes to main; a release pushes tags and its own branch only")
        if name in RELEASE_WORKFLOWS and key != FRAMEWORK_JOB and BUILDS_REFERENCE.search(run):
            problems.append(f"{where} builds the Swift API reference, which the framework job alone does,"
                            " holding no secret and no environment")
        if (name in RELEASE_WORKFLOWS and uses.startswith("actions/download-artifact@")
                and with_ != FRAMEWORK_DOWNLOAD):
            problems.append(f"{where} downloads with {with_}, not exactly {FRAMEWORK_DOWNLOAD}: a release"
                            " job downloads the framework's zip alone, from this run with the default"
                            " token, and the Swift reference reaches docs-publish.yml only")
        if (name in RELEASE_WORKFLOWS and key != FRAMEWORK_JOB and uses.startswith("actions/upload-artifact@")
                and with_.get("name") == SWIFT_REFERENCE):
            problems.append(f"{where} uploads {SWIFT_REFERENCE}, which the site publishes; only the"
                            " framework job may")
        if (name == "release.yml" and job_id != "verify" and uses.startswith("actions/checkout@")
                and "ref" in with_):
            problems.append(f"{where} checks out the ref {with_['ref']!r}, not the run's own commit")
    if name in RELEASE_WORKFLOWS and "actions" in permissions:
        problems.append(f"{where} holds actions: {permissions['actions']}; no release job reads another"
                        " run's artifacts or workflows")
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
    if key in {("release.yml", "finish"), ("release-resume.yml", "finish")} and permissions != {"contents": "write"}:
        problems.append(f"{where} holds {job.get('permissions')}, not exactly {{'contents': 'write'}}")
    if name in RELEASE_WORKFLOWS and "uses" in job and job.get("uses") != DOCS_WORKFLOW:
        problems.append(f"{where} calls {job.get('uses')}; a release calls the documentation site's workflow"
                        " alone")
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
        if REPOSITORY_WRITES.search(run):
            problems.append(f"{where}: the step holding {SWIFT_KEY} pushes by itself; the Swift package"
                            " goes through the script")
        if (len(re.findall(r"\$\{?key\b", run)) != 3 or run.count("key=") != 1
                or re.search(r"\.ssh\b|\$\{?HOME\b|~/", run)):
            problems.append(f"{where}: the step holding {SWIFT_KEY} uses its file for more than ssh, or"
                            " copies it out of RUNNER_TEMP")
        if (SWIFT_KEY_WRITE not in run or f"chmod 600 \"$key\"" not in run
                or f"key={SWIFT_KEY_FILE}" not in run):
            problems.append(f"{where}: the step holding {SWIFT_KEY} does not write it with umask 077"
                            f" and mode 600 to {SWIFT_KEY_FILE}")
        if (not all(option in run for option in SWIFT_SSH_OPTIONS) or HOST_KEY_CHECKING_OFF.search(run)
                or KNOWN_HOSTS_WRITE not in run or re.search(r"UserKnownHostsFile=(?!\$known_hosts\b)", run)):
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
                   if str(later.get("if", "")) == "always()"
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
        run = str(step.get("run", "")).strip()
        if "github.token" in json.dumps(step) and (not run.startswith(DRAFT_RELEASE) or CHAINED.search(run)):
            problems.append(f"{where} gives its token to a step that does more than create the draft"
                            " release (one command, nothing chained)")
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
    reference = (job.get("with") or {}).get(SWIFT_REFERENCE)
    expected = SWIFT_REFERENCE if name == "release.yml" else None
    if reference != expected:
        problems.append(f"{where} places {reference!r} under /swift/, not {expected!r}: the framework job's"
                        " artifact in a release, and none in a resume, whose run has no framework job")
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


def is_reference_unpack(step: dict) -> bool:
    """Whether `step` is the pinned unpack of the Swift reference, to the letter, keys included."""
    return dict(step, run=str(step.get("run", "")).rstrip("\n")) == REFERENCE_UNPACK


def docs_build_problems(name: str, document: dict) -> list[str]:
    """Rule 9: docs-publish.yml's build job places the Swift reference and does nothing else with it."""
    jobs = document.get("jobs") if isinstance(document.get("jobs"), dict) else {}
    if name != DOCS_BUILD_JOB[0] or not isinstance(jobs.get(DOCS_BUILD_JOB[1]), dict):
        return [] if name != DOCS_BUILD_JOB[0] else [f"{name}: has no build job"]
    job = jobs[DOCS_BUILD_JOB[1]]
    where = f"{name}: job {DOCS_BUILD_JOB[1]}"
    problems = []
    if job.get("permissions") != {"contents": "read"}:
        problems.append(f"{where} holds {job.get('permissions')}, not exactly {{'contents': 'read'}}:"
                        " the job that places the Swift reference holds nothing more (no Pages, OIDC or Actions token)")
    defaults = ((job.get("defaults") or {}).get("run") or {}) if isinstance(job.get("defaults"), dict) else {}
    if TOUCHES_SITE.search(str(defaults.get("working-directory", ""))):
        problems.append(f"{where} runs its steps in the site's directory by default")
    if NAMES_RUNNER_TEMP.search(str(defaults.get("working-directory", ""))):
        problems.append(f"{where} runs its steps in the runner's temporary directory by default")
    steps = steps_of(job)
    downloads = [index for index, step in enumerate(steps)
                 if str(step.get("uses", "")).startswith("actions/download-artifact@")]
    unpacks = [index for index, step in enumerate(steps) if is_reference_unpack(step)]
    for index, step in enumerate(steps):
        uses = str(step.get("uses", ""))
        with_ = step.get("with") if isinstance(step.get("with"), dict) else {}
        if TOUCHES_SITE.search(uses) or TOUCHES_SITE.search(str(step.get("working-directory", ""))):
            problems.append(f"{where} runs a step from, or in, the site's directory"
                            f" ({uses or step.get('working-directory')})")
        if index in downloads:
            if with_ != REFERENCE_DOWNLOAD:
                problems.append(f"{where} downloads with {with_}, not exactly {REFERENCE_DOWNLOAD}: the"
                                " caller's artifact, from the calling run with the default token, into"
                                " the runner's temporary directory alone")
            if step.get("if") != REFERENCE_IF:
                problems.append(f"{where} downloads the Swift reference if {step.get('if')!r}, not"
                                f" {REFERENCE_IF!r}")
            continue
        if index in unpacks:
            continue
        if TOUCHES_SITE.search(SITE_TEST.sub("", str(step.get("run", "")))):
            problems.append(f"{where} runs a step that uses what the site holds beyond testing a"
                            " path: nothing from the Swift reference is run, moved or changed")
        if NAMES_RUNNER_TEMP.search(json.dumps(step)):
            problems.append(f"{where} runs a step that names the runner's temporary directory, where"
                            " the Swift reference's tar lies: only its download and its unpack may")
    if downloads and not unpacks:
        problems.append(f"{where} downloads the Swift reference but does not unpack it with exactly"
                        f" the step {REFERENCE_UNPACK}")
    elif len(unpacks) > 1:
        problems.append(f"{where} unpacks the Swift reference {len(unpacks)} times, not once")
    elif unpacks and unpacks[0] - 1 not in downloads:
        problems.append(f"{where} unpacks the Swift reference other than directly after its download")
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
                            " group `release`, never canceled")
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
            job = job if isinstance(job, dict) else {}
            problems += runner_problems(path.name, job_id, job)
            problems += hosted_guard_problems(path.name, job_id, job)
        problems += fan_in_problems(path.name, jobs)
        problems += self_reference_problems(root, path.name, document)
        problems += oidc_problems(path.name, document)
        problems += release_problems(path.name, document)
        problems += docs_build_problems(path.name, document)
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
    types: [opened, synchronize, reopened]
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
    runs-on: macos-26
    steps:
      - if: {HOSTED_GUARD_IF}
        run: exit 1
      - run: echo
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
          known_hosts="$RUNNER_TEMP/github-known-hosts"
          (umask 077 && printenv SWIFT_PACKAGE_DEPLOY_KEY > "$key")
          chmod 600 "$key"
          python3 scripts/swift-release.py known-hosts > "$known_hosts"
          export GIT_SSH_COMMAND="ssh -i $key -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=$known_hosts"
          python3 scripts/swift-release.py publish --remote git@github.com:o/r.git
      - name: Remove the deploy key
        if: always()
        run: rm -f "$RUNNER_TEMP/swift-package-deploy-key"
"""
COMPLIANT_DOCS_PUBLISH = """\
on: {workflow_call: {inputs: {ref: {type: string}, swift-reference: {type: string}}}, workflow_dispatch: {}}
permissions: {}
jobs:
  build:
    runs-on: ubuntu-latest
    permissions: {contents: read}
    steps:
      - run: mkdocs build --strict
      - if: inputs.swift-reference != ''
        uses: actions/download-artifact@0123456789abcdef0123456789abcdef01234567
        with: {name: '${{ inputs.swift-reference }}', path: '${{ runner.temp }}/swift-reference-artifact'}
      - name: Unpack the Swift API reference
        if: inputs.swift-reference != ''
        run: |
          mkdir -p site/swift
          tar -xf "$RUNNER_TEMP/swift-reference-artifact/swift-reference.tar" -C site/swift --strip-components=1
      - run: |
          if [[ ! -f site/swift/documentation/supabaseoffline/index.html ]]; then
            echo "Published without the Swift API reference: /swift/ is not part of this site."
          fi
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
      - run: bash scripts/build-swift-docs.sh --derived-data d --out r
      - run: |
          python3 scripts/swift-release.py publish --dry-run --out t
          python3 scripts/swift-release.py read-manifest --tree t
      - uses: actions/upload-artifact@0123456789abcdef0123456789abcdef01234567
        with: {{name: swift-reference, path: r}}
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
        run: ./gradlew publishToMavenCentral {" ".join(CENTRAL_DAEMON_FLAGS)}
      - if: ${{{{ !inputs.dry_run }}}}
        env:
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{{{ secrets.GPG_KEY_CONTENTS }}}}
        run: ./gradlew publishAndReleaseToMavenCentral {WAIT_FOR_PUBLISHED} {" ".join(CENTRAL_DAEMON_FLAGS)}
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
    with: {{ref: '{DOCS_REF}', swift-reference: swift-reference}}
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

# The first line of the compliant coverage-upload condition, which several cases rewrite.
GUARD_LINE = f"      {DEPENDABOT_GUARD}\n"

# Each case: what it proves, the file it replaces or adds, its content, and a phrase the report
# must contain. The later ones are the second review's counter-examples.
# Where a case adds steps: the publish job's staging step in COMPLIANT_RELEASE, and the check step
# of COMPLIANT_DOCS_PUBLISH's build job.
PUBLISH_STAGING = "      - run: python3 scripts/swift-release.py publish --dry-run --out t\n"
DOCS_CHECK_STEP = "      - run: |\n"
# COMPLIANT_DOCS_PUBLISH's download and unpack steps, which the Swift reference's cases rewrite.
DOCS_DOWNLOAD_PATH = "path: '${{ runner.temp }}/swift-reference-artifact'}"
DOCS_DOWNLOAD_STEP = ("      - if: inputs.swift-reference != ''\n"
                      "        uses: actions/download-artifact@0123456789abcdef0123456789abcdef01234567\n"
                      "        with: {name: '${{ inputs.swift-reference }}', " + DOCS_DOWNLOAD_PATH + "\n")
DOCS_UNPACK_HEAD = "      - name: Unpack the Swift API reference\n        if: inputs.swift-reference != ''\n"
DOCS_UNPACK_STEP = (DOCS_UNPACK_HEAD + "        run: |\n          mkdir -p site/swift\n"
                    "          tar -xf \"$RUNNER_TEMP/swift-reference-artifact/swift-reference.tar\""
                    " -C site/swift --strip-components=1\n")


def release_with_publish_steps(steps: str) -> str:
    """COMPLIANT_RELEASE with `steps` added to the publish job, after its staging step."""
    return COMPLIANT_RELEASE.replace(PUBLISH_STAGING + "      - if: inputs.dry_run",
                                     PUBLISH_STAGING + steps + "      - if: inputs.dry_run")


def docs_publish_with_step(step: str) -> str:
    """COMPLIANT_DOCS_PUBLISH with `step` added to its build job, before the check step."""
    return COMPLIANT_DOCS_PUBLISH.replace(DOCS_CHECK_STEP, step + DOCS_CHECK_STEP)


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
    ("ios-tests back on the self-hosted runner", "ci.yml",
     COMPLIANT_CI.replace("runs-on: macos-26", "runs-on: [self-hosted, macOS, ARM64, offline-kt]"),
     "job ios-tests names the self-hosted runner"),
    ("ios-tests routed by the old expression", "ci.yml",
     COMPLIANT_CI.replace("runs-on: macos-26",
                          "runs-on: ${{ needs.changes.outputs.ios-runner == 'local'"
                          " && fromJSON('[\"self-hosted\", \"macOS\", \"ARM64\", \"offline-kt\"]')"
                          " || 'macos-26' }}"),
     "job ios-tests names the self-hosted runner"),
    ("fan-in missing a job", "ci.yml",
     COMPLIANT_CI.replace("needs: [changes, ios-tests, docs]", "needs: [changes, docs]"),
     "not every other job"),
    ("fan-in without always()", "ci.yml",
     COMPLIANT_CI.replace("    if: always()\n", "    if: success()\n"), "final-status runs if"),
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
    ("ios-tests on a variable that falls back to a hosted label", "ci.yml",
     COMPLIANT_CI.replace("runs-on: macos-26", "runs-on: ${{ vars.IOS_RUNNER || 'macos-26' }}"),
     "job ios-tests runs on"),
    ("a hosted-looking label the runner could be given", "extra.yml",
     "on: workflow_dispatch\njobs:\n  a:\n    runs-on: macos-offline-kt\n"
     "    steps: [{run: echo}]\n", "not a literal hosted label"),
    ("fan-in if: always() && false", "ci.yml",
     COMPLIANT_CI.replace("    if: always()\n", "    if: always() && false\n"),
     "final-status runs if"),
    ("fan-in skipped on a label event again", "ci.yml",
     COMPLIANT_CI.replace("    if: always()\n", "    if: always() && (github.event.action !="
                          " 'labeled' || github.event.label.name == 'full-ci')\n"),
     "final-status runs if"),
    ("ci.yml gains a create trigger", "ci.yml",
     COMPLIANT_CI.replace("  merge_group:\n", "  merge_group:\n  create:\n"), "ci.yml: triggers"),
    ("ci.yml gains issue_comment", "ci.yml",
     COMPLIANT_CI.replace("  merge_group:\n", "  merge_group:\n  issue_comment:\n"),
     "ci.yml: triggers"),
    ("ci.yml pull_request gains a type", "ci.yml",
     COMPLIANT_CI.replace("synchronize, reopened]", "synchronize, reopened, edited]"),
     "pull_request types"),
    ("ci.yml pull_request starts on labels again", "ci.yml",
     COMPLIANT_CI.replace("synchronize, reopened]", "synchronize, reopened, labeled]"),
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
    # Rule 7: the iOS tests' check that they are on GitHub's own runner.
    ("ios-tests without its hosted-runner guard", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {HOSTED_GUARD_IF}\n        run: exit 1\n", ""),
     "ios-tests does not start by checking that it is on a GitHub-hosted runner"),
    ("the hosted-runner guard switched off", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {HOSTED_GUARD_IF}\n", "      - if: false\n"),
     "ios-tests does not start by checking that it is on a GitHub-hosted runner"),
    ("the hosted-runner guard that never fails", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {HOSTED_GUARD_IF}\n        run: exit 1\n",
                          f"      - if: {HOSTED_GUARD_IF}\n        run: exit 0\n"),
     "ios-tests does not start by checking that it is on a GitHub-hosted runner"),
    ("the hosted-runner guard after another step", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {HOSTED_GUARD_IF}\n        run: exit 1\n      - run: echo\n",
                          f"      - run: echo\n      - if: {HOSTED_GUARD_IF}\n        run: exit 1\n"),
     "ios-tests does not start by checking that it is on a GitHub-hosted runner"),
    ("the hosted-runner guard may continue on error", "ci.yml",
     COMPLIANT_CI.replace(f"      - if: {HOSTED_GUARD_IF}\n        run: exit 1\n",
                          f"      - if: {HOSTED_GUARD_IF}\n        run: exit 1\n"
                          "        continue-on-error: true\n"),
     "the guard may continue on error"),
    ("ios-tests may continue on error", "ci.yml",
     COMPLIANT_CI.replace("    needs: changes\n    runs-on: macos-26\n",
                          "    needs: changes\n    runs-on: macos-26\n"
                          "    continue-on-error: ${{ true }}\n"),
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
     "never canceled"),
    ("releases in different concurrency groups", "release-resume.yml",
     COMPLIANT_RESUME.replace("group: release", "group: resume"), "never canceled"),
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
     COMPLIANT_RELEASE.replace(" -o UserKnownHostsFile=$known_hosts", ""),
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
                               f"        run: ./gradlew publishAndReleaseToMavenCentral {WAIT_FOR_PUBLISHED}"
                               f" {' '.join(CENTRAL_DAEMON_FLAGS)}\n", "")
     .replace("  finish:\n", "      - if: ${{ !inputs.dry_run }}\n        env:\n"
              "          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_KEY_CONTENTS }}\n"
              f"        run: ./gradlew publishAndReleaseToMavenCentral {WAIT_FOR_PUBLISHED}"
              f" {' '.join(CENTRAL_DAEMON_FLAGS)}\n  finish:\n", 1),
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
    # The review of the release workflow's second round: what a list of cache actions missed.
    ("a local composite action in a release job", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/release.py check-version\n",
                               "      - uses: $/.github/actions/setup\n"
                               "      - run: python3 scripts/release.py check-version\n"),
     "job verify uses $/.github/actions/setup, which is not one of the actions"),
    ("the setup action by its old spelling", "release-resume.yml",
     COMPLIANT_RESUME.replace("      - run: python3 scripts/release.py check-resume",
                              "      - uses: ./.github/actions/setup-project\n"
                              "      - run: python3 scripts/release.py check-resume"),
     "job finish uses ./.github/actions/setup-project, which is not one of the actions"),
    ("a third-party Gradle cache action in the snapshot job", "ci.yml",
     COMPLIANT_CI.replace("      - id: version\n",
                          "      - uses: burrunan/gradle-cache-action@0123456789abcdef0123456789abcdef01234567\n"
                          "      - id: version\n"),
     "job snapshot uses burrunan/gradle-cache-action"),
    ("finish gaining pull-requests: write", "release.yml",
     COMPLIANT_RELEASE.replace("    permissions: {contents: write}\n    outputs:",
                               "    permissions: {contents: write, pull-requests: write}\n    outputs:"),
     "job finish holds"),
    ("a push to main in finish", "release.yml",
     COMPLIANT_RELEASE.replace("        run: python3 scripts/release.py push-tag\n",
                               "        run: python3 scripts/release.py push-tag && git push origin HEAD:main\n"),
     "a step pushes to main"),
    ("a release calling another reusable workflow", "release-resume.yml",
     COMPLIANT_RESUME + "  more:\n    needs: finish\n    uses: $/.github/workflows/docs-check.yml\n",
     "job more calls $/.github/workflows/docs-check.yml"),
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
     COMPLIANT_RELEASE.replace("        if: always()\n        run: rm -f", "        if: success()\n        run: rm -f"),
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
     "gives its token to a step that does more than create the draft release"),
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
     COMPLIANT_RELEASE.replace(f"ref: '{DOCS_REF}'", "ref: main"),
     "not the released tag"),
    # The Swift API reference: built and uploaded by the framework job alone, placed by the docs job.
    ("the resume's documentation job holding contents: write", "release-resume.yml",
     COMPLIANT_RESUME.replace("permissions: {contents: read, pages: write, id-token: write}",
                              "permissions: {contents: write, pages: write, id-token: write}"),
     "job docs holds"),
    ("the Swift reference built in the job that holds the release environment", "release.yml",
     release_with_publish_steps(
         "      - run: bash scripts/build-swift-docs.sh --derived-data d --out r\n"),
     "job publish builds the Swift API reference"),
    ("the Swift reference built by hand with docc in the job that holds the release environment",
     "release.yml",
     release_with_publish_steps(
         "      - run: xcrun docc process-archive transform-for-static-hosting a --output-path r\n"),
     "job publish builds the Swift API reference"),
    ("the Swift reference uploaded by the job that holds the release environment", "release.yml",
     release_with_publish_steps(
         "      - uses: actions/upload-artifact@0123456789abcdef0123456789abcdef01234567\n"
         "        with: {name: swift-reference, path: r}\n"),
     "job publish uploads swift-reference"),
    ("the framework job skipping the Swift reference", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: bash scripts/build-swift-docs.sh --derived-data d --out r\n", ""),
     "does not run `bash scripts/build-swift-docs.sh`"),
    ("the release's documentation without the Swift reference", "release.yml",
     COMPLIANT_RELEASE.replace(", swift-reference: swift-reference}", "}"),
     "places None under /swift/"),
    ("the release's documentation placing another artifact", "release.yml",
     COMPLIANT_RELEASE.replace("swift-reference: swift-reference}",
                               "swift-reference: swift-package-tree}"),
     "places 'swift-package-tree' under /swift/"),
    ("the job holding the release environment downloading the Swift reference", "release.yml",
     release_with_publish_steps(
         "      - uses: actions/download-artifact@0123456789abcdef0123456789abcdef01234567\n"
         "        with: {name: swift-reference, path: swift/Sources/SupabaseOffline}\n"),
     "job publish downloads with {'name': 'swift-reference'"),
    ("the site's build downloading another run's artifact with a token", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace("permissions: {contents: read}",
                                    "permissions: {contents: read, actions: read}")
     .replace(DOCS_DOWNLOAD_PATH, "path: '${{ runner.temp }}/swift-reference-artifact', run-id: '1',"
              " github-token: '${{ github.token }}'}"),
     "job build downloads with"),
    ("the site's build running a script from the Swift reference", "docs-publish.yml",
     docs_publish_with_step("      - run: bash site/swift/prepare.sh\n"),
     "job build runs a step that uses what the site holds"),
    ("the site's build running the Swift reference from its folder", "docs-publish.yml",
     docs_publish_with_step("      - run: cd site && ./swift/prepare.sh\n"),
     "job build runs a step that uses what the site holds"),
    ("the Swift reference placed over the site's root", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace(DOCS_DOWNLOAD_PATH, "path: site}"),
     "job build downloads with"),
    ("the site's build holding a Pages token", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace("permissions: {contents: read}",
                                    "permissions: {contents: read, pages: write}"),
     "job build holds"),
    ("the site's build running a script from the site by ./ path", "docs-publish.yml",
     docs_publish_with_step("      - run: ./site/swift/prepare.sh\n"),
     "job build runs a step that uses what the site holds"),
    ("the site's build running a script from the site by workspace path", "docs-publish.yml",
     docs_publish_with_step("      - run: bash \"$GITHUB_WORKSPACE/site/swift/prepare.sh\"\n"),
     "job build runs a step that uses what the site holds"),
    ("the site's build sourcing a file from the site", "docs-publish.yml",
     docs_publish_with_step("      - run: . site/swift/env.sh\n"),
     "job build runs a step that uses what the site holds"),
    ("the site's build working in the site's directory", "docs-publish.yml",
     docs_publish_with_step("      - working-directory: site/swift\n        run: make\n"),
     "job build runs a step from, or in, the site's directory"),
    ("the site's build working in the site's directory by default", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace("    permissions: {contents: read}\n    steps:",
                                    "    permissions: {contents: read}\n"
                                    "    defaults: {run: {working-directory: site/swift}}\n    steps:"),
     "job build runs its steps in the site's directory by default"),
    ("the site's build using an action from the site", "docs-publish.yml",
     docs_publish_with_step("      - uses: ./site/swift\n"),
     "job build runs a step from, or in, the site's directory"),
    ("the Swift reference's tar downloaded into the site, where the reference itself once went",
     "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace(DOCS_DOWNLOAD_PATH, "path: site/swift}"),
     "job build downloads with"),
    ("the Swift reference downloaded when the caller names none", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace(DOCS_DOWNLOAD_STEP,
                                    DOCS_DOWNLOAD_STEP.replace("      - if: inputs.swift-reference != ''\n"
                                                               "        uses:", "      - uses:")),
     "job build downloads the Swift reference if None"),
    ("the Swift reference unpacked with a flag that keeps absolute names", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace("tar -xf \"$RUNNER_TEMP", "tar -xPf \"$RUNNER_TEMP"),
     "job build downloads the Swift reference but does not unpack it with exactly the step"),
    ("the Swift reference unpacked over the site's root", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace("-C site/swift --strip", "-C site --strip"),
     "job build downloads the Swift reference but does not unpack it with exactly the step"),
    ("the Swift reference unpacked when the caller names none", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace(DOCS_UNPACK_HEAD, "      - name: Unpack the Swift API reference\n"),
     "job build downloads the Swift reference but does not unpack it with exactly the step"),
    ("the Swift reference unpacked twice", "docs-publish.yml",
     docs_publish_with_step(DOCS_UNPACK_STEP),
     "job build unpacks the Swift reference 2 times, not once"),
    ("the Swift reference unpacked in another working directory", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace(DOCS_UNPACK_HEAD, DOCS_UNPACK_HEAD + "        working-directory: docs\n"),
     "job build downloads the Swift reference but does not unpack it with exactly the step"),
    ("the Swift reference's tar running a command on each file", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace("--strip-components=1\n", "--strip-components=1 --to-command=sh\n"),
     "job build downloads the Swift reference but does not unpack it with exactly the step"),
    ("the Swift reference downloaded and never unpacked", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace(DOCS_UNPACK_STEP, ""),
     "job build downloads the Swift reference but does not unpack it with exactly the step"),
    ("the Swift reference unpacked before its download", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace(DOCS_DOWNLOAD_STEP + DOCS_UNPACK_STEP,
                                    DOCS_UNPACK_STEP + DOCS_DOWNLOAD_STEP),
     "job build unpacks the Swift reference other than directly after its download"),
    ("the Swift reference unpacked with no download", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace(DOCS_DOWNLOAD_STEP, ""),
     "job build unpacks the Swift reference other than directly after its download"),
    ("the site's build running the tar's contents without naming the site", "docs-publish.yml",
     docs_publish_with_step("      - run: tar -xOf \"$RUNNER_TEMP/swift-reference-artifact/swift-reference.tar\""
                            " | sh\n"),
     "job build runs a step that names the runner's temporary directory"),
    ("the site's build working in the runner's temporary directory by default", "docs-publish.yml",
     COMPLIANT_DOCS_PUBLISH.replace("    permissions: {contents: read}\n    steps:",
                                    "    permissions: {contents: read}\n"
                                    "    defaults: {run: {working-directory: '${{ runner.temp }}'}}\n    steps:"),
     "job build runs its steps in the runner's temporary directory by default"),
    ("publish downloading another run's framework with a token", "release.yml",
     release_with_publish_steps(
         "      - uses: actions/download-artifact@0123456789abcdef0123456789abcdef01234567\n"
         "        with: {name: swift-framework, path: '${{ runner.temp }}/swift-release',"
         " run-id: '1', github-token: '${{ github.token }}'}\n"),
     "job publish downloads with"),
    ("publish given an Actions permission", "release.yml",
     COMPLIANT_RELEASE.replace("    environment: release\n    permissions: {contents: read}",
                               "    environment: release\n    permissions: {contents: read, actions: read}"),
     "job publish holds actions: read"),
    ("the resume's documentation naming an artifact its run has not", "release-resume.yml",
     COMPLIANT_RESUME.replace(f"with: {{ref: '{DOCS_REF}'}}",
                              f"with: {{ref: '{DOCS_REF}', swift-reference: swift-reference}}"),
     "places 'swift-reference' under /swift/"),
    ("the documentation published before finish", "release-resume.yml",
     COMPLIANT_RESUME.replace("  docs:\n    needs: finish\n", "  docs:\n    needs: []\n"),
     "not finish alone"),
    # The review of the Swift release: what the rule let through.
    ("the draft step publishing the draft on a second line", "release.yml",
     COMPLIANT_RELEASE.replace(f"        run: {DRAFT_RELEASE} --zip z\n",
                               f"        run: |\n          {DRAFT_RELEASE} --zip z\n"
                               "          gh release edit \"$VERSION\" --draft=false\n"),
     "gives its token to a step that does more than create the draft release"),
    ("the draft step chaining a command", "release.yml",
     COMPLIANT_RELEASE.replace(f"        run: {DRAFT_RELEASE} --zip z\n",
                               f"        run: {DRAFT_RELEASE} --zip z; curl -X PATCH example.invalid\n"),
     "gives its token to a step that does more than create the draft release"),
    ("a GitHub Release touched in publish", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/swift-release.py publish --dry-run --out t\n      - if: inputs.dry_run",
                               "      - run: gh release upload 0.1.0 z\n      - if: inputs.dry_run"),
     "job publish runs `gh release`, `git push` or `git tag`"),
    ("a tag made in the framework job", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/check-bridge-header.py\n",
                               "      - run: python3 scripts/check-bridge-header.py && git tag 0.1.0\n"),
     "job framework runs `gh release`, `git push` or `git tag`"),
    ("the resume's Swift step pushing by itself", "release-resume.yml",
     COMPLIANT_RESUME.replace("python3 scripts/swift-release.py publish --remote git@github.com:o/r.git",
                              "python3 scripts/swift-release.py publish --remote git@github.com:o/r.git\n"
                              "          git -C \"$RUNNER_TEMP/p\" push git@github.com:o/r.git HEAD:refs/tags/0.1.0"),
     "pushes by itself; the Swift package goes through the script"),
    ("a double-quoted forced refspec", "release.yml",
     COMPLIANT_RELEASE.replace("        run: python3 scripts/release.py push-tag\n",
                               "        run: git push origin \"+$GITHUB_SHA:refs/tags/$VERSION\"\n"),
     "a step pushes with force"),
    ("a single-quoted forced refspec", "release-resume.yml",
     COMPLIANT_RESUME.replace("      - run: python3 scripts/release.py push-tag",
                              "      - run: git push origin '+HEAD:refs/tags/0.1.0'"),
     "a step pushes with force"),
    ("the tree check before Central may continue on error", "release.yml",
     COMPLIANT_RELEASE.replace("      - run: python3 scripts/swift-release.py publish --dry-run --out t\n      - if: inputs.dry_run",
                               "      - run: python3 scripts/swift-release.py publish --dry-run --out t\n"
                               "        continue-on-error: true\n      - if: inputs.dry_run"),
     "job publish may continue on error"),
    ("the hosted-runner check may continue on error", "release.yml",
     COMPLIANT_RELEASE.replace(f"      - if: {HOSTED_GUARD_IF}\n        run: exit 1\n",
                               f"      - if: {HOSTED_GUARD_IF}\n        run: exit 1\n        continue-on-error: true\n"),
     "job framework may continue on error"),
    ("a release job that may continue on error", "release-resume.yml",
     COMPLIANT_RESUME.replace("    environment: release\n", "    environment: release\n    continue-on-error: true\n"),
     "job finish may continue on error"),
    ("the deploy key removed only on a dry run", "release.yml",
     COMPLIANT_RELEASE.replace("        if: always()\n        run: rm -f", "        if: always() && inputs.dry_run\n        run: rm -f"),
     "does not remove the deploy key under always()"),
    ("the real Central step with a Gradle daemon", "release.yml",
     COMPLIANT_RELEASE.replace(f"{WAIT_FOR_PUBLISHED} --no-daemon", WAIT_FOR_PUBLISHED),
     "a Central step leaves a Gradle or Kotlin daemon running"),
    ("the dry-run Central step with a Kotlin daemon", "release.yml",
     COMPLIANT_RELEASE.replace("publishToMavenCentral --no-daemon -Pkotlin.compiler.execution.strategy=in-process",
                               "publishToMavenCentral --no-daemon"),
     "a Central step leaves a Gradle or Kotlin daemon running"),
    ("the known_hosts file swapped for /dev/null", "release.yml",
     COMPLIANT_RELEASE.replace("-o UserKnownHostsFile=$known_hosts", "-o UserKnownHostsFile=$known_hosts -o UserKnownHostsFile=/dev/null"),
     "does not connect with host-key checking on"),
    ("the deploy key copied to ~/.ssh in its step", "release-resume.yml",
     COMPLIANT_RESUME.replace('          chmod 600 "$key"\n',
                              '          chmod 600 "$key"\n          cp "$key" "$HOME/.ssh/id_ed25519"\n'),
     "copies it out of RUNNER_TEMP"),
    ("the deploy key copied by a later step", "release.yml",
     COMPLIANT_RELEASE.replace("      - name: Remove the deploy key\n",
                               "      - run: cp \"$RUNNER_TEMP/swift-package-deploy-key\" ~/.ssh/id_ed25519\n"
                               "      - name: Remove the deploy key\n"),
     "touches the deploy key's file outside the step that uses it"),
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
    for action in ("setup",):
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
