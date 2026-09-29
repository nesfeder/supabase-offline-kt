#!/usr/bin/env python3
"""Decides where the iOS tests run for this CI run, and whether they may run at all.

Usage:
  decide-ios-tests.py              read the run from the environment, write the decision to
                                   $GITHUB_OUTPUT (ios-runner, ios-allowed, ios-hold, ios-held)
  decide-ios-tests.py --self-test  check the decision against its truth table and print it

Anything unexpected routes to GitHub's hosted runner, never to the maintainer's Mac.
"""

from __future__ import annotations

import itertools
import json
import os
import sys
from dataclasses import dataclass, replace

DEPENDABOT = "dependabot[bot]"
FULL_CI_LABEL = "full-ci"


@dataclass(frozen=True)
class Run:
    """What the decision reads about a run: the event, the repository and who started it."""

    event: str
    private: str = "true"
    local_runner_variable: str = ""
    draft: str = ""
    author: str = ""
    labels: tuple[str, ...] = ()
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


def is_dependabot(run: Run) -> bool:
    """True when Dependabot opened, pushed, re-ran or named the branch of this run."""
    return (
        run.author == DEPENDABOT
        or run.actor == DEPENDABOT
        or run.triggering_actor == DEPENDABOT
        or run.head_ref.startswith("dependabot/")
    )


def decide(run: Run) -> Decision:
    """Applies the routing rule and, on the hosted runner, the private-repository rationing rule."""
    has_full_ci = FULL_CI_LABEL in run.labels
    dependabot = is_dependabot(run)
    local = (
        run.private == "true"
        and run.local_runner_variable == "on"
        and run.event != "schedule"
        and (not dependabot or has_full_ci)
    )

    hold = ""
    if not local and run.private != "false":
        if run.event == "pull_request":
            if run.draft != "false":
                hold = "draft pull request"
            elif dependabot and not has_full_ci:
                hold = f"Dependabot pull request without the {FULL_CI_LABEL} label"
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
    labels = json.loads(environment.get("PULL_REQUEST_LABELS", "") or "null") or []
    if not isinstance(labels, list) or not all(isinstance(label, str) for label in labels):
        raise SystemExit("PULL_REQUEST_LABELS must be a JSON array of strings")
    return Run(
        event=environment.get("EVENT_NAME", ""),
        private=environment.get("REPOSITORY_PRIVATE", ""),
        local_runner_variable=environment.get("IOS_LOCAL_RUNNER", ""),
        draft=environment.get("PULL_REQUEST_DRAFT", ""),
        author=environment.get("PULL_REQUEST_AUTHOR", ""),
        labels=tuple(labels),
        actor=environment.get("ACTOR", ""),
        triggering_actor=environment.get("TRIGGERING_ACTOR", ""),
        head_ref=environment.get("HEAD_REF", ""),
        kotlin=environment.get("KOTLIN", ""),
    )


def write_outputs(decision: Decision) -> None:
    """Writes the decision to the step's outputs and a one-line account to the log."""
    print(
        f"  iOS tests: runner={decision.runner} allowed={str(decision.allowed).lower()}"
        f" held={str(decision.held).lower()}" + (f" ({decision.hold})" if decision.hold else "")
    )
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as outputs:
        outputs.write(f"ios-runner={decision.runner}\n")
        outputs.write(f"ios-allowed={str(decision.allowed).lower()}\n")
        outputs.write(f"ios-hold={decision.hold}\n")
        outputs.write(f"ios-held={str(decision.held).lower()}\n")


# Each row: a run, then the runner, whether the iOS tests may run, and the hold reason expected.
PRIVATE_ON = Run(event="pull_request", private="true", local_runner_variable="on", draft="true")
PRIVATE_OFF = replace(PRIVATE_ON, local_runner_variable="")
DEPENDABOT_PULL_REQUEST = dict(
    author=DEPENDABOT, actor=DEPENDABOT, triggering_actor=DEPENDABOT, head_ref="dependabot/gradle/x"
)
TRUTH_TABLE: list[tuple[str, Run, str, bool, str]] = [
    ("variable on: draft", PRIVATE_ON, "local", True, ""),
    ("variable on: ready", replace(PRIVATE_ON, draft="false"), "local", True, ""),
    ("variable on: push to main", replace(PRIVATE_ON, event="push", draft=""), "local", True, ""),
    ("variable on: manual run", replace(PRIVATE_ON, event="workflow_dispatch", draft=""),
     "local", True, ""),
    ("variable on: merge queue", replace(PRIVATE_ON, event="merge_group", draft=""),
     "local", True, ""),
    ("variable on: weekly schedule", replace(PRIVATE_ON, event="schedule", draft=""),
     "hosted", True, ""),
    ("variable on: Dependabot pull request", replace(PRIVATE_ON, draft="false",
     **DEPENDABOT_PULL_REQUEST), "hosted", False,
     "Dependabot pull request without the full-ci label"),
    ("variable on: Dependabot pull request, full-ci", replace(PRIVATE_ON, draft="false",
     labels=("dependencies", "full-ci"), **DEPENDABOT_PULL_REQUEST), "local", True, ""),
    ("variable on: Dependabot author only", replace(PRIVATE_ON, draft="false",
     author=DEPENDABOT), "hosted", False, "Dependabot pull request without the full-ci label"),
    ("variable on: Dependabot actor only (pull request)", replace(PRIVATE_ON, draft="false",
     actor=DEPENDABOT), "hosted", False, "Dependabot pull request without the full-ci label"),
    ("variable on: Dependabot triggering actor only", replace(PRIVATE_ON, draft="false",
     triggering_actor=DEPENDABOT), "hosted", False,
     "Dependabot pull request without the full-ci label"),
    ("variable on: dependabot/ head ref only", replace(PRIVATE_ON, draft="false",
     head_ref="dependabot/github_actions/y"), "hosted", False,
     "Dependabot pull request without the full-ci label"),
    ("variable on: manual run on a Dependabot branch", replace(PRIVATE_ON,
     event="workflow_dispatch", draft="", head_ref="dependabot/gradle/x"), "hosted", True, ""),
    ("variable on: Dependabot push to its branch", replace(PRIVATE_ON, event="push", draft="",
     actor=DEPENDABOT, head_ref="dependabot/gradle/x"), "hosted", False, "push to main"),
    ("variable off: draft", PRIVATE_OFF, "hosted", False, "draft pull request"),
    ("variable off: ready", replace(PRIVATE_OFF, draft="false"), "hosted", True, ""),
    ("variable off: push to main", replace(PRIVATE_OFF, event="push", draft=""),
     "hosted", False, "push to main"),
    ("variable off: manual run", replace(PRIVATE_OFF, event="workflow_dispatch", draft=""),
     "hosted", True, ""),
    ("variable off: weekly schedule", replace(PRIVATE_OFF, event="schedule", draft=""),
     "hosted", True, ""),
    ("variable off: merge queue", replace(PRIVATE_OFF, event="merge_group", draft=""),
     "hosted", True, ""),
    ("variable off: Dependabot pull request", replace(PRIVATE_OFF, draft="false",
     **DEPENDABOT_PULL_REQUEST), "hosted", False,
     "Dependabot pull request without the full-ci label"),
    ("variable off: Dependabot pull request, full-ci", replace(PRIVATE_OFF, draft="false",
     labels=("full-ci",), **DEPENDABOT_PULL_REQUEST), "hosted", True, ""),
    ("variable 'On' (not exactly on): ready", replace(PRIVATE_ON, local_runner_variable="On",
     draft="false"), "hosted", True, ""),
    ("variable 'true': draft", replace(PRIVATE_ON, local_runner_variable="true"),
     "hosted", False, "draft pull request"),
    ("private field missing, variable on: draft", replace(PRIVATE_ON, private=""),
     "hosted", False, "draft pull request"),
    ("private field missing, variable on: push", replace(PRIVATE_ON, private="", event="push",
     draft=""), "hosted", False, "push to main"),
    ("public, variable on: draft", replace(PRIVATE_ON, private="false"), "hosted", True, ""),
    ("public: push to main", replace(PRIVATE_OFF, private="false", event="push", draft=""),
     "hosted", True, ""),
    ("public: Dependabot pull request", replace(PRIVATE_OFF, private="false", draft="false",
     **DEPENDABOT_PULL_REQUEST), "hosted", True, ""),
    ("draft field missing on a pull request", replace(PRIVATE_OFF, draft=""),
     "hosted", False, "draft pull request"),
    ("unknown event, variable off", replace(PRIVATE_OFF, event="repository_dispatch", draft=""),
     "hosted", False, "repository_dispatch event"),
]


def self_test() -> None:
    """Checks every truth-table row, then invariants over every combination of the inputs."""
    failures = 0
    print(f"{'case':<52} {'runner':<7} {'iOS':<5} hold")
    for name, run, runner, allowed, hold in TRUTH_TABLE:
        decision = decide(run)
        ok = (decision.runner, decision.allowed, decision.hold) == (runner, allowed, hold)
        mark = "" if ok else f"   FAIL: expected {runner} {allowed} {hold!r}"
        print(f"{name:<52} {decision.runner:<7} {'runs' if decision.allowed else 'held':<5}"
              f" {decision.hold}{mark}")
        failures += not ok

    combinations = 0
    for event, private, variable, draft, dependabot_key, labelled, kotlin in itertools.product(
        ["pull_request", "push", "workflow_dispatch", "schedule", "merge_group", "other"],
        ["true", "false", ""],
        ["on", "", "off", "ON"],
        ["true", "false", ""],
        ["none", "author", "actor", "triggering_actor", "head_ref"],
        [False, True],
        ["true", "false", ""],
    ):
        combinations += 1
        fields = {"none": {}, "author": {"author": DEPENDABOT}, "actor": {"actor": DEPENDABOT},
                  "triggering_actor": {"triggering_actor": DEPENDABOT},
                  "head_ref": {"head_ref": "dependabot/npm/z"}}[dependabot_key]
        run = Run(event=event, private=private, local_runner_variable=variable, draft=draft,
                  labels=("full-ci",) if labelled else (), kotlin=kotlin, **fields)
        decision = decide(run)
        problems = []
        if decision.runner == "local" and not (
            private == "true" and variable == "on" and event != "schedule"
            and (dependabot_key == "none" or labelled)
        ):
            problems.append("routed to the Mac outside the rule")
        if decision.runner == "local" and not decision.allowed:
            problems.append("routed to the Mac but held")
        if event == "schedule" and (decision.runner, decision.allowed) != ("hosted", True):
            problems.append("the weekly run must run on GitHub's runner")
        if private == "false" and (decision.runner, decision.allowed) != ("hosted", True):
            problems.append("a public repository must run the iOS tests on GitHub's runner")
        if decision.allowed == bool(decision.hold):
            problems.append("allowed and hold disagree")
        if decision.held != (kotlin == "true" and not decision.allowed):
            problems.append("held is not 'due and not allowed'")
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
