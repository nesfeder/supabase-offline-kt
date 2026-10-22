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
      Publishes the draft GitHub Release X.Y.Z, which the Release run made before its approval
      with the changelog's section as its notes and the Swift package's framework as its asset
      (scripts/swift-release.py draft-release), for the existing tag; or does nothing when the
      release is published. It refuses a draft without the framework, and a version with no
      release: the Swift package's manifest names that asset.
  push-post-release-branch --version X.Y.Z [--swift-package URL]
      Pushes the branch that starts the next development version (see next-snapshot), committed
      on origin/main's latest commit as github-actions[bot], and prints, and writes to the job
      summary, the link that opens its pull request. GitHub Actions may not open pull requests in
      this repository, so the owner opens it with that link, and CI then starts by itself. It does
      nothing but print the link when the branch exists, and nothing when main has moved on.
  next-snapshot VERSION [--swift-package URL]
      Rewrites the working tree for the version after VERSION: VERSION_NAME becomes the next
      minor's -SNAPSHOT, CHANGELOG.md gets an empty `## Unreleased` section again, the
      compatibility page an `Unreleased` row, and compat/overrides.txt is emptied of findings. The
      samples move to VERSION: the catalog's supabase-offline, and the iOS project's exact version
      of the Swift package with its Package.resolved pin, at the commit of the package's tag
      VERSION in its repository (default https://github.com/nesfeder/supabase-offline-swift). So
      does every install snippet (see check-snippets) the prepare pull request left.
  check-snippets
      Fails unless every install snippet names one version, and that version is the last release
      (the version the samples take, `supabase-offline` in gradle/libs.versions.toml) or, when
      VERSION_NAME is a release version X.Y.Z, that release, which the pull request preparing it
      may move them to. A snippet is a line of a tracked Markdown file (the README, the Swift
      package's README and documentation catalog, the site's pages; never CHANGELOG.md, whose
      history stays) in one of three forms: the Maven coordinates with a version, the catalog
      entry `supabase-offline = "X.Y.Z"` and `.upToNextMinor(from: "X.Y.Z")`. CI's docs job runs
      it, and it fails when it finds no snippet at all.
  self-test
      Runs every command against throwaway repositories, with a stand-in for `gh`.

Every command that changes something outside the working tree can be run again after a failure:
it does what is missing and leaves what is done. Standard library only; runs on the system python3
of macOS (3.9) and on CI runners.
"""

from __future__ import annotations

import argparse
import contextlib
import importlib.util
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Iterator

GRADLE_PROPERTIES = "gradle.properties"
CHANGELOG = "CHANGELOG.md"
COMPATIBILITY = "docs/project/compatibility.md"
CATALOG = "gradle/libs.versions.toml"
OVERRIDES = "compat/overrides.txt"
PUBLISHED_RECORD = "compat/published-artifacts.txt"
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
RELEASE_VERSION = re.compile(r"^\d+\.\d+\.\d+$")


def api_compat_headings() -> list[str]:
    """The `### ` headings of a changelog section, from api-compat.py, the one list: the pull-request
    check reads them, and a release starts the next section with the same."""
    spec = importlib.util.spec_from_file_location("api_compat", Path(__file__).with_name("api-compat.py"))
    module = importlib.util.module_from_spec(spec)
    # Its dataclasses look their module up by name while it loads
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return list(module.HEADINGS)


CHANGELOG_HEADINGS = api_compat_headings()
# The Swift package's framework, an asset of each GitHub Release, which the package's manifest
# names by URL (scripts/swift-release.py).
SWIFT_FRAMEWORK_ASSET = "SupabaseOfflineCore.xcframework.zip"
# The samples take the newest release, and the post-release pull request moves them to it: the
# catalog's version of the Android sample's library, and the iOS project's package requirement and
# its pin. The workspace's Package.resolved never pins the package: swift/ overrides it there.
SAMPLE_CATALOG_KEY = "supabase-offline"
IOS_SAMPLE_PROJECT = "samples/ios/OfflineTasks.xcodeproj/project.pbxproj"
IOS_SAMPLE_RESOLVED = "samples/ios/OfflineTasks.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
SWIFT_PACKAGE_URL = "https://github.com/nesfeder/supabase-offline-swift"
SWIFT_PACKAGE_IDENTITY = "supabase-offline-swift"
# The install snippets a reader copies, in the tracked Markdown files but CHANGELOG.md: the Maven
# coordinates with a version, the catalog entry docs/getting-started/setup.md shows, and the Swift
# package's requirement. In each form group 1 is what precedes the version and group 2 the version.
# They name the last release, as the samples do, or the one a prepare pull request moves them to;
# next-snapshot moves them with the samples.
SNIPPET_FORMS = (
    re.compile(r"(io\.github\.nesfeder\.supabase-offline:supabase-offline-kt(?:-testing)?:)(\d+\.\d+\.\d+)"),
    re.compile(r'(^[ \t]*supabase-offline[ \t]*=[ \t]*")(\d+\.\d+\.\d+)(?=")', re.MULTILINE),
    re.compile(r'(\.upToNextMinor\(from: ")(\d+\.\d+\.\d+)(?="\))'),
)
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


def markdown_files(root: Path) -> list[str]:
    """The tracked Markdown files but the changelog, where an install snippet can be."""
    listing = git(root, "ls-files", "-z", "--", "*.md")
    return sorted(path for path in listing.split("\0") if path and path != CHANGELOG)


def install_snippets(root: Path) -> list[tuple[str, int, str]]:
    """Every install snippet of the tracked Markdown files, as (path, line, version)."""
    found = []
    for path in markdown_files(root):
        text = (root / path).read_text(encoding="utf-8")
        for form in SNIPPET_FORMS:
            for match in form.finditer(text):
                found.append((path, text.count("\n", 0, match.start()) + 1, match.group(2)))
    return sorted(found)


def released_version(root: Path) -> str:
    """The last release: the version the samples take, SAMPLE_CATALOG_KEY in the catalog."""
    version = catalog_version((root / CATALOG).read_text(encoding="utf-8"), SAMPLE_CATALOG_KEY)
    if not RELEASE_VERSION.match(version):
        raise ReleaseError(f"`{SAMPLE_CATALOG_KEY}` in {CATALOG} is {version!r}, not a release version X.Y.Z")
    return version


def check_snippets(root: Path) -> tuple[str, str, list[tuple[str, int, str]]]:
    """Every install snippet names one version, the last release or the release being prepared;
    returns that version, which of the two it is, and the snippets."""
    last = released_version(root)
    prepared = version_name((root / GRADLE_PROPERTIES).read_text(encoding="utf-8"))
    preparing = bool(RELEASE_VERSION.match(prepared)) and prepared != last
    snippets = install_snippets(root)
    if not snippets:
        raise ReleaseError("no install snippet in the tracked Markdown files: a check that sees nothing proves nothing")
    expected = (f"{last}, the last release (`{SAMPLE_CATALOG_KEY}` in {CATALOG})"
                + (f", or {prepared}, the release being prepared" if preparing else ""))
    listing = "\n  ".join(f"{path}:{line}: {version}" for path, line, version in snippets)
    remedy = ("the pull request that prepares a release moves them all, or the post-release pull request"
              " does (RELEASING.md)")
    versions = sorted({version for _, _, version in snippets})
    if len(versions) > 1:
        raise ReleaseError(f"the install snippets name {len(versions)} versions, {', '.join(versions)}, and every"
                           f" one must name {expected}; {remedy}:\n  {listing}")
    if versions[0] != last and not (preparing and versions[0] == prepared):
        raise ReleaseError(f"the install snippets name {versions[0]}, not {expected}; {remedy}:\n  {listing}")
    return versions[0], "the last release" if versions[0] == last else "the release being prepared", snippets


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


def next_snapshot(root: Path, version: str, swift_commit: str) -> str:
    """Rewrites the working tree for the version after version, with the samples on version and
    the iOS sample's pin at swift_commit; returns the new VERSION_NAME."""
    properties = (root / GRADLE_PROPERTIES).read_text(encoding="utf-8")
    current = version_name(properties)
    if current != version:
        raise ReleaseError(f"VERSION_NAME on main is {current}, not {version}: the next version has"
                           " been started already, or the release is not the one on main")
    snippet_files(root)  # refuses a tree with no install snippet before anything is written
    pin_samples(root, version, swift_commit)
    pin_snippets(root, version)
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


def pin_samples(root: Path, version: str, swift_commit: str) -> None:
    """Moves both samples to the published version: the Android sample's catalog version, and the
    iOS project's exact version of the Swift package with its Package.resolved pin. Checks all three
    before it writes any."""
    catalog, catalogs = re.subn(rf'^({re.escape(SAMPLE_CATALOG_KEY)}\s*=\s*)"[^"]*"', rf'\g<1>"{version}"',
                                (root / CATALOG).read_text(encoding="utf-8"), flags=re.MULTILINE)
    if catalogs != 1:
        raise ReleaseError(f"{CATALOG} has no single `{SAMPLE_CATALOG_KEY}` version for the Android sample")
    requirement = re.compile(rf'(repositoryURL = "{re.escape(SWIFT_PACKAGE_URL)}";\s*requirement = \{{\s*'
                             r'kind = exactVersion;\s*version = )[^;\s]+;')
    project, requirements = requirement.subn(rf"\g<1>{version};",
                                             (root / IOS_SAMPLE_PROJECT).read_text(encoding="utf-8"))
    if requirements != 1:
        raise ReleaseError(f"{IOS_SAMPLE_PROJECT} does not require {SWIFT_PACKAGE_URL} at one exact version")
    resolved = json.loads((root / IOS_SAMPLE_RESOLVED).read_text(encoding="utf-8"))
    pins = [pin for pin in resolved.get("pins", []) if pin.get("identity") == SWIFT_PACKAGE_IDENTITY]
    if len(pins) != 1:
        raise ReleaseError(f"{IOS_SAMPLE_RESOLVED} has no single pin for {SWIFT_PACKAGE_IDENTITY}")
    # The originHash covers the packages' URLs alone, so a new version leaves it as it is.
    pins[0]["state"] = {"revision": swift_commit, "version": version}
    (root / CATALOG).write_text(catalog, encoding="utf-8")
    (root / IOS_SAMPLE_PROJECT).write_text(project, encoding="utf-8")
    # Xcode's own layout: a two-space indent, " : " after a key, and a final newline.
    (root / IOS_SAMPLE_RESOLVED).write_text(json.dumps(resolved, indent=2, separators=(",", " : ")) + "\n",
                                            encoding="utf-8")


def snippet_files(root: Path) -> list[str]:
    """The files that hold an install snippet; it refuses a tree with none."""
    paths = sorted({path for path, _, _ in install_snippets(root)})
    if not paths:
        raise ReleaseError("no install snippet in the tracked Markdown files to move to the release")
    return paths


def pin_snippets(root: Path, version: str) -> list[str]:
    """Rewrites every install snippet to version, in a block or in prose; returns the files."""
    paths = snippet_files(root)
    for path in paths:
        text = (root / path).read_text(encoding="utf-8")
        for form in SNIPPET_FORMS:
            text = form.sub(lambda match: match.group(1) + version, text)
        (root / path).write_text(text, encoding="utf-8")
    return paths


def swift_package_commit(root: Path, version: str, swift_package: str) -> str:
    """The commit of the Swift package's tag version in its repository, which the iOS sample pins."""
    commit = remote_tag(root, version, swift_package)
    if not commit:
        raise ReleaseError(f"the Swift package's repository {swift_package} has no tag {version}: the"
                           " samples can only move to a version that is published")
    return commit


# ─────────────────────────────────────────────────────────────────────────────
# After Maven Central: the tag, the GitHub Release and the post-release pull request
# ─────────────────────────────────────────────────────────────────────────────


def remote_tag(root: Path, version: str, remote: str = "origin") -> str:
    """The commit the remote's tag version points at, or an empty string when it has none."""
    listing = git(root, "ls-remote", "--tags", remote, f"refs/tags/{version}", f"refs/tags/{version}^{{}}")
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
    """Publishes the draft release for the existing tag, unless it is published already. The draft
    holds the notes (the changelog's section, then GitHub's generated list of the merged pull
    requests, grouped as `.github/release.yml` says) and the Swift package's framework."""
    existing = gh(root, "release", "view", version, "--json", "url,isDraft,assets", check=False)
    if existing.returncode != 0:
        raise ReleaseError(f"there is no GitHub Release {version}: the Release run's draft job creates"
                           " it, as a draft holding the Swift package's framework, before the approval"
                           " (RELEASING.md, \"When a release stops halfway\")")
    details = json.loads(existing.stdout or "{}")
    if not details.get("isDraft"):
        return f"The GitHub Release {version} already exists: {details.get('url', '')}"
    if SWIFT_FRAMEWORK_ASSET not in [asset.get("name") for asset in details.get("assets") or []]:
        raise ReleaseError(f"the draft release {version} has no {SWIFT_FRAMEWORK_ASSET}, which the Swift"
                           " package's manifest names: publishing it would leave the package unresolvable")
    gh(root, "release", "edit", version, "--draft=false", "--verify-tag")
    return f"Published the draft GitHub Release {version}: {details.get('url', '')}"


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
        - The samples take {version}: `supabase-offline` in `gradle/libs.versions.toml`, and the iOS
          project's exact version of the Swift package, with its pin in the project's `Package.resolved`.
        - Every install snippet names {version}: the README's, the Swift package's README and
          documentation catalog, and the site's pages (`scripts/release.py check-snippets`), unless
          the pull request that prepared the release moved them already.

        Before merging, check:

        - [ ] Both artifacts resolve at {version}:
              https://repo1.maven.org/maven2/{group.replace('.', '/')}/supabase-offline-kt/{version}/ and
              https://repo1.maven.org/maven2/{group.replace('.', '/')}/supabase-offline-kt-testing/{version}/
              (the Central Portal's search can take a few hours longer).
        - [ ] The GitHub Release {version} shows the changelog's section, is not a draft, and holds
              {SWIFT_FRAMEWORK_ASSET}.
        - [ ] The Swift package resolves at {version} in a new Xcode project, by the URL
              https://github.com/nesfeder/supabase-offline-swift (RELEASING.md, "After the release").
        - [ ] The samples build on {version} as a reader gets them: `./gradlew :samples:android:assembleDebug`
              with no `-PsupabaseOffline.local`, and `samples/ios/OfflineTasks.xcodeproj` (the project,
              not the workspace) resolves the package at {version}. CI builds both on this checkout.
        - [ ] The diff touches only the files above.
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


def push_post_release_branch(root: Path, version: str, swift_package: str = SWIFT_PACKAGE_URL) -> str:
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
        swift_commit = swift_package_commit(root, version, swift_package)
        git(root, "switch", "--quiet", "--create", branch, "FETCH_HEAD")
        next_snapshot(root, version, swift_commit)
        git(root, "add", GRADLE_PROPERTIES, CHANGELOG, COMPATIBILITY, OVERRIDES, CATALOG, IOS_SAMPLE_PROJECT,
            IOS_SAMPLE_RESOLVED, *snippet_files(root))
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

## [0.0.9] - 2026-09-01

### Added

- The first release; the preview was `io.github.nesfeder.supabase-offline:supabase-offline-kt:0.0.8`.
"""
FIXTURE_COMPATIBILITY = """\
# Compatibility

| supabase-offline-kt | supabase-kt |
| --- | --- |
| 0.1.0 | 3.8.0 |

Each release adds its row.
"""
# The iOS sample's two package references, and their pins in Xcode's layout, as the samples
# stood before the release.
FIXTURE_PROJECT = """\
/* Begin XCRemoteSwiftPackageReference section */
\t\tA1 /* XCRemoteSwiftPackageReference "supabase-offline-swift" */ = {
\t\t\tisa = XCRemoteSwiftPackageReference;
\t\t\trepositoryURL = "https://github.com/nesfeder/supabase-offline-swift";
\t\t\trequirement = {
\t\t\t\tkind = exactVersion;
\t\t\t\tversion = 0.0.9;
\t\t\t};
\t\t};
\t\tA2 /* XCRemoteSwiftPackageReference "supabase-swift" */ = {
\t\t\tisa = XCRemoteSwiftPackageReference;
\t\t\trepositoryURL = "https://github.com/supabase/supabase-swift";
\t\t\trequirement = {
\t\t\t\tkind = upToNextMajorVersion;
\t\t\t\tminimumVersion = 0.0.9;
\t\t\t};
\t\t};
/* End XCRemoteSwiftPackageReference section */
"""
FIXTURE_RESOLVED = """\
{
  "originHash" : "656ed216a89bcf61f1d8e0a43cff4446e3f835c264b0d8cc4d2911d100fb6143",
  "pins" : [
    {
      "identity" : "supabase-offline-swift",
      "kind" : "remoteSourceControl",
      "location" : "https://github.com/nesfeder/supabase-offline-swift",
      "state" : {
        "revision" : "1111111111111111111111111111111111111111",
        "version" : "0.0.9"
      }
    },
    {
      "identity" : "supabase-swift",
      "kind" : "remoteSourceControl",
      "location" : "https://github.com/supabase/supabase-swift",
      "state" : {
        "revision" : "2222222222222222222222222222222222222222",
        "version" : "0.0.9"
      }
    }
  ],
  "version" : 3
}
"""
# Two pages with install snippets in every form, in a block and in prose, beside versions that are
# not snippets and must stay.
SETUP_PAGE = "docs/getting-started/setup.md"
SWIFT_README = "swift/README.md"
FIXTURE_SETUP_PAGE = """\
# Project setup

A patch release (0.0.9 → 0.0.10) only fixes things.

=== "Version catalog"

    ```toml
    [versions]
    supabase-offline = "0.0.9"
    ```

=== "Kotlin DSL"

    ```kotlin
    implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt:0.0.9")
    testImplementation("io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:0.0.9")
    ```
"""
FIXTURE_SWIFT_README = """\
# The package

```swift
.package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.0.9"))
```

In a manifest, `.upToNextMinor(from: "0.0.9")`; the package needs iOS 16.
"""
FIXTURE_FILES = {
    GRADLE_PROPERTIES: "GROUP=io.example.group\nVERSION_NAME=0.1.0\n",
    SETUP_PAGE: FIXTURE_SETUP_PAGE,
    SWIFT_README: FIXTURE_SWIFT_README,
    CATALOG: '[versions]\nsupabase-bom = "3.8.0"\nsupabase-offline = "0.0.9"\n\n[libraries]\n'
             'supabase-offline = { module = "io.example.group:lib", version.ref = "supabase-offline" }\n',
    IOS_SAMPLE_PROJECT: FIXTURE_PROJECT,
    IOS_SAMPLE_RESOLVED: FIXTURE_RESOLVED,
    COMPATIBILITY: FIXTURE_COMPATIBILITY,
    OVERRIDES: "# Findings a reviewer judged compatible.\n\nkotlin-removed: lib :: fun a()  # made open\n",
    PUBLISHED_RECORD: "# The record.\nlib file .pom\nlib-android file .aar\n",
}
# A stand-in for the GitHub CLI's `release` commands: it records each call, answers from a JSON
# state file and keeps each asset's bytes in a folder beside it. `edit --verify-tag` asks origin.
FAKE_GH = """\
#!/usr/bin/env python3
import json, os, shutil, subprocess, sys
state_path = os.environ["FAKE_GH_STATE"]
assets_dir = state_path + ".assets"
state = json.load(open(state_path)) if os.path.exists(state_path) else {"releases": {}}
state.setdefault("calls", []).append(sys.argv[1:])
args = sys.argv[1:]
tag = args[2] if len(args) > 2 else ""
release = state["releases"].get(tag)
valued = {"--json", "--target", "--title", "--notes-file", "--pattern", "--dir"}
def flag(name):
    return args[args.index(name) + 1] if name in args else None
def positional():
    found, skip = [], False
    for arg in args[3:]:
        if skip:
            skip = False
        elif arg in valued:
            skip = True
        elif not arg.startswith("-"):
            found.append(arg)
    return found
def add_asset(path):
    os.makedirs(os.path.join(assets_dir, tag), exist_ok=True)
    shutil.copyfile(path, os.path.join(assets_dir, tag, os.path.basename(path)))
    release["assets"].append({"name": os.path.basename(path), "size": os.path.getsize(path)})
code = 0
if args[:2] != ["release", "create"] and release is None and args[:1] == ["release"]:
    sys.stderr.write("release not found\\n"); code = 1
elif args[:2] == ["release", "view"]:
    print(json.dumps(release))
elif args[:2] == ["release", "create"]:
    release = {"url": "https://example.invalid/releases/" + tag, "isDraft": "--draft" in args,
               "notes": open(flag("--notes-file")).read(), "targetCommitish": flag("--target") or "main",
               "assets": []}
    state["releases"][tag] = release
    for path in positional():
        add_asset(path)
    print(release["url"])
elif args[:2] == ["release", "upload"]:
    if any(asset["name"] == os.path.basename(path) for path in positional() for asset in release["assets"]):
        sys.stderr.write("asset exists\\n"); code = 1
    else:
        for path in positional():
            add_asset(path)
elif args[:2] == ["release", "download"]:
    pattern, folder = flag("--pattern"), flag("--dir") or "."
    names = [asset["name"] for asset in release["assets"] if asset["name"] == pattern]
    if not names:
        sys.stderr.write("no assets match\\n"); code = 1
    for name in names:
        shutil.copyfile(os.path.join(assets_dir, tag, name), os.path.join(folder, name))
elif args[:2] == ["release", "edit"]:
    listed = subprocess.run(["git", "ls-remote", "--tags", "origin", "refs/tags/" + tag],
                            capture_output=True, text=True).stdout
    if "--verify-tag" in args and not listed.strip():
        sys.stderr.write("tag not found on the remote\\n"); code = 1
    elif "--draft=false" in args:
        release["isDraft"] = False
else:
    sys.stderr.write("the stand-in answers only `release` commands\\n"); code = 2
json.dump(state, open(state_path, "w"))
sys.exit(code)
"""


def install_fake_gh(work: Path) -> Path:
    """Puts the stand-in for gh first on the PATH, with a fresh state file; returns that file."""
    tools = work / "bin"
    tools.mkdir(exist_ok=True)
    (tools / "gh").write_text(FAKE_GH)
    (tools / "gh").chmod((tools / "gh").stat().st_mode | stat.S_IEXEC)
    if not os.environ["PATH"].startswith(f"{tools}{os.pathsep}"):
        os.environ["PATH"] = f"{tools}{os.pathsep}{os.environ['PATH']}"
    state = work / "gh-state.json"
    os.environ["FAKE_GH_STATE"] = str(state)
    return state


class RecordedGit:
    """Stands in for a module's `git` in a self-test: records each call, then runs it."""

    def __init__(self, module_globals: dict):
        self.module_globals = module_globals
        self.real = module_globals["git"]
        self.calls: list[tuple[str, ...]] = []

    def __call__(self, root: Path, *arguments: str, check: bool = True) -> str:
        self.calls.append(arguments)
        return self.real(root, *arguments, check=check)

    def __enter__(self) -> "RecordedGit":
        self.module_globals["git"] = self
        return self

    def __exit__(self, *exception: object) -> None:
        self.module_globals["git"] = self.real

    def pushes(self) -> list[tuple[str, ...]]:
        """The recorded `git push` calls."""
        return [call for call in self.calls if "push" in call]

    def forced_pushes(self) -> list[tuple[str, ...]]:
        """The recorded pushes that could move a ref: a force flag, a mirror, or a `+` refspec."""
        return [call for call in self.pushes() if any(
            argument == "--mirror" or argument.startswith(("--force", "+"))
            or re.fullmatch(r"-[A-Za-z]*f[A-Za-z]*", argument) for argument in call)]


# Keeps git's automatic maintenance out of a throwaway repository: a background `gc` still writing
# into it when it is deleted fails the clean-up with "Directory not empty".
NO_AUTOMATIC_MAINTENANCE = (("gc.auto", "0"), ("maintenance.auto", "false"))


def init_throwaway_repository(*arguments: str) -> None:
    """`git init` with these arguments, the new repository (the last one) left without automatic
    maintenance."""
    subprocess.run(["git", "init", *arguments], check=True)
    for key, value in NO_AUTOMATIC_MAINTENANCE:
        subprocess.run(["git", "-C", arguments[-1], "config", key, value], check=True)


@contextlib.contextmanager
def throwaway_directory() -> Iterator[str]:
    """A temporary directory, removed afterward as far as it can be: what cannot be removed is left
    to the system's temporary-file sweep rather than failing the command that made it."""
    directory = tempfile.mkdtemp()
    try:
        yield directory
    finally:
        shutil.rmtree(directory, ignore_errors=True)


def make_repository(work: Path, name: str, files: dict[str, str]) -> Path:
    """A git repository with files committed on main, and a bare origin it has pushed to."""
    origin = work / f"{name}-origin.git"
    root = work / name
    init_throwaway_repository("-q", "--bare", "-b", "main", str(origin))
    init_throwaway_repository("-q", "-b", "main", str(root))
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
    with throwaway_directory() as directory:
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

        snippets_at = [(SETUP_PAGE, 9, "0.0.9"), (SETUP_PAGE, 15, "0.0.9"), (SETUP_PAGE, 16, "0.0.9"),
                       (SWIFT_README, 4, "0.0.9"), (SWIFT_README, 7, "0.0.9")]
        expect(check_snippets(root) == ("0.0.9", "the last release", snippets_at),
               "check-snippets finds every form, in a block or in prose, at the samples' version, and leaves the"
               " changelog's history and the pages' other versions out")
        prepared_pages = {SETUP_PAGE: FIXTURE_SETUP_PAGE.replace("0.0.9\"", "0.1.0\""),
                          SWIFT_README: FIXTURE_SWIFT_README.replace('from: "0.0.9"', 'from: "0.1.0"')}
        prepared = make_repository(work, "prepared-snippets", dict(base, **prepared_pages))
        expect(check_snippets(prepared)[:2] == ("0.1.0", "the release being prepared"),
               "check-snippets accepts the release being prepared, VERSION_NAME, when every snippet names it")
        mixed = make_repository(work, "mixed-snippets", dict(base, **{SWIFT_README: prepared_pages[SWIFT_README]}))
        expect(refused(lambda: check_snippets(mixed), "2 versions, 0.0.9, 0.1.0")
               and refused(lambda: check_snippets(mixed), f"{SWIFT_README}:4: 0.1.0"),
               "check-snippets refuses snippets that name two versions, listing each by file and line")
        unprepared = make_repository(work, "unprepared-snippets", dict(base, **dict(prepared_pages, **{
            GRADLE_PROPERTIES: "GROUP=io.example.group\nVERSION_NAME=0.2.0-SNAPSHOT\n"})))
        expect(refused(lambda: check_snippets(unprepared), "name 0.1.0, not 0.0.9, the last release"),
               "check-snippets refuses a version that is neither the last release nor one being prepared")
        unreleased = make_repository(work, "unreleased-catalog", dict(base, **{
            CATALOG: FIXTURE_FILES[CATALOG].replace('supabase-offline = "0.0.9"', 'supabase-offline = "0.1.0-SNAPSHOT"')}))
        expect(refused(lambda: check_snippets(unreleased), "not a release version"),
               "check-snippets refuses a catalog whose samples' version is not a release")
        unsnippeted = make_repository(work, "unsnippeted", {path: content for path, content in base.items()
                                                            if path not in (SETUP_PAGE, SWIFT_README)})
        expect(refused(lambda: check_snippets(unsnippeted), "no install snippet"),
               "check-snippets refuses a tree with no snippet, the changelog's history not counting")

        notes = release_notes(FIXTURE_CHANGELOG, "0.1.0")
        expect(notes == "### Added\n\n- Offline reads.\n- Queued writes.\n\n### Fixed\n\n- A fix.\n",
               "notes keep the section's entries and drop its heading and empty subheadings")

        expect("Removed" in CHANGELOG_HEADINGS and CHANGELOG_HEADINGS[0] == "Breaking",
               "the changelog headings are api-compat.py's, Removed included")
        expect(next_version("0.1.0") == "0.2.0-SNAPSHOT" and next_version("1.4.2") == "1.5.0-SNAPSHOT",
               "the next version is the next minor's snapshot")
        bumped = make_repository(work, "bumped", dict(base, **{CHANGELOG: FIXTURE_CHANGELOG.split("## Unreleased")[0] + "## [0.1.0]" + FIXTURE_CHANGELOG.split("## [0.1.0]")[1]}))
        swift_commit = "c" * 40
        following = next_snapshot(bumped, "0.1.0", swift_commit)
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
        expect(refused(lambda: next_snapshot(bumped, "0.1.0", swift_commit), "has been started already"),
               "next-snapshot refuses to run twice")
        expect((bumped / SETUP_PAGE).read_text() == FIXTURE_SETUP_PAGE.replace("0.0.9\"", "0.1.0\"")
               and (bumped / SWIFT_README).read_text() == FIXTURE_SWIFT_README.replace('from: "0.0.9"', 'from: "0.1.0"')
               and "supabase-offline-kt:0.0.8" in changelog,
               "next-snapshot moves every install snippet to the release, in a block or in prose, and nothing else")
        expect(check_snippets(bumped)[:2] == ("0.1.0", "the last release"),
               "check-snippets passes after next-snapshot, the samples and the snippets agreeing")
        expect(refused(lambda: next_snapshot(unsnippeted, "0.1.0", swift_commit), "no install snippet")
               and not git(unsnippeted, "status", "--porcelain").strip(),
               "next-snapshot refuses a tree with no snippet and changes nothing")
        expect((bumped / CATALOG).read_text() == FIXTURE_FILES[CATALOG].replace('supabase-offline = "0.0.9"', 'supabase-offline = "0.1.0"'),
               "next-snapshot moves the Android sample's catalog version to the release, and nothing else")
        expect((bumped / IOS_SAMPLE_PROJECT).read_text() == FIXTURE_PROJECT.replace("version = 0.0.9;", "version = 0.1.0;"),
               "next-snapshot moves the iOS project's exact version of the Swift package, and no other package")
        expect((bumped / IOS_SAMPLE_RESOLVED).read_text() == FIXTURE_RESOLVED.replace(
                   '"1111111111111111111111111111111111111111",\n        "version" : "0.0.9"',
                   f'"{swift_commit}",\n        "version" : "0.1.0"'),
               "next-snapshot pins the Swift package's tag commit in Xcode's layout, its originHash and other pins kept")
        for name, path, before, after, phrase in [
                ("a catalog without the samples' version", CATALOG, 'supabase-offline = "0.0.9"', 'supabase-offline-kt = "0.0.9"', "no single `supabase-offline`"),
                ("a project that takes the package at a range", IOS_SAMPLE_PROJECT, "kind = exactVersion;", "kind = upToNextMajorVersion;", "at one exact version"),
                ("a Package.resolved without the package's pin", IOS_SAMPLE_RESOLVED, '"identity" : "supabase-offline-swift"', '"identity" : "elsewhere"', "no single pin")]:
            unpinnable = make_repository(work, f"unpinnable-{path.count('/')}-{len(before)}", dict(base, **{path: FIXTURE_FILES[path].replace(before, after)}))
            expect(refused(lambda: next_snapshot(unpinnable, "0.1.0", swift_commit), phrase)
                   and not git(unpinnable, "status", "--porcelain").strip(),
                   f"next-snapshot refuses {name} and changes nothing")

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

        # The steps after Maven Central, against a bare origin and a stand-in for gh; every git call
        # is recorded, so a push that could move a ref fails the self-test.
        recorded = RecordedGit(globals()).__enter__()
        gh_state = install_fake_gh(work)
        finish = make_repository(work, "finish", base)
        sha = git(finish, "rev-parse", "HEAD").strip()
        commit_change(finish, "README.md", "a later change on main\n")
        git(finish, "push", "-q", "origin", "main")
        git(finish, "checkout", "-q", "--detach", sha)
        expect(push_tag(finish, "0.1.0", sha).startswith("Pushed"), "push-tag pushes the tag")
        expect("already on origin" in push_tag(finish, "0.1.0", sha), "push-tag again does nothing")
        expect(refused(lambda: push_tag(finish, "0.1.0", git(finish, "rev-parse", "origin/main").strip()), "never move"),
               "push-tag refuses to move a tag")
        expect(refused(lambda: publish_github_release(finish, "0.1.0"), "there is no GitHub Release 0.1.0"),
               "publish-github-release refuses a version with no draft")
        framework = work / SWIFT_FRAMEWORK_ASSET
        framework.write_bytes(b"framework")
        notes_file = work / "notes.md"
        notes_file.write_text(release_notes(FIXTURE_CHANGELOG, "0.1.0"))
        gh(finish, "release", "create", "0.1.0", "--draft", "--target", sha, "--notes-file", str(notes_file))
        expect(refused(lambda: publish_github_release(finish, "0.1.0"), f"has no {SWIFT_FRAMEWORK_ASSET}"),
               "publish-github-release refuses a draft without the framework")
        gh(finish, "release", "upload", "0.1.0", str(framework))
        expect(publish_github_release(finish, "0.1.0").startswith("Published the draft"),
               "publish-github-release publishes the draft")
        state = json.loads(gh_state.read_text())
        expect(state["calls"][-1] == ["release", "edit", "0.1.0", "--draft=false", "--verify-tag"]
               and not state["releases"]["0.1.0"]["isDraft"],
               "the draft is published for the existing tag, notes and asset unchanged")
        expect("already exists" in publish_github_release(finish, "0.1.0"), "publish-github-release again does nothing")
        summary = work / "summary.md"
        os.environ["GITHUB_STEP_SUMMARY"] = str(summary)
        os.environ["GITHUB_SERVER_URL"], os.environ["GITHUB_REPOSITORY"] = "https://github.com", "owner/repo"
        swift_package = make_repository(work, "swift-package", {"Package.swift": "// The package.\n"})
        expect(refused(lambda: push_post_release_branch(finish, "0.1.0", str(swift_package)), "has no tag 0.1.0")
               and not git(finish, "ls-remote", "--heads", "origin", "refs/heads/release/0.1.0-next-snapshot").strip(),
               "push-post-release-branch refuses a version the Swift package has no tag for, and pushes nothing")
        git(swift_package, "tag", "0.1.0")
        tagged_commit = git(swift_package, "rev-parse", "HEAD").strip()
        message = push_post_release_branch(finish, "0.1.0", str(swift_package))
        expect(message.startswith("Pushed the branch release/0.1.0-next-snapshot"),
               "push-post-release-branch pushes the branch")
        branch_head = git(finish, "rev-parse", "origin/main").strip()
        pushed = git(finish, "ls-remote", "--heads", "origin", "refs/heads/release/0.1.0-next-snapshot").split()
        log = git(finish, "log", "-1", "--format=%an <%ae>|%s|%P", pushed[0] if pushed else "HEAD")
        expect(bool(pushed) and log.startswith(f"{BOT_NAME} <{BOT_EMAIL}>") and log.strip().endswith(branch_head),
               "the branch's commit is the bot's, on top of main's latest commit")
        changed = set(git(finish, "diff", "--name-only", branch_head, pushed[0] if pushed else "HEAD").split())
        expect({CATALOG, IOS_SAMPLE_PROJECT, IOS_SAMPLE_RESOLVED, SETUP_PAGE, SWIFT_README} <= changed
               and changed <= {GRADLE_PROPERTIES, CHANGELOG, COMPATIBILITY, OVERRIDES, CATALOG, IOS_SAMPLE_PROJECT,
                               IOS_SAMPLE_RESOLVED, SETUP_PAGE, SWIFT_README}
               and f'"{tagged_commit}"' in committed_file(finish, pushed[0] if pushed else "HEAD", IOS_SAMPLE_RESOLVED)
               and 'from: "0.1.0"' in committed_file(finish, pushed[0] if pushed else "HEAD", SWIFT_README),
               "the branch moves the samples and the install snippets too, the iOS pin to the Swift package's tag commit")
        link = message.split("Open its pull request: ", 1)[-1]
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)
        expect(link.startswith("https://github.com/owner/repo/compare/main...release/0.1.0-next-snapshot?")
               and query.get("expand") == ["1"]
               and query.get("title") == ["chore(release): start 0.2.0-SNAPSHOT after 0.1.0"]
               and "Before merging, check" in query.get("body", [""])[0]
               and "0.2.0-SNAPSHOT" in query.get("body", [""])[0],
               "the link opens the pull request with its title and a description that says what to check")
        expect(link in summary.read_text(), "the link is in the job summary")
        again = push_post_release_branch(finish, "0.1.0", str(swift_package))
        expect(again.startswith("The branch release/0.1.0-next-snapshot exists already") and link in again,
               "push-post-release-branch again pushes nothing and gives the link again")
        git(finish, "push", "-q", "origin", "--delete", "release/0.1.0-next-snapshot")
        git(finish, "push", "-q", "origin", "refs/heads/release/0.1.0-next-snapshot:refs/heads/main")
        expect("has been started already" in push_post_release_branch(finish, "0.1.0", str(swift_package)),
               "push-post-release-branch pushes nothing once main has moved on to the next version")
        state = json.loads(gh_state.read_text())
        expect(all(call[0] == "release" for call in state["calls"]),
               "nothing asks GitHub to open a pull request")
        recorded.__exit__()
        expect(len(recorded.pushes()) >= 3 and not recorded.forced_pushes(),
               f"push-tag and push-post-release-branch never force: {recorded.forced_pushes()}")
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
    post_release = commands.add_parser("push-post-release-branch")
    post_release.add_argument("--version", required=True)
    post_release.add_argument("--swift-package", default=SWIFT_PACKAGE_URL)
    released = commands.add_parser("release-version")
    released.add_argument("--github-output")
    following = commands.add_parser("next-snapshot")
    following.add_argument("version")
    following.add_argument("--swift-package", default=SWIFT_PACKAGE_URL)
    commands.add_parser("check-snippets")
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
            print(push_post_release_branch(root, arguments.version, arguments.swift_package))
        elif arguments.command == "release-version":
            version = release_version(root)
            write_outputs(arguments.github_output, {"version": version})
            print(f"VERSION_NAME is {version}.")
        elif arguments.command == "next-snapshot":
            swift_commit = swift_package_commit(root, arguments.version, arguments.swift_package)
            print(f"VERSION_NAME is now {next_snapshot(root, arguments.version, swift_commit)}.")
        elif arguments.command == "check-snippets":
            version, which, snippets = check_snippets(root)
            files = len({path for path, _, _ in snippets})
            print(f"Every install snippet names {version}, {which}: {len(snippets)} in {files} files.")
    except ReleaseError as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
