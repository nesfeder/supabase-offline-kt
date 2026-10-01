#!/usr/bin/env python3
"""The steps of the Release workflow that are not Gradle: its guards, its notes, and what follows
once Maven Central has published a version. RELEASING.md describes the process around them.

Commands (from the repository root):

  check-version [--head REF] [--github-output FILE]
      The release's guards, on the files committed at REF (default HEAD). It fails unless:
        * VERSION_NAME in gradle.properties is a release version, X.Y.Z (not -SNAPSHOT);
        * no tag X.Y.Z exists in this clone (fetch the tags first);
        * CHANGELOG.md has a section for X.Y.Z (`## [X.Y.Z] - YYYY-MM-DD`) with at least one entry,
          and no entry is left under `## Unreleased`;
        * the supabase-kt table of docs/project/compatibility.md has a row for X.Y.Z naming the
          catalog's supabase-kt version.
  release-version [--github-output FILE]
      Reports VERSION_NAME, and fails unless it is a release version X.Y.Z. The job that tags takes
      the version from its own checkout, never from the job that ran the build and the tests.
  check-resume --sha SHA [--repository URL] [--github-output FILE]
      The guards for finishing a release whose publish succeeded but whose later steps did not:
      SHA is on origin/main and its VERSION_NAME is X.Y.Z; CHANGELOG.md has X.Y.Z's section; a
      tag X.Y.Z, if there is one, points at SHA; and the Maven repository (default Maven Central)
      serves the POM of every artifact compat/published-artifacts.txt lists, at X.Y.Z.
  snapshot-version [--github-output FILE]
      Reports VERSION_NAME and whether it is a snapshot, for CI's snapshot job.
  notes VERSION
      Prints the changelog's section for VERSION, without its heading or empty subheadings.
  push-tag --version X.Y.Z --sha SHA
      Pushes the tag X.Y.Z at SHA to origin, or does nothing when it is already there at SHA.
  publish-github-release --version X.Y.Z
      Creates the GitHub Release for the existing tag from the changelog's section, not as a draft,
      or does nothing when the release exists.
  push-post-release-branch --version X.Y.Z
      Pushes the branch that starts the next development version (see next-snapshot), committed
      on origin/main's latest commit as github-actions[bot], and prints, and writes to the job
      summary, the link that opens its pull request. GitHub Actions may not open pull requests in
      this repository, so the owner opens it with that link, and CI then starts by itself. It does
      nothing but print the link when the branch exists, and nothing when main has moved on.
  next-snapshot VERSION
      Rewrites the working tree for the version after VERSION: VERSION_NAME becomes the next
      minor's -SNAPSHOT, CHANGELOG.md gets an empty `## Unreleased` section again, the
      compatibility page an `Unreleased` row, and compat/overrides.txt is emptied of findings.
  self-test
      Runs every command against throwaway repositories, with a stand-in for `gh`.

Every command that changes something outside the working tree can be run again after a failure:
it does what is missing and leaves what is done. Standard library only; runs on the system python3
of macOS (3.9) and on CI runners.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import textwrap
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

GRADLE_PROPERTIES = "gradle.properties"
CHANGELOG = "CHANGELOG.md"
COMPATIBILITY = "docs/project/compatibility.md"
CATALOG = "gradle/libs.versions.toml"
OVERRIDES = "compat/overrides.txt"
PUBLISHED_RECORD = "compat/published-artifacts.txt"
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
RELEASE_VERSION = re.compile(r"^\d+\.\d+\.\d+$")
CHANGELOG_HEADINGS = ["Breaking", "Added", "Changed", "Deprecated", "Fixed"]
BOT_NAME = "github-actions[bot]"
# GitHub's own address for its Actions bot, which shows the commits under that bot's name.
BOT_EMAIL = "41898282+github-actions[bot]@users.noreply.github.com"


class ReleaseError(Exception):
    """A guard that failed, or a step that cannot go on; the message says which and why."""


def git(root: Path, *arguments: str, check: bool = True) -> str:
    """Runs git in root and returns its standard output."""
    completed = subprocess.run(["git", "-C", str(root), *arguments], stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, universal_newlines=True)
    if check and completed.returncode != 0:
        raise ReleaseError(f"git {' '.join(arguments)} failed: {completed.stderr.strip()}")
    return completed.stdout


def committed_file(root: Path, ref: str, path: str) -> str:
    """The file as committed at ref, or an empty string when it does not exist there."""
    completed = subprocess.run(["git", "-C", str(root), "show", f"{ref}:{path}"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    return completed.stdout if completed.returncode == 0 else ""


def version_name(properties: str) -> str:
    """VERSION_NAME from the text of gradle.properties, or an empty string."""
    match = re.search(r"^VERSION_NAME=(\S+)\s*$", properties, re.MULTILINE)
    return match.group(1) if match else ""


def catalog_version(catalog: str, key: str) -> str:
    """A version from the text of the version catalog, or an empty string."""
    match = re.search(rf'^{re.escape(key)}\s*=\s*"([^"]+)"', catalog, re.MULTILINE)
    return match.group(1) if match else ""


def section_heading(version: str) -> re.Pattern:
    """A release's changelog heading: `## [X.Y.Z] - YYYY-MM-DD`, or `## X.Y.Z` with no date."""
    return re.compile(rf"^## \[?{re.escape(version)}\]?(?:\s+-\s+\d{{4}}-\d{{2}}-\d{{2}})?\s*$")


def changelog_section(changelog: str, heading: re.Pattern) -> list[str] | None:
    """The lines under the first `## ` heading heading matches, up to the next; None without one."""
    lines = changelog.splitlines()
    for index, line in enumerate(lines):
        if heading.match(line):
            body = []
            for following in lines[index + 1:]:
                if following.startswith("## "):
                    break
                body.append(following)
            return body
    return None


def entries(section: list[str]) -> list[str]:
    """The bullet lines of a changelog section."""
    return [line for line in section if re.match(r"^\s*[-*] \S", line)]


def compatibility_rows(page: str) -> dict[str, str]:
    """The supabase-kt table of the compatibility page, as release (first cell) to version."""
    rows = {}
    for line in page.splitlines():
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if line.lstrip().startswith("|") and len(cells) == 2 and not set(cells[0]) <= set("-: "):
            rows[cells[0]] = cells[1]
    return rows


def write_outputs(path: str | None, values: dict[str, str]) -> None:
    """Appends the values to the GitHub Actions output file, when there is one."""
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            for key, value in values.items():
                handle.write(f"{key}={value}\n")


# ─────────────────────────────────────────────────────────────────────────────
# Guards
# ─────────────────────────────────────────────────────────────────────────────


def check_version(root: Path, ref: str) -> str:
    """The release's guards at ref; returns the version being released."""
    version = version_name(committed_file(root, ref, GRADLE_PROPERTIES))
    if not version:
        raise ReleaseError(f"{GRADLE_PROPERTIES} at {ref} has no VERSION_NAME")
    if version.endswith("-SNAPSHOT"):
        raise ReleaseError(f"VERSION_NAME is {version}, a snapshot: the pull request that prepares a"
                           " release sets it to the version without -SNAPSHOT (RELEASING.md)")
    if not RELEASE_VERSION.match(version):
        raise ReleaseError(f"VERSION_NAME is {version!r}, not a release version X.Y.Z")
    if git(root, "tag", "--list", version).strip():
        raise ReleaseError(f"the tag {version} already exists: {version} was released, or"
                           " half-released (RELEASING.md, \"When a release stops halfway\")")
    changelog = committed_file(root, ref, CHANGELOG)
    section = changelog_section(changelog, section_heading(version))
    if section is None:
        raise ReleaseError(f"{CHANGELOG} has no section for {version}: rename `## Unreleased` to"
                           f" `## [{version}] - YYYY-MM-DD` in the pull request that prepares it")
    if not entries(section):
        raise ReleaseError(f"{CHANGELOG}'s section for {version} has no entry")
    unreleased = changelog_section(changelog, re.compile(r"^## Unreleased\s*$"))
    if unreleased and entries(unreleased):
        raise ReleaseError(f"{CHANGELOG} still has entries under `## Unreleased`, which {version}"
                           " would ship without listing: move them into its section")
    supabase = catalog_version(committed_file(root, ref, CATALOG), "supabase-bom")
    rows = compatibility_rows(committed_file(root, ref, COMPATIBILITY))
    if version not in rows:
        raise ReleaseError(f"the supabase-kt table in {COMPATIBILITY} has no row for {version}:"
                           f" rename its `Unreleased` row to {version}")
    if rows[version] != supabase:
        raise ReleaseError(f"the row for {version} in {COMPATIBILITY} names supabase-kt"
                           f" {rows[version]}, but the catalog pins {supabase}")
    return version


def release_version(root: Path) -> str:
    """VERSION_NAME of the working tree, which must be a release version."""
    version = version_name((root / GRADLE_PROPERTIES).read_text(encoding="utf-8"))
    if not RELEASE_VERSION.match(version):
        raise ReleaseError(f"VERSION_NAME is {version!r}, not a release version X.Y.Z")
    return version


def published_artifacts(root: Path, ref: str) -> list[str]:
    """The artifact names compat/published-artifacts.txt lists at ref."""
    names = []
    for line in committed_file(root, ref, PUBLISHED_RECORD).splitlines():
        if line.strip() and not line.startswith("#") and line.split()[0] not in names:
            names.append(line.split()[0])
    return names


def serves(url: str) -> bool:
    """Whether the URL answers with the file (a HEAD request, or a read for file: URLs)."""
    try:
        request = urllib.request.Request(url, method="HEAD" if url.startswith("http") else "GET")
        with urllib.request.urlopen(request, timeout=30) as response:
            # A file: URL that opens has no status; an http one answers 200 or raises.
            return getattr(response, "status", None) in (None, 200)
    except (urllib.error.URLError, OSError):
        return False


def check_resume(root: Path, sha: str, repository: str) -> str:
    """The guards for finishing a published release at sha; returns its version."""
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ReleaseError(f"{sha!r} is not a full commit SHA: copy it from the failed run's summary")
    git(root, "cat-file", "-e", f"{sha}^{{commit}}")
    if subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", sha, "origin/main"]).returncode:
        raise ReleaseError(f"{sha} is not on origin/main")
    version = version_name(committed_file(root, sha, GRADLE_PROPERTIES))
    if not RELEASE_VERSION.match(version):
        raise ReleaseError(f"VERSION_NAME at {sha} is {version!r}, not a release version X.Y.Z")
    if changelog_section(committed_file(root, sha, CHANGELOG), section_heading(version)) is None:
        raise ReleaseError(f"{CHANGELOG} at {sha} has no section for {version}")
    tagged = git(root, "rev-parse", "--verify", "--quiet", f"refs/tags/{version}^{{commit}}", check=False).strip()
    if tagged and tagged != sha:
        raise ReleaseError(f"the tag {version} already points at {tagged}, not {sha}")
    group = re.search(r"^GROUP=(\S+)", committed_file(root, sha, GRADLE_PROPERTIES), re.MULTILINE)
    artifacts = published_artifacts(root, sha)
    if not group or not artifacts:
        raise ReleaseError(f"no GROUP or no {PUBLISHED_RECORD} at {sha}")
    missing = []
    for artifact in artifacts:
        url = f"{repository}/{group.group(1).replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
        if not serves(url):
            missing.append(url)
    if missing:
        raise ReleaseError(f"{len(missing)} of {len(artifacts)} artifacts of {version} are not on the"
                           " Maven repository yet (it can lag the Portal's PUBLISHED by up to an hour),"
                           " so resuming would tag a version nobody can resolve:\n  " + "\n  ".join(missing))
    return version


# ─────────────────────────────────────────────────────────────────────────────
# Notes and the next development version
# ─────────────────────────────────────────────────────────────────────────────


def release_notes(changelog: str, version: str) -> str:
    """The changelog's section for version, without empty subheadings, as Markdown."""
    section = changelog_section(changelog, section_heading(version))
    if section is None:
        raise ReleaseError(f"{CHANGELOG} has no section for {version}")
    blocks: list[list[str]] = [[]]
    for line in section:
        if line.startswith("### "):
            blocks.append([line])
        else:
            blocks[-1].append(line)
    kept = [block for block in blocks if block and (not block[0].startswith("### ") or entries(block))]
    return "\n\n".join("\n".join(block).strip() for block in kept if "\n".join(block).strip()) + "\n"


def next_version(version: str) -> str:
    """The next minor's snapshot: 0.1.0 and 0.1.3 are followed by 0.2.0-SNAPSHOT."""
    major, minor, _ = (int(part) for part in version.split("."))
    return f"{major}.{minor + 1}.0-SNAPSHOT"


def next_snapshot(root: Path, version: str) -> str:
    """Rewrites the working tree for the version after version; returns the new VERSION_NAME."""
    properties = (root / GRADLE_PROPERTIES).read_text(encoding="utf-8")
    current = version_name(properties)
    if current != version:
        raise ReleaseError(f"VERSION_NAME on main is {current}, not {version}: the next version has"
                           " been started already, or the release is not the one on main")
    following = next_version(version)
    (root / GRADLE_PROPERTIES).write_text(
        re.sub(r"^VERSION_NAME=\S+\s*$", f"VERSION_NAME={following}", properties, count=1,
               flags=re.MULTILINE), encoding="utf-8")

    changelog = (root / CHANGELOG).read_text(encoding="utf-8")
    if changelog_section(changelog, re.compile(r"^## Unreleased\s*$")) is None:
        lines = changelog.splitlines(keepends=True)
        heading = section_heading(version)
        index = next((position for position, line in enumerate(lines) if heading.match(line.rstrip("\n"))), None)
        if index is None:
            raise ReleaseError(f"{CHANGELOG} has no section for {version}")
        unreleased = "## Unreleased\n\n" + "".join(f"### {name}\n\n" for name in CHANGELOG_HEADINGS)
        (root / CHANGELOG).write_text("".join(lines[:index]) + unreleased + "".join(lines[index:]),
                                      encoding="utf-8")

    page = (root / COMPATIBILITY).read_text(encoding="utf-8")
    rows = compatibility_rows(page)
    if "Unreleased" not in rows:
        if version not in rows:
            raise ReleaseError(f"the supabase-kt table in {COMPATIBILITY} has no row for {version}")
        supabase = catalog_version((root / CATALOG).read_text(encoding="utf-8"), "supabase-bom")
        released_row = re.compile(rf"^\|\s*{re.escape(version)}\s*\|", re.MULTILINE)
        page = released_row.sub(lambda match: f"| Unreleased | {supabase} |\n" + match.group(0), page, count=1)
        (root / COMPATIBILITY).write_text(page, encoding="utf-8")

    overrides = root / OVERRIDES
    if overrides.is_file():
        kept = [line for line in overrides.read_text(encoding="utf-8").splitlines(keepends=True)
                if not line.strip() or line.startswith("#")]
        overrides.write_text("".join(kept).rstrip("\n") + "\n", encoding="utf-8")
    return following


# ─────────────────────────────────────────────────────────────────────────────
# After Maven Central: the tag, the GitHub Release and the post-release pull request
# ─────────────────────────────────────────────────────────────────────────────


def remote_tag(root: Path, version: str) -> str:
    """The commit origin's tag version points at, or an empty string when it has none."""
    listing = git(root, "ls-remote", "--tags", "origin", f"refs/tags/{version}", f"refs/tags/{version}^{{}}")
    commits = {}
    for line in listing.splitlines():
        sha, ref = line.split("\t")
        commits[ref] = sha
    return commits.get(f"refs/tags/{version}^{{}}", commits.get(f"refs/tags/{version}", ""))


def push_tag(root: Path, version: str, sha: str) -> str:
    """Pushes the tag at sha unless origin has it there; fails when origin has it elsewhere."""
    existing = remote_tag(root, version)
    if existing == sha:
        return f"The tag {version} is already on origin at {sha}."
    if existing:
        raise ReleaseError(f"origin's tag {version} points at {existing}, not {sha}: never move a"
                           " published tag (RELEASING.md)")
    git(root, "push", "origin", f"{sha}:refs/tags/{version}")
    return f"Pushed the tag {version} at {sha}."


def gh(root: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess:
    """Runs the GitHub CLI in root."""
    completed = subprocess.run(["gh", *arguments], cwd=root, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, universal_newlines=True)
    if check and completed.returncode != 0:
        raise ReleaseError(f"gh {' '.join(arguments[:2])} failed: {completed.stderr.strip()}")
    return completed


def publish_github_release(root: Path, version: str) -> str:
    """Creates the release for the tag, unless it exists: the changelog's section, then GitHub's
    generated list of the merged pull requests, grouped as `.github/release.yml` says."""
    existing = gh(root, "release", "view", version, "--json", "url,isDraft", check=False)
    if existing.returncode == 0:
        details = json.loads(existing.stdout or "{}")
        if details.get("isDraft"):
            raise ReleaseError(f"the GitHub Release {version} exists as a draft: publish or delete it by"
                               " hand, then run this again")
        return f"The GitHub Release {version} already exists: {details.get('url', '')}"
    with tempfile.TemporaryDirectory() as directory:
        notes = Path(directory) / "notes.md"
        notes.write_text(release_notes((root / CHANGELOG).read_text(encoding="utf-8"), version),
                         encoding="utf-8")
        created = gh(root, "release", "create", version, "--verify-tag", "--title", version,
                     "--notes-file", str(notes), "--generate-notes")
    return f"Created the GitHub Release {version}: {created.stdout.strip()}"


def post_release_body(version: str, following: str, group: str) -> str:
    """The post-release pull request's description: what it does and what to check."""
    return textwrap.dedent(f"""\
        {version} is on Maven Central and tagged. This pull request starts the next development
        version, as RELEASING.md describes:

        - `VERSION_NAME` is `{following}` (`gradle.properties`). If the next release is a patch, change
          it in the pull request that prepares that release.
        - `CHANGELOG.md` has an empty `## Unreleased` section again, above `{version}`'s.
        - The supabase-kt table of `docs/project/compatibility.md` has an `Unreleased` row again.
        - `compat/overrides.txt` holds no finding: the overrides {version} shipped with are spent.

        Before merging, check:

        - [ ] Both artifacts resolve at {version}:
              https://repo1.maven.org/maven2/{group.replace('.', '/')}/supabase-offline-kt/{version}/ and
              https://repo1.maven.org/maven2/{group.replace('.', '/')}/supabase-offline-kt-testing/{version}/
              (the Central Portal's search can take a few hours longer).
        - [ ] The GitHub Release {version} shows the changelog's section, and is not a draft.
        - [ ] The diff touches only the four files above.
        """)


def repository_url(root: Path) -> str:
    """The repository's web address: from the Actions environment, or from origin's URL."""
    server, repository = os.environ.get("GITHUB_SERVER_URL"), os.environ.get("GITHUB_REPOSITORY")
    if server and repository:
        return f"{server}/{repository}"
    return web_address(git(root, "remote", "get-url", "origin").strip())


def web_address(remote: str) -> str:
    """A GitHub remote's web address (SSH or HTTPS, with or without `.git`); others unchanged."""
    match = re.match(r"^(?:git@([^:]+):|https?://(?:[^@/]+@)?([^/]+)/)(.+?)(?:\.git)?/?$", remote)
    if not match:
        return remote
    return f"https://{match.group(1) or match.group(2)}/{match.group(3)}"


def post_release_link(root: Path, branch: str, version: str, following: str, group: str) -> str:
    """The link that opens the post-release pull request with its title and description filled in."""
    query = urllib.parse.urlencode({
        "expand": "1",
        "title": f"chore(release): start {following} after {version}",
        "body": post_release_body(version, following, group),
    }, quote_via=urllib.parse.quote)
    return f"{repository_url(root)}/compare/main...{branch}?{query}"


def push_post_release_branch(root: Path, version: str) -> str:
    """Pushes the branch for the next development version, unless it exists or main has moved on,
    and reports the link that opens its pull request."""
    branch = f"release/{version}-next-snapshot"
    following = next_version(version)
    git(root, "fetch", "--no-tags", "origin", "refs/heads/main")
    group = re.search(r"^GROUP=(\S+)", committed_file(root, "FETCH_HEAD", GRADLE_PROPERTIES), re.MULTILINE)
    if git(root, "ls-remote", "--heads", "origin", f"refs/heads/{branch}").strip():
        pushed = f"The branch {branch} exists already."
    else:
        on_main = version_name(committed_file(root, "FETCH_HEAD", GRADLE_PROPERTIES))
        if on_main != version:
            return (f"main's VERSION_NAME is {on_main}, not {version}: the next development version"
                    " has been started already, so there is no branch to push.")
        git(root, "switch", "--quiet", "--create", branch, "FETCH_HEAD")
        next_snapshot(root, version)
        git(root, "add", GRADLE_PROPERTIES, CHANGELOG, COMPATIBILITY, OVERRIDES)
        git(root, "-c", f"user.name={BOT_NAME}", "-c", f"user.email={BOT_EMAIL}", "commit", "--quiet",
            "-m", f"chore(release): start {following} after {version}")
        git(root, "push", "origin", f"HEAD:refs/heads/{branch}")
        pushed = f"Pushed the branch {branch}, which starts {following}."
    link = post_release_link(root, branch, version, following, group.group(1) if group else "")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(f"### Open the post-release pull request\n\n{pushed} "
                         f"[Open its pull request]({link}), check it as its description says, and merge"
                         " it. CI starts by itself once you have opened it.\n")
    return f"{pushed} Open its pull request: {link}"


# ─────────────────────────────────────────────────────────────────────────────
# Self-test
# ─────────────────────────────────────────────────────────────────────────────

FIXTURE_CHANGELOG = """\
# Changelog

Intro.

## Unreleased

### Breaking

### Added

### Changed

### Deprecated

### Fixed

## [0.1.0] - 2026-10-01

### Breaking

### Added

- Offline reads.
- Queued writes.

### Changed

### Fixed

- A fix.
"""
FIXTURE_COMPATIBILITY = """\
# Compatibility

| supabase-offline-kt | supabase-kt |
| --- | --- |
| 0.1.0 | 3.8.0 |

Each release adds its row.
"""
FIXTURE_FILES = {
    GRADLE_PROPERTIES: "GROUP=io.example.group\nVERSION_NAME=0.1.0\n",
    CATALOG: '[versions]\nsupabase-bom = "3.8.0"\n',
    COMPATIBILITY: FIXTURE_COMPATIBILITY,
    OVERRIDES: "# Findings a reviewer judged compatible.\n\nkotlin-removed: lib :: fun a()  # made open\n",
    PUBLISHED_RECORD: "# The record.\nlib file .pom\nlib-android file .aar\n",
}
# A stand-in for the GitHub CLI: it records each call, and answers from a JSON state file.
FAKE_GH = """\
#!/usr/bin/env python3
import json, os, sys
state_path = os.environ["FAKE_GH_STATE"]
state = json.load(open(state_path)) if os.path.exists(state_path) else {"releases": {}}
state.setdefault("calls", []).append(sys.argv[1:])
args = sys.argv[1:]
code = 0
if args[:2] == ["release", "view"]:
    release = state["releases"].get(args[2])
    if release is None:
        sys.stderr.write("release not found\\n"); code = 1
    else:
        print(json.dumps(release))
elif args[:2] == ["release", "create"]:
    notes = open(args[args.index("--notes-file") + 1]).read()
    state["releases"][args[2]] = {"url": "https://example.invalid/releases/" + args[2], "isDraft": False, "notes": notes}
    print("https://example.invalid/releases/" + args[2])
else:
    sys.stderr.write("the stand-in answers only `release view` and `release create`\\n"); code = 2
json.dump(state, open(state_path, "w"))
sys.exit(code)
"""


def make_repository(work: Path, name: str, files: dict[str, str]) -> Path:
    """A git repository with files committed on main, and a bare origin it has pushed to."""
    origin = work / f"{name}-origin.git"
    root = work / name
    subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(origin)], check=True)
    subprocess.run(["git", "init", "-q", "-b", "main", str(root)], check=True)
    for path, content in files.items():
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        (root / path).write_text(content, encoding="utf-8")
    git(root, "add", "-A")
    git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-q", "-m", "base")
    git(root, "remote", "add", "origin", str(origin))
    git(root, "push", "-q", "origin", "main")
    git(root, "fetch", "-q", "origin")
    return root


def commit_change(root: Path, path: str, content: str) -> str:
    """Commits one file's new content on the current branch and returns the commit."""
    (root / path).write_text(content, encoding="utf-8")
    git(root, "add", path)
    git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-q", "-m", path)
    return git(root, "rev-parse", "HEAD").strip()


def self_test() -> int:
    """Runs each command's cases against throwaway repositories."""
    failures = []

    def expect(condition: bool, name: str) -> None:
        print(f"{'ok  ' if condition else 'FAIL'} {name}")
        if not condition:
            failures.append(name)

    def refused(call, phrase: str) -> bool:
        try:
            call()
        except ReleaseError as error:
            return phrase in str(error)
        return False

    base = dict(FIXTURE_FILES, **{CHANGELOG: FIXTURE_CHANGELOG})
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)

        root = make_repository(work, "guards", base)
        expect(check_version(root, "HEAD") == "0.1.0", "check-version passes a prepared release")
        cases = [
            ("a snapshot version", GRADLE_PROPERTIES, "GROUP=io.example.group\nVERSION_NAME=0.1.0-SNAPSHOT\n", "a snapshot"),
            ("a version that is not X.Y.Z", GRADLE_PROPERTIES, "VERSION_NAME=0.1\n", "not a release version"),
            ("no changelog section", CHANGELOG, FIXTURE_CHANGELOG.replace("## [0.1.0] - 2026-10-01", "## [0.0.9] - 2026-10-01"), "no section for 0.1.0"),
            ("an empty changelog section", CHANGELOG, FIXTURE_CHANGELOG.split("## [0.1.0]")[0] + "## [0.1.0] - 2026-10-01\n\n### Added\n", "has no entry"),
            ("entries left under Unreleased", CHANGELOG, FIXTURE_CHANGELOG.replace("## Unreleased\n\n### Breaking\n", "## Unreleased\n\n### Breaking\n\n- Left behind.\n"), "still has entries"),
            ("no compatibility row", COMPATIBILITY, FIXTURE_COMPATIBILITY.replace("| 0.1.0 |", "| Unreleased |"), "has no row for 0.1.0"),
            ("a compatibility row naming another supabase-kt", COMPATIBILITY, FIXTURE_COMPATIBILITY.replace("3.8.0", "3.7.0"), "names supabase-kt 3.7.0"),
        ]
        for name, path, content, phrase in cases:
            case = make_repository(work, f"guard-{cases.index((name, path, content, phrase))}", dict(base, **{path: content}))
            expect(refused(lambda: check_version(case, "HEAD"), phrase), f"check-version refuses {name}")
        tagged = make_repository(work, "tagged", base)
        git(tagged, "tag", "0.1.0")
        expect(refused(lambda: check_version(tagged, "HEAD"), "already exists"), "check-version refuses an existing tag")
        undated = make_repository(work, "undated", dict(base, **{CHANGELOG: FIXTURE_CHANGELOG.replace("## [0.1.0] - 2026-10-01", "## 0.1.0")}))
        expect(check_version(undated, "HEAD") == "0.1.0", "check-version accepts a heading with no date")

        notes = release_notes(FIXTURE_CHANGELOG, "0.1.0")
        expect(notes == "### Added\n\n- Offline reads.\n- Queued writes.\n\n### Fixed\n\n- A fix.\n",
               "notes keep the section's entries and drop its heading and empty subheadings")

        expect(next_version("0.1.0") == "0.2.0-SNAPSHOT" and next_version("1.4.2") == "1.5.0-SNAPSHOT",
               "the next version is the next minor's snapshot")
        bumped = make_repository(work, "bumped", dict(base, **{CHANGELOG: FIXTURE_CHANGELOG.split("## Unreleased")[0] + "## [0.1.0]" + FIXTURE_CHANGELOG.split("## [0.1.0]")[1]}))
        following = next_snapshot(bumped, "0.1.0")
        changelog = (bumped / CHANGELOG).read_text()
        page = (bumped / COMPATIBILITY).read_text()
        expect(following == "0.2.0-SNAPSHOT" and "VERSION_NAME=0.2.0-SNAPSHOT" in (bumped / GRADLE_PROPERTIES).read_text(),
               "next-snapshot sets VERSION_NAME")
        expect(changelog.index("## Unreleased") < changelog.index("## [0.1.0]")
               and all(f"### {name}" in changelog.split("## [0.1.0]")[0] for name in CHANGELOG_HEADINGS),
               "next-snapshot adds an empty Unreleased section above the release's")
        expect(page.index("| Unreleased | 3.8.0 |") < page.index("| 0.1.0 | 3.8.0 |"),
               "next-snapshot adds the Unreleased row above the release's")
        expect("kotlin-removed" not in (bumped / OVERRIDES).read_text() and "# Findings" in (bumped / OVERRIDES).read_text(),
               "next-snapshot empties the overrides and keeps their explanation")
        expect(refused(lambda: next_snapshot(bumped, "0.1.0"), "has been started already"),
               "next-snapshot refuses to run twice")

        central = work / "central"
        for artifact in ("lib", "lib-android"):
            pom = central / "io/example/group" / artifact / "0.1.0" / f"{artifact}-0.1.0.pom"
            pom.parent.mkdir(parents=True)
            pom.write_text("<project/>")
        resume = make_repository(work, "resume", base)
        released = git(resume, "rev-parse", "HEAD").strip()
        later = commit_change(resume, "README.md", "later\n")
        git(resume, "push", "-q", "origin", "main")
        git(resume, "fetch", "-q", "origin")
        repository = central.as_uri()
        expect(check_resume(resume, released, repository) == "0.1.0", "check-resume passes a published release")
        expect(refused(lambda: check_resume(resume, "abc", repository), "not a full commit SHA"), "check-resume refuses a short SHA")
        side = git(resume, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                   "commit-tree", "-m", "side", f"{released}^{{tree}}").strip()
        expect(refused(lambda: check_resume(resume, side, repository), "not on origin/main"), "check-resume refuses a commit off main")
        (central / "io/example/group/lib-android/0.1.0/lib-android-0.1.0.pom").unlink()
        expect(refused(lambda: check_resume(resume, released, repository), "1 of 2 artifacts"),
               "check-resume refuses a version Maven Central does not serve")
        git(resume, "tag", "0.1.0", later)
        expect(refused(lambda: check_resume(resume, released, repository), "already points at"),
               "check-resume refuses a tag at another commit")

        # The steps after Maven Central, against a bare origin and a stand-in for gh.
        tools = work / "bin"
        tools.mkdir()
        (tools / "gh").write_text(FAKE_GH)
        (tools / "gh").chmod((tools / "gh").stat().st_mode | stat.S_IEXEC)
        os.environ["PATH"] = f"{tools}{os.pathsep}{os.environ['PATH']}"
        os.environ["FAKE_GH_STATE"] = str(work / "gh-state.json")
        finish = make_repository(work, "finish", base)
        sha = git(finish, "rev-parse", "HEAD").strip()
        commit_change(finish, "README.md", "a later change on main\n")
        git(finish, "push", "-q", "origin", "main")
        git(finish, "checkout", "-q", "--detach", sha)
        expect(push_tag(finish, "0.1.0", sha).startswith("Pushed"), "push-tag pushes the tag")
        expect("already on origin" in push_tag(finish, "0.1.0", sha), "push-tag again does nothing")
        expect(refused(lambda: push_tag(finish, "0.1.0", git(finish, "rev-parse", "origin/main").strip()), "never move"),
               "push-tag refuses to move a tag")
        expect(publish_github_release(finish, "0.1.0").startswith("Created"), "publish-github-release creates the release")
        state = json.loads((work / "gh-state.json").read_text())
        expect("Offline reads" in state["releases"]["0.1.0"]["notes"] and "--verify-tag" in state["calls"][-1],
               "the release carries the changelog's section, for the existing tag")
        expect("--generate-notes" in state["calls"][-1] and state["calls"][-1].count("--title") == 1,
               "the release lists the merged pull requests beneath the section, under its own title")
        expect("already exists" in publish_github_release(finish, "0.1.0"), "publish-github-release again does nothing")
        summary = work / "summary.md"
        os.environ["GITHUB_STEP_SUMMARY"] = str(summary)
        os.environ["GITHUB_SERVER_URL"], os.environ["GITHUB_REPOSITORY"] = "https://github.com", "owner/repo"
        message = push_post_release_branch(finish, "0.1.0")
        expect(message.startswith("Pushed the branch release/0.1.0-next-snapshot"),
               "push-post-release-branch pushes the branch")
        branch_head = git(finish, "rev-parse", "origin/main").strip()
        pushed = git(finish, "ls-remote", "--heads", "origin", "refs/heads/release/0.1.0-next-snapshot").split()
        log = git(finish, "log", "-1", "--format=%an <%ae>|%s|%P", pushed[0] if pushed else "HEAD")
        expect(bool(pushed) and log.startswith(f"{BOT_NAME} <{BOT_EMAIL}>") and log.strip().endswith(branch_head),
               "the branch's commit is the bot's, on top of main's latest commit")
        link = message.split("Open its pull request: ", 1)[-1]
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)
        expect(link.startswith("https://github.com/owner/repo/compare/main...release/0.1.0-next-snapshot?")
               and query.get("expand") == ["1"]
               and query.get("title") == ["chore(release): start 0.2.0-SNAPSHOT after 0.1.0"]
               and "Before merging, check" in query.get("body", [""])[0]
               and "0.2.0-SNAPSHOT" in query.get("body", [""])[0],
               "the link opens the pull request with its title and a description that says what to check")
        expect(link in summary.read_text(), "the link is in the job summary")
        again = push_post_release_branch(finish, "0.1.0")
        expect(again.startswith("The branch release/0.1.0-next-snapshot exists already") and link in again,
               "push-post-release-branch again pushes nothing and gives the link again")
        git(finish, "push", "-q", "origin", "--delete", "release/0.1.0-next-snapshot")
        git(finish, "push", "-q", "origin", "refs/heads/release/0.1.0-next-snapshot:refs/heads/main")
        expect("has been started already" in push_post_release_branch(finish, "0.1.0"),
               "push-post-release-branch pushes nothing once main has moved on to the next version")
        state = json.loads((work / "gh-state.json").read_text())
        expect(all(call[0] == "release" for call in state["calls"]),
               "nothing asks GitHub to open a pull request")
        del os.environ["GITHUB_SERVER_URL"], os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_STEP_SUMMARY"]
        expect(web_address("git@github.com:owner/repo.git") == "https://github.com/owner/repo"
               and web_address("https://github.com/owner/repo.git") == "https://github.com/owner/repo"
               and web_address("https://x-access-token@github.com/owner/repo") == "https://github.com/owner/repo",
               "without the Actions environment the link comes from origin's address")
        released = make_repository(work, "released", dict(base, **{GRADLE_PROPERTIES: "GROUP=io.example.group\nVERSION_NAME=0.1.0\n"}))
        expect(release_version(released) == "0.1.0", "release-version reads a release version")
        snapshotted = make_repository(work, "snapshotted", FIXTURE_FILES)
        (snapshotted / GRADLE_PROPERTIES).write_text("VERSION_NAME=0.2.0-SNAPSHOT\n")
        expect(refused(lambda: release_version(snapshotted), "not a release version"),
               "release-version refuses a snapshot")
    if failures:
        print(f"SELF-TEST FAILED: {len(failures)} check(s).")
        return 1
    print("SELF-TEST PASSED.")
    return 0


def main(argv: list[str]) -> int:
    """Parses the command line and runs one command."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("check-version")
    check.add_argument("--head", default="HEAD")
    check.add_argument("--github-output")
    resume = commands.add_parser("check-resume")
    resume.add_argument("--sha", required=True)
    resume.add_argument("--repository", default=MAVEN_CENTRAL)
    resume.add_argument("--github-output")
    snapshot = commands.add_parser("snapshot-version")
    snapshot.add_argument("--github-output")
    commands.add_parser("notes").add_argument("version")
    tag = commands.add_parser("push-tag")
    tag.add_argument("--version", required=True)
    tag.add_argument("--sha", required=True)
    commands.add_parser("publish-github-release").add_argument("--version", required=True)
    commands.add_parser("push-post-release-branch").add_argument("--version", required=True)
    released = commands.add_parser("release-version")
    released.add_argument("--github-output")
    commands.add_parser("next-snapshot").add_argument("version")
    commands.add_parser("self-test")
    arguments = parser.parse_args(argv)
    root = Path.cwd()
    try:
        if arguments.command == "self-test":
            return self_test()
        if arguments.command == "check-version":
            version = check_version(root, arguments.head)
            sha = git(root, "rev-parse", arguments.head).strip()
            write_outputs(arguments.github_output, {"version": version, "sha": sha})
            print(f"Releasing {version} from {sha}: every guard passed.")
        elif arguments.command == "check-resume":
            version = check_resume(root, arguments.sha, arguments.repository)
            write_outputs(arguments.github_output, {"version": version, "sha": arguments.sha})
            print(f"Finishing {version} at {arguments.sha}: it is on the Maven repository.")
        elif arguments.command == "snapshot-version":
            version = version_name((root / GRADLE_PROPERTIES).read_text(encoding="utf-8"))
            snapshot_value = "true" if version.endswith("-SNAPSHOT") else "false"
            write_outputs(arguments.github_output, {"version": version, "snapshot": snapshot_value})
            print(f"VERSION_NAME is {version}; snapshot: {snapshot_value}.")
        elif arguments.command == "notes":
            sys.stdout.write(release_notes((root / CHANGELOG).read_text(encoding="utf-8"), arguments.version))
        elif arguments.command == "push-tag":
            print(push_tag(root, arguments.version, arguments.sha))
        elif arguments.command == "publish-github-release":
            print(publish_github_release(root, arguments.version))
        elif arguments.command == "push-post-release-branch":
            print(push_post_release_branch(root, arguments.version))
        elif arguments.command == "release-version":
            version = release_version(root)
            write_outputs(arguments.github_output, {"version": version})
            print(f"VERSION_NAME is {version}.")
        elif arguments.command == "next-snapshot":
            print(f"VERSION_NAME is now {next_snapshot(root, arguments.version)}.")
    except ReleaseError as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
