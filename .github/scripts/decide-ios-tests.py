#!/usr/bin/env python3
"""Decides where the iOS tests run for this CI run, and whether they may run at all.

Usage:
  decide-ios-tests.py              read the run from the environment, write the decision to
                                   $GITHUB_OUTPUT (ios-runner, ios-allowed, ios-hold, ios-held)
  decide-ios-tests.py --self-test  check the decision against its truth table and print it

Anything unexpected routes to GitHub's hosted runner, never to the self-hosted one.
"""

from __future__ import annotations

import itertools
import os
import sys
from dataclasses import dataclass, replace

DEPENDABOT = "dependabot[bot]"
FULL_CI_LABEL = "full-ci"


@dataclass(frozen=True)
class Run:
    """What the decision reads about a run: the event, the repository and who started it."""

    event: str
    action: str = ""
    label: str = ""
    private: str = "true"
    local_runner_variable: str = ""
    draft: str = ""
    author: str = ""
    actor: str = "maintainer"
    triggering_actor: str = "maintainer"
    head_ref: str = "feature"
    kotlin: str = "true"


@dataclass(frozen=True)
class Decision:
    """Where the iOS tests run, whether they may, and why not when they may not."""

    runner: str
    allowed: bool
    hold: str
    held: bool


def started_by_dependabot(run: Run) -> bool:
    """True when Dependabot pushed the commit or started the run; no label overrides this."""
    return run.actor == DEPENDABOT or run.triggering_actor == DEPENDABOT


def changes_dependabot_made(run: Run) -> bool:
    """True when the pull request or branch is Dependabot's, whoever started this run."""
    return run.author == DEPENDABOT or run.head_ref.startswith("dependabot/")


def approved_by_label(run: Run) -> bool:
    """True only on the event of a person adding the full-ci label: that person read this push."""
    return (
        run.event == "pull_request"
        and run.action == "labeled"
        and run.label == FULL_CI_LABEL
        and not started_by_dependabot(run)
    )


def decide(run: Run) -> Decision:
    """Applies the routing rule and, on the hosted runner, the private-repository rationing rule."""
    dependabot_started = started_by_dependabot(run)
    dependabot_changes = dependabot_started or changes_dependabot_made(run)
    approved = approved_by_label(run)
    local = (
        run.private == "true"
        and run.local_runner_variable == "on"
        and run.event not in ("schedule", "merge_group")
        and not dependabot_started
        and (not dependabot_changes or approved)
    )

    hold = ""
    if not local and run.private != "false":
        if run.event == "pull_request":
            if run.draft != "false":
                hold = "draft pull request"
            elif dependabot_started:
                hold = (f"Dependabot pushed or started this run; a maintainer adds the"
                        f" {FULL_CI_LABEL} label after reading the push")
            elif dependabot_changes and not approved:
                hold = (f"Dependabot pull request; a maintainer adds the {FULL_CI_LABEL} label"
                        f" after reading the push")
        elif run.event == "push":
            hold = "push to main"
        elif run.event not in ("workflow_dispatch", "schedule", "merge_group"):
            hold = f"{run.event} event"

    allowed = not hold
    return Decision(
        runner="local" if local else "hosted",
        allowed=allowed,
        hold=hold,
        held=run.kotlin == "true" and not allowed,
    )


def run_from_environment() -> Run:
    """Reads the run from the variables the changes job passes in."""
    environment = os.environ
    return Run(
        event=environment.get("EVENT_NAME", ""),
        action=environment.get("EVENT_ACTION", ""),
        label=environment.get("EVENT_LABEL", ""),
        private=environment.get("REPOSITORY_PRIVATE", ""),
        local_runner_variable=environment.get("IOS_LOCAL_RUNNER", ""),
        draft=environment.get("PULL_REQUEST_DRAFT", ""),
        author=environment.get("PULL_REQUEST_AUTHOR", ""),
        actor=environment.get("ACTOR", ""),
        triggering_actor=environment.get("TRIGGERING_ACTOR", ""),
        head_ref=environment.get("HEAD_REF", ""),
        kotlin=environment.get("KOTLIN", ""),
    )


QUEUED_NOTE = """\
The iOS tests go to the self-hosted runner. If the iOS job stays queued ("Waiting for a runner to
pick up this job"), that runner is offline: start it, or send the iOS tests to GitHub's runner and
re-run all jobs:

    gh variable set IOS_LOCAL_RUNNER --body off --repo {repository}

While the repository is private, a run with the iOS tests on GitHub's runner costs about 70
billable minutes, 60 of them for macOS.
"""


def write_outputs(decision: Decision) -> None:
    """Writes the decision to the step's outputs, a line to the log and a note to the summary."""
    print(
        f"  iOS tests: runner={decision.runner} allowed={str(decision.allowed).lower()}"
        f" held={str(decision.held).lower()}" + (f" ({decision.hold})" if decision.hold else "")
    )
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as outputs:
        outputs.write(f"ios-runner={decision.runner}\n")
        outputs.write(f"ios-allowed={str(decision.allowed).lower()}\n")
        outputs.write(f"ios-hold={decision.hold}\n")
        outputs.write(f"ios-held={str(decision.held).lower()}\n")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path and decision.runner == "local" and decision.allowed:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(QUEUED_NOTE.format(repository=os.environ.get("GITHUB_REPOSITORY", "")))


# Each row: a run, then the runner, whether the iOS tests may run, and the hold reason expected.
PRIVATE_ON = Run(event="pull_request", action="synchronize", private="true",
                 local_runner_variable="on", draft="true")
PRIVATE_OFF = replace(PRIVATE_ON, local_runner_variable="")
DEPENDABOT_PUSH = dict(author=DEPENDABOT, actor=DEPENDABOT, triggering_actor=DEPENDABOT,
                       head_ref="dependabot/gradle/x", draft="false")
LABEL_ADDED = dict(action="labeled", label=FULL_CI_LABEL, author=DEPENDABOT,
                   head_ref="dependabot/gradle/x", draft="false")
HELD_DRAFT = "draft pull request"
HELD_STARTED = ("Dependabot pushed or started this run; a maintainer adds the full-ci label after"
                " reading the push")
HELD_CHANGES = "Dependabot pull request; a maintainer adds the full-ci label after reading the push"
TRUTH_TABLE: list[tuple[str, Run, str, bool, str]] = [
    ("variable on: draft", PRIVATE_ON, "local", True, ""),
    ("variable on: ready", replace(PRIVATE_ON, draft="false"), "local", True, ""),
    ("variable on: push to main", replace(PRIVATE_ON, event="push", action="", draft=""),
     "local", True, ""),
    ("variable on: manual run", replace(PRIVATE_ON, event="workflow_dispatch", action="",
     draft=""), "local", True, ""),
    ("variable on: merge queue", replace(PRIVATE_ON, event="merge_group", action="",
     draft=""), "hosted", True, ""),
    ("variable on: Dependabot pull request in the merge queue", replace(PRIVATE_ON,
     event="merge_group", action="", draft="", head_ref="gh-readonly-queue/main/pr-21-x"),
     "hosted", True, ""),
    ("variable on: weekly schedule", replace(PRIVATE_ON, event="schedule", action="",
     draft=""), "hosted", True, ""),
    ("variable on: Dependabot opens or pushes", replace(PRIVATE_ON, **DEPENDABOT_PUSH),
     "hosted", False, HELD_STARTED),
    ("variable on: a maintainer adds full-ci", replace(PRIVATE_ON, **LABEL_ADDED),
     "local", True, ""),
    ("variable on: Dependabot pushes after full-ci", replace(PRIVATE_ON, **DEPENDABOT_PUSH),
     "hosted", False, HELD_STARTED),
    ("variable on: Dependabot adds full-ci itself", replace(PRIVATE_ON, **LABEL_ADDED,
     actor=DEPENDABOT, triggering_actor=DEPENDABOT), "hosted", False, HELD_STARTED),
    ("variable on: a maintainer re-runs the full-ci run", replace(PRIVATE_ON, **LABEL_ADDED,
     triggering_actor="another-maintainer"), "local", True, ""),
    ("variable on: a maintainer re-runs Dependabot's run", replace(PRIVATE_ON,
     **{**DEPENDABOT_PUSH, "triggering_actor": "maintainer"}), "hosted", False, HELD_STARTED),
    ("variable on: Dependabot re-runs a maintainer's run", replace(PRIVATE_ON, draft="false",
     triggering_actor=DEPENDABOT), "hosted", False, HELD_STARTED),
    ("variable on: a maintainer pushes to a Dependabot branch", replace(PRIVATE_ON,
     draft="false", author=DEPENDABOT, head_ref="dependabot/gradle/x"), "hosted", False,
     HELD_CHANGES),
    ("variable on: another label on a Dependabot pull request", replace(PRIVATE_ON,
     **{**LABEL_ADDED, "label": "dependencies"}), "hosted", False, HELD_CHANGES),
    ("variable on: dependabot/ head ref only", replace(PRIVATE_ON, draft="false",
     head_ref="dependabot/github_actions/y"), "hosted", False, HELD_CHANGES),
    ("variable on: manual run on a Dependabot branch", replace(PRIVATE_ON,
     event="workflow_dispatch", action="", draft="", head_ref="dependabot/gradle/x"),
     "hosted", True, ""),
    ("variable off: draft", PRIVATE_OFF, "hosted", False, HELD_DRAFT),
    ("variable off: ready", replace(PRIVATE_OFF, draft="false"), "hosted", True, ""),
    ("variable off: push to main", replace(PRIVATE_OFF, event="push", action="", draft=""),
     "hosted", False, "push to main"),
    ("variable off: manual run", replace(PRIVATE_OFF, event="workflow_dispatch", action="",
     draft=""), "hosted", True, ""),
    ("variable off: weekly schedule", replace(PRIVATE_OFF, event="schedule", action="",
     draft=""), "hosted", True, ""),
    ("variable off: merge queue", replace(PRIVATE_OFF, event="merge_group", action="",
     draft=""), "hosted", True, ""),
    ("variable off: Dependabot opens or pushes", replace(PRIVATE_OFF, **DEPENDABOT_PUSH),
     "hosted", False, HELD_STARTED),
    ("variable off: a maintainer adds full-ci", replace(PRIVATE_OFF, **LABEL_ADDED),
     "hosted", True, ""),
    ("variable off: Dependabot pushes after full-ci", replace(PRIVATE_OFF, **DEPENDABOT_PUSH),
     "hosted", False, HELD_STARTED),
    ("variable 'On' (not exactly on): ready", replace(PRIVATE_ON, local_runner_variable="On",
     draft="false"), "hosted", True, ""),
    ("variable 'true': draft", replace(PRIVATE_ON, local_runner_variable="true"),
     "hosted", False, HELD_DRAFT),
    ("private field missing, variable on: draft", replace(PRIVATE_ON, private=""),
     "hosted", False, HELD_DRAFT),
    ("private field missing, variable on: push", replace(PRIVATE_ON, private="", event="push",
     action="", draft=""), "hosted", False, "push to main"),
    ("public, variable on: draft", replace(PRIVATE_ON, private="false"), "hosted", True, ""),
    ("public: push to main", replace(PRIVATE_OFF, private="false", event="push", action="",
     draft=""), "hosted", True, ""),
    ("public: Dependabot opens or pushes", replace(PRIVATE_OFF, private="false",
     **DEPENDABOT_PUSH), "hosted", True, ""),
    ("draft field missing on a pull request", replace(PRIVATE_OFF, draft=""),
     "hosted", False, HELD_DRAFT),
    ("unknown event, variable off", replace(PRIVATE_OFF, event="repository_dispatch", action="",
     draft=""), "hosted", False, "repository_dispatch event"),
]


def violations(run: Run, decision: Decision, dependabot_key: str) -> list[str]:
    """The rule's invariants, checked against one decision."""
    problems = []
    started = dependabot_key in ("actor", "triggering_actor")
    label_event = (run.event == "pull_request" and run.action == "labeled"
                   and run.label == FULL_CI_LABEL)
    if decision.runner == "local" and not (
        run.private == "true" and run.local_runner_variable == "on"
        and run.event not in ("schedule", "merge_group") and not started
        and (dependabot_key == "none" or label_event)
    ):
        problems.append("routed to the self-hosted runner outside the rule")
    if decision.runner == "local" and not decision.allowed:
        problems.append("routed to the self-hosted runner but held")
    if run.event in ("schedule", "merge_group") and (decision.runner, decision.allowed) != (
            "hosted", True):
        problems.append(f"{run.event} must run the iOS tests on GitHub's runner")
    if run.private == "false" and (decision.runner, decision.allowed) != ("hosted", True):
        problems.append("a public repository must run the iOS tests on GitHub's runner")
    if run.private != "false" and run.event == "pull_request" and started and decision.allowed:
        problems.append("a run Dependabot pushed or started ran the held jobs")
    if decision.allowed == bool(decision.hold):
        problems.append("allowed and hold disagree")
    if decision.held != (run.kotlin == "true" and not decision.allowed):
        problems.append("held is not 'due and not allowed'")
    return problems


def self_test() -> None:
    """Checks every truth-table row, then the invariants over every combination of the inputs."""
    failures = 0
    print(f"{'case':<58} {'runner':<7} {'iOS':<5} hold")
    for name, run, runner, allowed, hold in TRUTH_TABLE:
        decision = decide(run)
        ok = (decision.runner, decision.allowed, decision.hold) == (runner, allowed, hold)
        mark = "" if ok else f"   FAIL: expected {runner} {allowed} {hold!r}"
        print(f"{name:<58} {decision.runner:<7} {'runs' if decision.allowed else 'held':<5}"
              f" {decision.hold}{mark}")
        failures += not ok

    combinations = 0
    keys = {"none": {}, "author": {"author": DEPENDABOT}, "actor": {"actor": DEPENDABOT},
            "triggering_actor": {"triggering_actor": DEPENDABOT},
            "head_ref": {"head_ref": "dependabot/npm/z"}}
    for event, action, label, private, variable, draft, dependabot_key, kotlin in (
            itertools.product(
                ["pull_request", "push", "workflow_dispatch", "schedule", "merge_group", "other"],
                ["", "labeled", "synchronize"],
                ["", FULL_CI_LABEL, "dependencies"],
                ["true", "false", ""],
                ["on", "", "off", "ON"],
                ["true", "false", ""],
                list(keys),
                ["true", "false", ""])):
        combinations += 1
        run = Run(event=event, action=action, label=label, private=private,
                  local_runner_variable=variable, draft=draft, kotlin=kotlin,
                  **keys[dependabot_key])
        problems = violations(run, decide(run), dependabot_key)
        if problems:
            failures += 1
            print(f"FAIL {run}: {'; '.join(problems)}")

    print(f"\n{len(TRUTH_TABLE)} table rows and {combinations} combinations checked.")
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s).")
        sys.exit(1)
    print("SELF-TEST PASSED.")


def main() -> None:
    """Runs the self-test or decides for the current run."""
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif not sys.argv[1:]:
        write_outputs(decide(run_from_environment()))
    else:
        raise SystemExit("usage: decide-ios-tests.py [--self-test]")


if __name__ == "__main__":
    main()
