#!/usr/bin/env python3
"""Classifies a change to the library's public contract and checks it against the version.

What it reads, all from git, so nothing is built:

  * the committed Kotlin ABI records (``.api`` for Android, ``.klib.api`` for iOS) under each
    published module's ``api/``,
  * the committed Swift interfaces under ``swift/api/`` (once the Swift package exists),
  * the Room schema directory, the files that pin stored formats, and the version catalog,
  * ``CHANGELOG.md``.

Everything it knows about this library is in ``compat/policy.json``.

Commands:

  classify      --base REF [--head REF]   print the classification of base..head
  check-pr      --base REF [--head REF]   classify, then require a matching CHANGELOG entry
  check-release [--from TAG] [--version X.Y.Z] [--head REF]
                                          classify last-release..head and require a big enough bump
  self-test                               run the fixtures in temporary git repositories

A classification is one of: none < patch < additive < breaking. Separately, a change can be
flagged ``format`` (stored data), ``dependency`` (upstream pin or toolchain floor), or ``error``
(something that must never happen, such as editing an exported Room schema).

The tool is deliberately conservative: when it cannot tell, it says breaking. A person can
downgrade one finding only by listing it, with a reason, in ``compat/overrides.txt``, which is
reviewed like any other file and emptied after each release.

Runs on the system python3 of macOS (3.9) and on CI runners; standard library only.
"""

from __future__ import annotations

import argparse
import contextlib
import fnmatch
import io
import json
import os
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from typing import Dict, Iterable, Iterator, List, Optional, Sequence, Set, Tuple

LEVELS = ["none", "patch", "additive", "breaking"]
FLAGS = ["format", "dependency", "error"]


# ─────────────────────────────────────────────────────────────────────────────
# Findings
# ─────────────────────────────────────────────────────────────────────────────


@dataclass
class Finding:
    """One thing that changed, with how much it matters to a consumer."""

    level: str  # one of LEVELS
    category: str  # machine name, e.g. "kotlin-removed"
    detail: str  # the line or file concerned
    flag: Optional[str] = None  # one of FLAGS, or None
    overridden_by: Optional[str] = None

    def key(self) -> str:
        """The exact text an override line must repeat."""
        return f"{self.category}: {self.detail}"


@dataclass
class Report:
    """Every finding between two commits."""

    base: str
    head: str
    findings: List[Finding] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)

    def add(self, finding: Finding) -> None:
        self.findings.append(finding)

    def effective(self) -> List[Finding]:
        """The findings no override downgraded."""
        return [finding for finding in self.findings if finding.overridden_by is None]

    def level(self) -> str:
        """The highest level of any effective finding; an overridden one counts as additive."""
        best = 0
        for finding in self.findings:
            level = "additive" if finding.overridden_by is not None else finding.level
            best = max(best, LEVELS.index(level))
        return LEVELS[best]

    def flags(self) -> Set[str]:
        return {finding.flag for finding in self.effective() if finding.flag}


# ─────────────────────────────────────────────────────────────────────────────
# Git access
# ─────────────────────────────────────────────────────────────────────────────


class Git:
    """Reads committed files, so the working tree never matters."""

    def __init__(self, root: str):
        self.root = root

    def run(self, *args: str) -> str:
        completed = subprocess.run(
            ["git", "-C", self.root, *args],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        )
        if completed.returncode != 0:
            raise SystemExit(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
        return completed.stdout

    def require_commit(self, ref: str) -> None:
        """Fails with a readable message when ``ref`` is not in the local history."""
        completed = subprocess.run(
            ["git", "-C", self.root, "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        )
        if completed.returncode != 0:
            raise SystemExit(
                f"{ref} is not a commit in this clone. Fetch enough history to include it "
                "(in CI, a checkout with fetch-depth 2 for a pull request, 0 for a release)."
            )

    def files(self, ref: str) -> List[str]:
        return [line for line in self.run("ls-tree", "-r", "--name-only", ref).splitlines() if line]

    def show(self, ref: str, path: str) -> Optional[str]:
        """The file at ``ref``, or None when it does not exist there."""
        completed = subprocess.run(
            ["git", "-C", self.root, "show", f"{ref}:{path}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        )
        return completed.stdout if completed.returncode == 0 else None

    def changed(self, base: str, head: str, paths: Sequence[str]) -> List[Tuple[str, str]]:
        """Returns (status, path) for files that differ between base and head under paths."""
        if not paths:
            return []
        output = self.run("diff", "--name-status", "--no-renames", base, head, "--", *paths)
        result = []
        for line in output.splitlines():
            if not line.strip():
                continue
            status, path = line.split("\t", 1)
            result.append((status[0], path))
        return result

    def tags(self) -> List[str]:
        return [line for line in self.run("tag", "--list").splitlines() if line]


def match_any(path: str, patterns: Iterable[str]) -> bool:
    return any(fnmatch.fnmatch(path, pattern) for pattern in patterns)


# ─────────────────────────────────────────────────────────────────────────────
# Kotlin records: Android (.api, JVM signatures) and iOS (.klib.api)
# ─────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class DumpEntry:
    """One declaration line of a record, with the declarations that enclose it."""

    parents: Tuple[str, ...]  # declaration texts of the enclosing declarations
    text: str  # this declaration, without its signature comment
    targets: str  # "" for all targets, else the klib "// Targets: [...]" annotation
    fqn: str  # fully qualified name with dots, where one could be read


@dataclass
class Dump:
    """A parsed record."""

    entries: Dict[DumpEntry, int]
    header: Dict[str, str]  # klib only: "targets", "unique_name"
    sealed: Set[str]  # FQNs of sealed types (klib only; JVM records do not mark sealed)
    enums: Set[str]  # FQNs of enum classes


SIGNATURE_SPLIT = re.compile(r"^(?P<text>.*?)\s+//\s(?P<signature>[^ ]+\|.*)$")
KLIB_CLASS_HEAD = re.compile(
    r"^(?:(?:final|open|abstract|sealed)\s+)?"
    r"(?:(?:inner|value|data|fun|enum|annotation|companion|expect)\s+)*"
    r"(?:class|interface|object)\b"
)


def klib_fqn(raw: str) -> str:
    """com.example/Outer.Inner -> com.example.Outer.Inner"""
    return raw.replace("/", ".")


def jvm_fqn(raw: str) -> str:
    """com/example/Outer$Inner -> com.example.Outer.Inner"""
    return raw.replace("/", ".").replace("$", ".")


def split_supertypes(text: str) -> List[str]:
    """Returns the supertypes named after ' : ' in a class header, generics kept whole."""
    if " : " not in text:
        return []
    tail = text.split(" : ", 1)[1]
    tail = tail.rstrip("{").strip()
    result, depth, current = [], 0, ""
    for character in tail:
        if character == "<":
            depth += 1
        elif character == ">":
            depth -= 1
        if character == "," and depth == 0:
            result.append(current.strip())
            current = ""
        else:
            current += character
    if current.strip():
        result.append(current.strip())
    return [re.sub(r"<.*", "", supertype).strip() for supertype in result]


def parse_klib_dump(content: str) -> Dump:
    entries: Dict[DumpEntry, int] = {}
    header: Dict[str, str] = {}
    sealed: Set[str] = set()
    enums: Set[str] = set()
    stack: List[Tuple[int, str, str, str]] = []  # (indent, text, targets, fqn)
    pending_targets = ""
    in_header = True
    for raw_line in content.splitlines():
        if not raw_line.strip():
            continue
        stripped = raw_line.strip()
        if in_header and stripped.startswith("//"):
            if stripped.startswith("// Targets:"):
                header["targets"] = stripped[len("// Targets:"):].strip()
            elif stripped.startswith("// Library unique name:"):
                header["unique_name"] = stripped[len("// Library unique name:"):].strip()
            continue
        in_header = False
        if stripped.startswith("// Targets:"):
            pending_targets = stripped[len("// Targets:"):].strip()
            continue
        if stripped.startswith("//") or stripped == "}":
            continue
        indent = (len(raw_line) - len(raw_line.lstrip(" "))) // 4
        match = SIGNATURE_SPLIT.match(stripped)
        text = match.group("text") if match else stripped
        signature = match.group("signature") if match else ""
        fqn = klib_fqn(signature.split("|", 1)[0]) if signature else ""
        while stack and stack[-1][0] >= indent:
            stack.pop()
        targets = pending_targets or (stack[-1][2] if stack else "")
        pending_targets = ""
        parents = tuple(item[1] for item in stack)
        entry = DumpEntry(parents=parents, text=text.rstrip(" {"), targets=targets, fqn=fqn)
        entries[entry] = entries.get(entry, 0) + 1
        if KLIB_CLASS_HEAD.match(text):
            if text.startswith("sealed "):
                sealed.add(fqn)
            if re.match(r"^(?:\w+\s+)*enum class\b", text):
                enums.add(fqn)
        stack.append((indent, entry.text, targets, fqn))
    return Dump(entries=entries, header=header, sealed=sealed, enums=enums)


JVM_CLASS_HEAD = re.compile(
    r"^public (?:(?:final|abstract|static|synthetic)\s+)*(?:interface\s+|annotation\s+)*"
    r"class\s+(\S+)"
)


def parse_jvm_dump(content: str) -> Dump:
    entries: Dict[DumpEntry, int] = {}
    enums: Set[str] = set()
    current: Optional[Tuple[str, str]] = None  # (header text, fqn)
    for raw_line in content.splitlines():
        if not raw_line.strip():
            continue
        if raw_line.startswith("\t"):
            if current is None:
                continue
            entry = DumpEntry(parents=(current[0],), text=raw_line.strip(), targets="", fqn=current[1])
        elif raw_line.strip() == "}":
            current = None
            continue
        else:
            text = raw_line.strip().rstrip("{").strip()
            match = JVM_CLASS_HEAD.match(text)
            fqn = jvm_fqn(match.group(1)) if match else ""
            if " : java/lang/Enum" in text:
                enums.add(fqn)
            current = (text, fqn)
            entry = DumpEntry(parents=(), text=text, targets="", fqn=fqn)
        entries[entry] = entries.get(entry, 0) + 1
    return Dump(entries=entries, header={}, sealed=set(), enums=enums)


def parse_dump(path: str, content: str) -> Dump:
    return parse_klib_dump(content) if path.endswith(".klib.api") else parse_jvm_dump(content)


def count_changes(before: Dump, after: Dump) -> Tuple[List[DumpEntry], List[DumpEntry]]:
    """Entries that occur fewer times after (removed) and more times after (added)."""
    removed = [entry for entry, count in before.entries.items() if after.entries.get(entry, 0) < count]
    added = [entry for entry, count in after.entries.items() if before.entries.get(entry, 0) < count]
    return removed, added


def classify_dump(
    path: str,
    base: Optional[str],
    head: Optional[str],
    policy: dict,
    report: Report,
    sealed_everywhere: Set[str],
) -> None:
    input_only = set(policy.get("inputOnlyTypes", {}).get("kotlin", []))
    closed = set(policy.get("closedForImplementation", {}).get("kotlin", []))
    if base is None and head is None:
        return
    if base is None:
        report.add(Finding("additive", "kotlin-dump-added", path))
        return
    if head is None:
        report.add(Finding("breaking", "kotlin-dump-removed", path))
        return
    before, after = parse_dump(path, base), parse_dump(path, head)
    klib = path.endswith(".klib.api")

    if klib:
        old_targets = set(re.findall(r"[\w]+", before.header.get("targets", "")))
        new_targets = set(re.findall(r"[\w]+", after.header.get("targets", "")))
        for target in sorted(old_targets - new_targets):
            report.add(Finding("breaking", "klib-target-removed", f"{path}: {target}"))
        for target in sorted(new_targets - old_targets):
            report.add(Finding("additive", "klib-target-added", f"{path}: {target}"))
        if before.header.get("unique_name") != after.header.get("unique_name"):
            report.add(
                Finding(
                    "breaking",
                    "klib-unique-name-changed",
                    f"{path}: {before.header.get('unique_name')} -> {after.header.get('unique_name')}",
                )
            )

    removed, added = count_changes(before, after)
    removed_parents = {entry.text for entry in removed}
    for entry in removed:
        # A removed class reports once; its members are implied.
        if any(parent in removed_parents for parent in entry.parents):
            continue
        report.add(Finding("breaking", "kotlin-removed", f"{path}: {describe(entry)}"))

    added_parents = {entry.text for entry in added}
    for entry in added:
        parent_text = entry.parents[-1] if entry.parents else ""
        parent_fqn = parent_fqn_of(entry, after)
        if klib and entry.text.startswith("enum entry "):
            level = "additive" if parent_fqn in input_only else "breaking"
            name = entry.text[len("enum entry "):]
            report.add(Finding(level, "kotlin-enum-entry-added", f"{path}: {parent_fqn}.{name}"))
            continue
        if (
            not klib
            and parent_fqn in after.enums
            and entry.text.startswith("public static final field ")
            and parent_text
        ):
            words = entry.text.split()
            name = words[4] if len(words) > 4 else entry.text
            if name.startswith("$"):
                continue
            level = "additive" if parent_fqn in input_only else "breaking"
            report.add(Finding(level, "kotlin-enum-entry-added", f"{path}: {parent_fqn}.{name}"))
            continue
        if klib and KLIB_CLASS_HEAD.match(entry.text):
            sealed_parents = [
                klib_fqn(supertype)
                for supertype in split_supertypes(entry.text)
                if klib_fqn(supertype) in sealed_everywhere
            ]
            if sealed_parents:
                sealed_type = sealed_parents[0]
                level = "additive" if sealed_type in input_only else "breaking"
                report.add(
                    Finding(
                        level,
                        "kotlin-sealed-subtype-added",
                        f"{path}: {entry.fqn or entry.text} : {sealed_type}",
                    )
                )
                continue
        if any(parent in added_parents for parent in entry.parents):
            continue  # members of a new class come with it
        if (
            is_new_abstract_member(entry, klib)
            and parent_is_implementable(parent_text, klib)
            and parent_fqn not in closed
            and parent_fqn not in sealed_everywhere
        ):
            report.add(
                Finding("breaking", "kotlin-abstract-member-added", f"{path}: {parent_fqn}: {entry.text}")
            )
            continue
        report.add(Finding("additive", "kotlin-added", f"{path}: {describe(entry)}"))


def describe(entry: DumpEntry) -> str:
    where = f" [{entry.targets}]" if entry.targets else ""
    owner = entry.parents[-1] if entry.parents else ""
    owner = re.sub(r"\s+:.*$", "", owner)
    return f"{owner} :: {entry.text}{where}" if owner else f"{entry.text}{where}"


def parent_fqn_of(entry: DumpEntry, dump: Dump) -> str:
    if not entry.parents:
        return ""
    for candidate in dump.entries:
        if candidate.text == entry.parents[-1] and candidate.parents == entry.parents[:-1]:
            return candidate.fqn
    return ""


def is_new_abstract_member(entry: DumpEntry, klib: bool) -> bool:
    if klib:
        return bool(re.match(r"^abstract (?:suspend )?(?:fun|val|var)\b", entry.text))
    return entry.text.startswith("public abstract fun ")


def parent_is_implementable(parent_text: str, klib: bool) -> bool:
    """Whether a consumer may implement or extend the enclosing type, so a new abstract member
    breaks them. Sealed types are excluded by the caller from the klib records' sealed set."""
    if not parent_text:
        return False
    if klib:
        if parent_text.startswith("sealed "):
            return False
        return bool(re.match(r"^(?:abstract|open)\s+(?:fun\s+)?(?:interface|class)\b", parent_text))
    return "abstract" in parent_text.split(" class ")[0] or " interface class " in parent_text


# ─────────────────────────────────────────────────────────────────────────────
# Swift interfaces
# ─────────────────────────────────────────────────────────────────────────────


def normalise_swift(content: str) -> List[str]:
    lines = []
    for line in content.splitlines():
        if line.startswith("// swift-") or not line.strip():
            continue
        lines.append(line.rstrip())
    return lines


def classify_swift(
    path: str, base: Optional[str], head: Optional[str], policy: dict, report: Report
) -> None:
    input_only = set(policy.get("inputOnlyTypes", {}).get("swift", []))
    if base is None and head is None:
        return
    if base is None:
        report.add(Finding("additive", "swift-interface-added", path))
        return
    if head is None:
        report.add(Finding("breaking", "swift-interface-removed", path))
        return
    before, after = normalise_swift(base), normalise_swift(head)
    before_set, after_set = set(before), set(after)
    for line in before:
        if line not in after_set and line.strip() not in ("}", "{"):
            report.add(Finding("breaking", "swift-removed", f"{path}: {line.strip()}"))
    enclosing: List[str] = []
    for line in after:
        stripped = line.strip()
        if stripped.endswith("{"):
            enclosing.append(stripped)
        elif stripped == "}" and enclosing:
            enclosing.pop()
            continue
        if line in before_set or stripped in ("}", "{"):
            continue
        if enclosing and not stripped.endswith("{"):
            owner = enclosing[-1]
        else:
            owner = enclosing[-2] if len(enclosing) > 1 else ""
        enum_match = re.match(r"^(?:@\w+\s+)*public (?:indirect )?enum (\w+)", owner)
        if stripped.startswith("case ") and enum_match:
            level = "additive" if enum_match.group(1) in input_only else "breaking"
            report.add(
                Finding(level, "swift-enum-case-added", f"{path}: {enum_match.group(1)}.{stripped[5:]}")
            )
            continue
        protocol_match = re.match(r"^public protocol (\w+)", owner)
        if protocol_match and re.match(r"^(func|var|associatedtype|init|static|subscript)\b", stripped):
            report.add(
                Finding(
                    "breaking",
                    "swift-protocol-requirement-added",
                    f"{path}: {protocol_match.group(1)}: {stripped}",
                )
            )
            continue
        report.add(Finding("additive", "swift-added", f"{path}: {stripped}"))


def read_digester_report(path: Optional[str], report: Report) -> None:
    """Adds the swift-api-digester's 'API breakage' lines, when a report file is given."""
    if not path:
        return
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            match = re.search(r"API breakage: (.*?)(?: \[#api-digester-breaking-change\])?$", line.strip())
            if match:
                report.add(Finding("breaking", "swift-digester", match.group(1)))


# ─────────────────────────────────────────────────────────────────────────────
# Stored formats, upstream pin, toolchain floors
# ─────────────────────────────────────────────────────────────────────────────


def classify_schema(git: Git, base: str, head: str, policy: dict, report: Report) -> None:
    for status, path in git.changed(base, head, policy.get("schemaDirectories", [])):
        if status == "A":
            report.add(Finding("additive", "room-schema-version-added", path, flag="format"))
        else:
            report.add(Finding("breaking", "room-schema-edited", f"{path} ({status})", flag="error"))


def classify_contract(git: Git, base: str, head: str, policy: dict, report: Report) -> None:
    paths = policy.get("persistedContract", {}).get("paths", [])
    for status, path in git.changed(base, head, paths):
        report.add(Finding("additive", "persisted-contract-changed", f"{path} ({status})", flag="format"))
    # A change to the rules themselves is shown for review but needs no CHANGELOG entry: consumers
    # see its effect only through the findings it changes.
    rules = ["compat/policy.json", policy.get("abiExclusions", "compat/abi-exclusions.txt")]
    for status, path in git.changed(base, head, rules):
        report.add(
            Finding("patch", "compatibility-rules-changed", f"{path} ({status}): review what it now lets through")
        )


CATALOG_VERSION = re.compile(r'^\s*(?P<name>[\w.-]+)\s*=\s*"(?P<value>[^"]+)"\s*$')


def catalog_versions(content: Optional[str]) -> Dict[str, str]:
    versions: Dict[str, str] = {}
    if content is None:
        return versions
    section = ""
    for line in content.splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            section = stripped
            continue
        if section != "[versions]":
            continue
        match = CATALOG_VERSION.match(line)
        if match:
            versions[match.group("name")] = match.group("value")
    return versions


def version_tuple(value: str) -> Tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", value.split("-")[0])[:3])


def classify_catalog(git: Git, base: str, head: str, policy: dict, report: Report) -> None:
    path = policy.get("versionCatalog", "gradle/libs.versions.toml")
    before, after = catalog_versions(git.show(base, path)), catalog_versions(git.show(head, path))
    for name in policy.get("upstreamPins", {}).get("names", []):
        old, new = before.get(name), after.get(name)
        if old == new or old is None or new is None:
            continue
        if version_tuple(old)[:2] != version_tuple(new)[:2]:
            report.add(Finding("additive", "upstream-minor-changed", f"{name} {old} -> {new}", flag="dependency"))
        else:
            report.add(Finding("patch", "upstream-patch-changed", f"{name} {old} -> {new}"))
    floors = policy.get("toolchainFloors", {})
    kotlin = floors.get("kotlin")
    if kotlin and before.get(kotlin) and after.get(kotlin) and before[kotlin] != after[kotlin]:
        old, new = version_tuple(before[kotlin]), version_tuple(after[kotlin])
        same_release = old[:2] == new[:2] and len(old) > 2 and len(new) > 2 and old[2] // 10 == new[2] // 10
        if same_release:
            report.add(Finding("patch", "kotlin-bugfix-changed", f"{before[kotlin]} -> {after[kotlin]}"))
        else:
            report.add(
                Finding("additive", "kotlin-floor-changed", f"{before[kotlin]} -> {after[kotlin]}", flag="dependency")
            )
    for name in floors.get("increaseOnly", []):
        old, new = before.get(name), after.get(name)
        if old and new and version_tuple(new) > version_tuple(old):
            report.add(Finding("additive", "toolchain-floor-raised", f"{name} {old} -> {new}", flag="dependency"))


def policy_problems(git: Git, head: str, policy: dict) -> List[str]:
    """Paths the policy watches that do not exist, so a rename cannot silently stop a watch."""
    files = git.files(head)
    watched = list(policy.get("persistedContract", {}).get("paths", []))
    watched.append(policy.get("versionCatalog", "gradle/libs.versions.toml"))
    watched.append(policy.get("changelog", "CHANGELOG.md"))
    problems = [f"compat/policy.json names {path}, which does not exist" for path in watched if path not in files]
    for directory in policy.get("schemaDirectories", []):
        if not any(path.startswith(directory.rstrip("/") + "/") for path in files):
            problems.append(f"compat/policy.json names the schema directory {directory}, which holds no file")
    for pattern in policy.get("kotlinDumps", []):
        if not any(fnmatch.fnmatch(path, pattern) for path in files):
            problems.append(f"compat/policy.json names the records {pattern}, which match no file")
    return problems


# ─────────────────────────────────────────────────────────────────────────────
# Overrides
# ─────────────────────────────────────────────────────────────────────────────


def apply_overrides(git: Git, head: str, policy: dict, report: Report) -> List[str]:
    """Downgrades findings listed in compat/overrides.txt. Returns problems with the file."""
    problems = []
    content = git.show(head, policy.get("overrides", "compat/overrides.txt")) or ""
    wanted: Dict[str, str] = {}
    for number, line in enumerate(content.splitlines(), start=1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "  # " not in line:
            problems.append(f"compat/overrides.txt:{number}: an override needs a reason after '  # '")
            continue
        key, reason = line.split("  # ", 1)
        if not reason.strip():
            problems.append(f"compat/overrides.txt:{number}: empty reason")
            continue
        wanted[key.strip()] = reason.strip()
    used = set()
    for finding in report.findings:
        if finding.key() in wanted and finding.flag != "error":
            finding.overridden_by = wanted[finding.key()]
            used.add(finding.key())
    for key in sorted(set(wanted) - used):
        problems.append(f"compat/overrides.txt: '{key}' matches no finding; remove it")
    return problems


# ─────────────────────────────────────────────────────────────────────────────
# Classification
# ─────────────────────────────────────────────────────────────────────────────


def load_policy(git: Git, ref: str) -> dict:
    content = git.show(ref, "compat/policy.json")
    if content is None:
        raise SystemExit(f"compat/policy.json is missing at {ref}")
    return json.loads(content)


def classify(git: Git, base: str, head: str, swift_report: Optional[str] = None) -> Tuple[Report, List[str]]:
    git.require_commit(base)
    git.require_commit(head)
    policy = load_policy(git, head)
    report = Report(base=base, head=head)
    base_files, head_files = git.files(base), git.files(head)

    kotlin_patterns = policy.get("kotlinDumps", [])
    dump_paths = sorted(
        {path for path in base_files + head_files if match_any(path, kotlin_patterns) and path.endswith(".api")}
    )
    sealed_everywhere: Set[str] = set()
    for path in dump_paths:
        if path.endswith(".klib.api"):
            for ref in (base, head):
                content = git.show(ref, path)
                if content is not None:
                    sealed_everywhere |= parse_klib_dump(content).sealed
    for path in dump_paths:
        classify_dump(path, git.show(base, path), git.show(head, path), policy, report, sealed_everywhere)

    swift_patterns = policy.get("swiftInterfaces", [])
    for path in sorted({path for path in base_files + head_files if match_any(path, swift_patterns)}):
        classify_swift(path, git.show(base, path), git.show(head, path), policy, report)
    read_digester_report(swift_report, report)

    classify_schema(git, base, head, policy, report)
    classify_contract(git, base, head, policy, report)
    classify_catalog(git, base, head, policy, report)
    problems = apply_overrides(git, head, policy, report) + policy_problems(git, head, policy)
    return report, problems


def render(report: Report, problems: Sequence[str]) -> str:
    flags = f" + {', '.join(sorted(report.flags()))}" if report.flags() else ""
    lines = [f"## Compatibility: `{report.level()}`{flags}", ""]
    lines.append(f"Compared `{report.base}` with `{report.head}`.")
    lines.append("")
    if not report.findings:
        lines.append("No change to the public API, the stored formats, the upstream pin or the toolchain floors.")
    for level in reversed(LEVELS):
        group = [finding for finding in report.effective() if finding.level == level]
        if not group:
            continue
        lines.append(f"### {level}")
        for finding in group[:200]:
            flag = f" [{finding.flag}]" if finding.flag else ""
            lines.append(f"- `{finding.category}`{flag}: {finding.detail}")
        if len(group) > 200:
            lines.append(f"- … and {len(group) - 200} more")
        lines.append("")
    overridden = [finding for finding in report.findings if finding.overridden_by]
    if overridden:
        lines.append("### Overridden in compat/overrides.txt (counted as additive)")
        for finding in overridden:
            lines.append(f"- `{finding.key()}` — {finding.overridden_by}")
        lines.append("")
    if problems:
        lines.append("### Problems")
        lines.extend(f"- {problem}" for problem in problems)
    return "\n".join(lines).rstrip() + "\n"


def emit(text: str) -> None:
    """Prints, and appends to the job summary when running in GitHub Actions."""
    sys.stdout.write(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text + "\n")


# ─────────────────────────────────────────────────────────────────────────────
# CHANGELOG
# ─────────────────────────────────────────────────────────────────────────────

HEADINGS = ["Breaking", "Added", "Changed", "Deprecated", "Fixed"]


def changelog_section(content: Optional[str], title: str) -> Dict[str, Set[str]]:
    """Bullets under each ### heading of the '## <title>' section."""
    result: Dict[str, Set[str]] = {heading: set() for heading in HEADINGS}
    if content is None:
        return result
    inside, heading = False, None
    for line in content.splitlines():
        if line.startswith("## "):
            inside = line[3:].strip().lower() == title.lower()
            heading = None
            continue
        if not inside:
            continue
        if line.startswith("### "):
            name = line[4:].strip()
            heading = name if name in result else None
            continue
        if heading and re.match(r"^\s*[-*] \S", line):
            result[heading].add(line.strip())
    return result


def required_headings(report: Report) -> Optional[Set[str]]:
    """Headings a change of this kind must add a bullet under; None means nothing is required."""
    level, flags = report.level(), report.flags()
    if level == "breaking" or "error" in flags:
        return {"Breaking"}
    if "format" in flags or "dependency" in flags:
        return {"Breaking", "Changed"}
    if level == "additive":
        return {"Breaking", "Added", "Changed"}
    return None


# ─────────────────────────────────────────────────────────────────────────────
# Commands
# ─────────────────────────────────────────────────────────────────────────────


def command_classify(git: Git, arguments: argparse.Namespace) -> int:
    report, problems = classify(git, arguments.base, arguments.head, arguments.swift_report)
    emit(render(report, problems))
    return 1 if "error" in report.flags() or problems else 0


def command_check_pr(git: Git, arguments: argparse.Namespace) -> int:
    report, problems = classify(git, arguments.base, arguments.head, arguments.swift_report)
    policy = load_policy(git, arguments.head)
    path = policy.get("changelog", "CHANGELOG.md")
    before = changelog_section(git.show(arguments.base, path), "Unreleased")
    after = changelog_section(git.show(arguments.head, path), "Unreleased")
    added = {heading: after[heading] - before[heading] for heading in HEADINGS}
    required = required_headings(report)
    failures = list(problems)
    if "error" in report.flags():
        failures.append("An exported Room schema was edited or deleted. Add a new version instead; never edit a shipped one.")
    if required and not any(added[heading] for heading in required):
        flags = f" with {', '.join(sorted(report.flags()))}" if report.flags() else ""
        failures.append(
            f"This change is `{report.level()}`{flags}. Add a bullet under `## Unreleased` in "
            f"{path}, under one of: {', '.join('### ' + heading for heading in sorted(required))}."
        )
    if added["Breaking"] and report.level() != "breaking":
        report.notes.append("A person marked this change Breaking in CHANGELOG.md; the release check will treat it as breaking.")
    text = render(report, [])
    if report.notes:
        text += "\n" + "\n".join(f"> {note}" for note in report.notes) + "\n"
    if failures:
        text += "\n### Failed\n" + "\n".join(f"- {failure}" for failure in failures) + "\n"
    else:
        text += "\nThe changelog carries what this change needs.\n" if required else ""
    emit(text)
    return 1 if failures else 0


SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


def latest_release_tag(git: Git) -> Optional[str]:
    releases = [tag for tag in git.tags() if SEMVER.match(tag)]
    return max(releases, key=version_tuple) if releases else None


def bump_kind(old: str, new: str) -> str:
    before, after = version_tuple(old), version_tuple(new)
    if after <= before:
        return "not-greater"
    if after[0] != before[0]:
        return "major"
    if after[1] != before[1]:
        return "minor"
    return "patch"


def required_bump(report: Report, changelog_breaking: bool, current: str) -> str:
    level, flags = report.level(), report.flags()
    breaking = level == "breaking" or changelog_breaking
    stable = version_tuple(current)[0] >= 1
    if stable:
        if breaking:
            return "major"
        if level == "additive" or flags & {"format", "dependency"}:
            return "minor"
        return "patch"
    # 0.y.z: a patch never changes the contract; every other change, breaking or not, is a minor.
    if breaking or level == "additive" or flags & {"format", "dependency"}:
        return "minor"
    return "patch"


def command_check_release(git: Git, arguments: argparse.Namespace) -> int:
    previous = arguments.from_tag or latest_release_tag(git)
    version = arguments.version
    if version is None:
        properties = git.show(arguments.head, "gradle.properties") or ""
        match = re.search(r"^VERSION_NAME=(\S+)$", properties, re.MULTILINE)
        version = match.group(1).removesuffix("-SNAPSHOT") if match else None
    if version is None or not SEMVER.match(version):
        emit(f"Cannot tell which version is being released (got {version!r}).\n")
        return 1
    if previous is None:
        emit(f"No earlier release tag: {version} is the first release, so there is nothing to compare with.\n")
        return 0
    report, problems = classify(git, previous, arguments.head, arguments.swift_report)
    policy = load_policy(git, arguments.head)
    changelog = git.show(arguments.head, policy.get("changelog", "CHANGELOG.md"))
    sections = [changelog_section(changelog, "Unreleased"), changelog_section(changelog, version)]
    changelog_breaking = any(section["Breaking"] for section in sections)
    needed = required_bump(report, changelog_breaking, previous)
    actual = bump_kind(previous, version)
    order = ["patch", "minor", "major"]
    text = render(report, problems)
    text += f"\nRelease {previous} → {version}: a {actual} bump. This change needs at least a {needed} bump.\n"
    failures = list(problems)
    if actual == "not-greater":
        failures.append(f"{version} is not greater than {previous}.")
    elif order.index(actual) < order.index(needed):
        failures.append(f"{version} is a {actual} bump; the changes since {previous} need a {needed} bump.")
    if "error" in report.flags():
        failures.append("An exported Room schema was edited or deleted since the last release.")
    if failures:
        text += "\n### Failed\n" + "\n".join(f"- {failure}" for failure in failures) + "\n"
    emit(text)
    return 1 if failures else 0


# ─────────────────────────────────────────────────────────────────────────────
# Self-test
# ─────────────────────────────────────────────────────────────────────────────

POLICY = {
    "kotlinDumps": ["lib/api/**"],
    "swiftInterfaces": ["swift/api/*.swiftinterface"],
    "inputOnlyTypes": {"kotlin": ["io.example.ReadStrategy", "io.example.Mode"], "swift": ["ReadStrategy"]},
    "closedForImplementation": {"kotlin": ["io.example.Closed"]},
    "schemaDirectories": ["lib/schemas"],
    "persistedContract": {"paths": ["lib/src/commonTest/PersistedNamesTest.kt"]},
    "versionCatalog": "gradle/libs.versions.toml",
    "upstreamPins": {"names": ["supabase-bom"]},
    "toolchainFloors": {"kotlin": "kotlin", "increaseOnly": ["android-minSdk"]},
    "changelog": "CHANGELOG.md",
}

KLIB_BASE = """// Klib ABI Dump
// Targets: [iosArm64, iosSimulatorArm64]
// Rendering settings:
// - Signature version: 2
// - Show manifest properties: true
// - Show declarations: true

// Library unique name: <io.example:lib>
final enum class io.example/Mode : kotlin/Enum<io.example/Mode> { // io.example/Mode|null[0]
    enum entry A // io.example/Mode.A|null[0]
}

final enum class io.example/Outcome : kotlin/Enum<io.example/Outcome> { // io.example/Outcome|null[0]
    enum entry APPLIED // io.example/Outcome.APPLIED|null[0]
}

abstract interface io.example/Client { // io.example/Client|null[0]
    abstract fun select(kotlin/String): kotlin/String // io.example/Client.select|select(kotlin.String){}[0]
}

abstract interface io.example/Closed { // io.example/Closed|null[0]
    abstract fun a(): kotlin/Int // io.example/Closed.a|a(){}[0]
}

sealed interface io.example/MutationOutcome { // io.example/MutationOutcome|null[0]
    final object Queued : io.example/MutationOutcome // io.example/MutationOutcome.Queued|null[0]
}

sealed interface io.example/ReadStrategy { // io.example/ReadStrategy|null[0]
    final object NetworkOnly : io.example/ReadStrategy // io.example/ReadStrategy.NetworkOnly|null[0]
}

final fun io.example/helper(kotlin/Int): kotlin/Int // io.example/helper|helper(kotlin.Int){}[0]
"""

JVM_BASE = """public abstract interface class io/example/Client {
\tpublic abstract fun select (Ljava/lang/String;)Ljava/lang/String;
}

public final class io/example/HelperKt {
\tpublic static final fun helper (I)I
}

public final class io/example/Mode : java/lang/Enum {
\tpublic static final field A Lio/example/Mode;
}

public abstract interface class io/example/MutationOutcome {
\tpublic fun resultOrNull ()Ljava/lang/String;
}
"""

SWIFT_BASE = """// swift-interface-format-version: 1.0
// swift-module-flags: -target arm64-apple-ios16.0-simulator -enable-library-evolution -module-name SupabaseOffline
import Foundation
final public class SupabaseOfflineClient : Swift.Sendable {
  final public func close()
}
public enum ReadStrategy : Swift.Sendable {
  case networkOnly
}
public enum OfflineSyncState : Swift.Sendable {
  case idle
}
"""

CATALOG = """[versions]
kotlin = "{kotlin}"
supabase-bom = "{bom}"
android-minSdk = "{min_sdk}"
"""

CHANGELOG_BASE = """# Changelog

## Unreleased

### Added

### Fixed

## 0.1.0

### Added

- First release.
"""


class Fixture:
    """A throwaway git repository holding a baseline of every file the classifier reads."""

    def __init__(self, directory: str) -> None:
        self.directory = directory
        self.git = Git(directory)
        self.git.run("init", "-q")
        for key, value in (
            ("user.email", "self-test@example.invalid"),
            ("user.name", "self-test"),
            ("commit.gpgsign", "false"),
            ("tag.gpgsign", "false"),
            ("core.hooksPath", os.devnull),
        ):
            self.git.run("config", key, value)

    def write(self, path: str, content: str) -> None:
        full = os.path.join(self.directory, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(content)

    def commit(self, message: str) -> str:
        self.git.run("add", "-A")
        self.git.run("commit", "-q", "--allow-empty", "-m", message)
        return self.git.run("rev-parse", "HEAD").strip()

    def baseline(self) -> str:
        self.write("compat/policy.json", json.dumps(POLICY))
        self.write("lib/api/lib.klib.api", KLIB_BASE)
        self.write("lib/api/android/lib.api", JVM_BASE)
        self.write("swift/api/SupabaseOffline.swiftinterface", SWIFT_BASE)
        self.write("lib/schemas/2.json", '{"version": 2}')
        self.write("lib/src/commonTest/PersistedNamesTest.kt", 'val NAME = "mutation_owners"\n')
        self.write("gradle/libs.versions.toml", CATALOG.format(kotlin="2.4.20", bom="3.8.0", min_sdk="23"))
        self.write("CHANGELOG.md", CHANGELOG_BASE)
        self.write("compat/overrides.txt", "# empty\n")
        self.write("gradle.properties", "VERSION_NAME=0.1.1-SNAPSHOT\n")
        base = self.commit("baseline")
        self.git.run("tag", "0.1.0")
        return base


@contextlib.contextmanager
def fixture() -> Iterator[Tuple[Fixture, str]]:
    """A fresh Fixture and its baseline commit, deleted afterwards."""
    with tempfile.TemporaryDirectory(prefix="api-compat-self-test.") as directory:
        repository = Fixture(directory)
        yield repository, repository.baseline()


def expect(condition: bool, message: str, failures: List[str]) -> None:
    print(("ok   " if condition else "FAIL ") + message)
    if not condition:
        failures.append(message)


def quietly(command, *arguments) -> int:
    """Runs a command without printing its report, which the self-test does not read."""
    with contextlib.redirect_stdout(io.StringIO()):
        return command(*arguments)


def self_test() -> int:
    failures: List[str] = []
    # The fixtures' reports must never reach a real job summary.
    os.environ.pop("GITHUB_STEP_SUMMARY", None)

    def scenario(name: str, change, expected_level: str, expected_categories: Sequence[str],
                 expected_flags: Sequence[str] = (), unexpected_categories: Sequence[str] = ()) -> None:
        with fixture() as (repository, base):
            change(repository)
            head = repository.commit(name)
            report, problems = classify(repository.git, base, head)
        categories = {finding.category for finding in report.effective()}
        expect(report.level() == expected_level, f"{name}: level {report.level()} == {expected_level}", failures)
        for category in expected_categories:
            expect(category in categories, f"{name}: reports {category}", failures)
        for category in unexpected_categories:
            expect(category not in categories, f"{name}: does not report {category}", failures)
        for flag in expected_flags:
            expect(flag in report.flags(), f"{name}: flagged {flag}", failures)
        expect(not problems, f"{name}: no problems ({problems})", failures)

    def with_klib(old: str, new: str):
        return lambda repository: repository.write("lib/api/lib.klib.api", KLIB_BASE.replace(old, new))

    def with_jvm(old: str, new: str):
        return lambda repository: repository.write("lib/api/android/lib.api", JVM_BASE.replace(old, new))

    def with_swift(old: str, new: str):
        return lambda repository: repository.write(
            "swift/api/SupabaseOffline.swiftinterface", SWIFT_BASE.replace(old, new)
        )

    def with_catalog(kotlin: str = "2.4.20", bom: str = "3.8.0", min_sdk: str = "23"):
        return lambda repository: repository.write(
            "gradle/libs.versions.toml", CATALOG.format(kotlin=kotlin, bom=bom, min_sdk=min_sdk)
        )

    scenario("nothing", lambda repository: None, "none", [])
    scenario(
        "a new top-level function",
        lambda repository: repository.write(
            "lib/api/lib.klib.api",
            KLIB_BASE + "final fun io.example/more(): kotlin/Int // io.example/more|more(){}[0]\n",
        ),
        "additive",
        ["kotlin-added"],
    )
    scenario(
        "a removed function",
        with_klib("final fun io.example/helper", "final fun io.example/renamed"),
        "breaking",
        ["kotlin-removed"],
    )
    scenario(
        "a removed function (JVM record)",
        with_jvm("\tpublic static final fun helper (I)I\n", ""),
        "breaking",
        ["kotlin-removed"],
    )
    scenario(
        "a default parameter added (JVM descriptor changes)",
        with_jvm("helper (I)I", "helper (II)I"),
        "breaking",
        ["kotlin-removed"],
    )
    scenario(
        "a record removed",
        lambda repository: os.remove(os.path.join(repository.directory, "lib/api/android/lib.api")),
        "breaking",
        ["kotlin-dump-removed"],
    )
    scenario(
        "a subtype of an output sealed type",
        with_klib(
            "    final object Queued : io.example/MutationOutcome // io.example/MutationOutcome.Queued|null[0]\n",
            "    final object Queued : io.example/MutationOutcome // io.example/MutationOutcome.Queued|null[0]\n"
            "    final object Rejected : io.example/MutationOutcome // io.example/MutationOutcome.Rejected|null[0]\n",
        ),
        "breaking",
        ["kotlin-sealed-subtype-added"],
    )
    scenario(
        "a subtype of an input-only sealed type",
        with_klib(
            "    final object NetworkOnly : io.example/ReadStrategy // io.example/ReadStrategy.NetworkOnly|null[0]\n",
            "    final object NetworkOnly : io.example/ReadStrategy // io.example/ReadStrategy.NetworkOnly|null[0]\n"
            "    final object CacheOnly : io.example/ReadStrategy // io.example/ReadStrategy.CacheOnly|null[0]\n",
        ),
        "additive",
        ["kotlin-sealed-subtype-added"],
    )
    scenario(
        "an entry of an output enum",
        with_klib(
            "    enum entry APPLIED // io.example/Outcome.APPLIED|null[0]\n",
            "    enum entry APPLIED // io.example/Outcome.APPLIED|null[0]\n"
            "    enum entry QUEUED // io.example/Outcome.QUEUED|null[0]\n",
        ),
        "breaking",
        ["kotlin-enum-entry-added"],
    )
    scenario(
        "an entry of an input-only enum (JVM record)",
        with_jvm(
            "\tpublic static final field A Lio/example/Mode;\n",
            "\tpublic static final field A Lio/example/Mode;\n\tpublic static final field B Lio/example/Mode;\n",
        ),
        "additive",
        ["kotlin-enum-entry-added"],
    )
    scenario(
        "an abstract member on an implementable interface",
        with_klib(
            "    abstract fun select(kotlin/String): kotlin/String // io.example/Client.select|select(kotlin.String){}[0]\n",
            "    abstract fun select(kotlin/String): kotlin/String // io.example/Client.select|select(kotlin.String){}[0]\n"
            "    abstract fun sync(): kotlin/Int // io.example/Client.sync|sync(){}[0]\n",
        ),
        "breaking",
        ["kotlin-abstract-member-added"],
    )
    scenario(
        "an abstract member on an implementable interface (JVM record)",
        with_jvm(
            "\tpublic abstract fun select (Ljava/lang/String;)Ljava/lang/String;\n",
            "\tpublic abstract fun select (Ljava/lang/String;)Ljava/lang/String;\n\tpublic abstract fun sync ()I\n",
        ),
        "breaking",
        ["kotlin-abstract-member-added"],
    )
    scenario(
        "a member with a default body on an implementable interface",
        with_klib(
            "    abstract fun select(kotlin/String): kotlin/String // io.example/Client.select|select(kotlin.String){}[0]\n",
            "    abstract fun select(kotlin/String): kotlin/String // io.example/Client.select|select(kotlin.String){}[0]\n"
            "    open fun sync(): kotlin/Int // io.example/Client.sync|sync(){}[0]\n",
        ),
        "additive",
        ["kotlin-added"],
        unexpected_categories=["kotlin-abstract-member-added"],
    )
    scenario(
        "an abstract member on a closed interface",
        with_klib(
            "    abstract fun a(): kotlin/Int // io.example/Closed.a|a(){}[0]\n",
            "    abstract fun a(): kotlin/Int // io.example/Closed.a|a(){}[0]\n"
            "    abstract fun b(): kotlin/Int // io.example/Closed.b|b(){}[0]\n",
        ),
        "additive",
        ["kotlin-added"],
    )
    scenario(
        "an abstract member on a sealed interface (JVM record, which does not mark sealed)",
        with_jvm(
            "\tpublic fun resultOrNull ()Ljava/lang/String;\n",
            "\tpublic fun resultOrNull ()Ljava/lang/String;\n\tpublic abstract fun id ()I\n",
        ),
        "additive",
        ["kotlin-added"],
        unexpected_categories=["kotlin-abstract-member-added"],
    )
    scenario(
        "a dropped klib target",
        with_klib("[iosArm64, iosSimulatorArm64]", "[iosArm64]"),
        "breaking",
        ["klib-target-removed"],
    )
    scenario(
        "a changed klib unique name",
        with_klib("<io.example:lib>", "<io.example.renamed:lib>"),
        "breaking",
        ["klib-unique-name-changed"],
    )
    scenario(
        "a Swift case added to an output enum",
        with_swift("  case idle\n", "  case idle\n  case paused\n"),
        "breaking",
        ["swift-enum-case-added"],
    )
    scenario(
        "a Swift case added to an input-only enum",
        with_swift("  case networkOnly\n", "  case networkOnly\n  case cacheOnly\n"),
        "additive",
        ["swift-enum-case-added"],
    )
    scenario(
        "a Swift method removed",
        with_swift("  final public func close()\n", ""),
        "breaking",
        ["swift-removed"],
    )
    scenario(
        "a new Room schema version",
        lambda repository: repository.write("lib/schemas/3.json", '{"version": 3}'),
        "additive",
        ["room-schema-version-added"],
        ["format"],
    )
    scenario(
        "an edited Room schema",
        lambda repository: repository.write("lib/schemas/2.json", '{"version": 2, "edited": true}'),
        "breaking",
        ["room-schema-edited"],
        ["error"],
    )
    scenario(
        "a persisted name changed",
        lambda repository: repository.write("lib/src/commonTest/PersistedNamesTest.kt", 'val NAME = "owners"\n'),
        "additive",
        ["persisted-contract-changed"],
        ["format"],
    )
    scenario(
        "the compatibility rules changed",
        lambda repository: repository.write("compat/policy.json", json.dumps(dict(POLICY, changelog="CHANGELOG.md"), indent=2)),
        "patch",
        ["compatibility-rules-changed"],
    )
    scenario("a supabase-kt minor bump", with_catalog(bom="3.9.0"), "additive", ["upstream-minor-changed"], ["dependency"])
    scenario("a supabase-kt patch bump", with_catalog(bom="3.8.1"), "patch", ["upstream-patch-changed"])
    scenario("a Kotlin tooling release", with_catalog(kotlin="2.5.0"), "additive", ["kotlin-floor-changed"], ["dependency"])
    scenario("a Kotlin bug-fix release", with_catalog(kotlin="2.4.21"), "patch", ["kotlin-bugfix-changed"])
    scenario("a raised minSdk", with_catalog(min_sdk="24"), "additive", ["toolchain-floor-raised"], ["dependency"])

    # A record may hold the same line twice (overloads that differ only in what the record omits);
    # removing one of them is still a removal.
    with fixture() as (repository, base):
        doubled = JVM_BASE.replace("\tpublic static final fun helper (I)I\n", "\tpublic static final fun helper (I)I\n" * 2)
        repository.write("lib/api/android/lib.api", doubled)
        base = repository.commit("the same line twice")
        repository.write("lib/api/android/lib.api", JVM_BASE)
        head = repository.commit("once")
        report, _ = classify(repository.git, base, head)
    expect(report.level() == "breaking" and any(finding.category == "kotlin-removed" for finding in report.findings),
           "one of two identical lines removed: breaking", failures)

    # The policy must name files that exist, or a rename silently ends a watch.
    with fixture() as (repository, base):
        os.remove(os.path.join(repository.directory, "lib/src/commonTest/PersistedNamesTest.kt"))
        head = repository.commit("pin moved")
        _, problems = classify(repository.git, base, head)
    expect(any("PersistedNamesTest.kt, which does not exist" in problem for problem in problems),
           "a watched file that no longer exists is a problem", failures)

    # An override downgrades one finding and must carry a reason.
    with fixture() as (repository, base):
        repository.write("lib/api/lib.klib.api", KLIB_BASE.replace("final fun io.example/helper", "final fun io.example/renamed"))
        head = repository.commit("renamed")
        report, _ = classify(repository.git, base, head)
        key = next(finding.key() for finding in report.findings if finding.category == "kotlin-removed")
        repository.write("compat/overrides.txt", f"{key}  # experimental API, never documented as stable\n")
        head = repository.commit("override")
        report, problems = classify(repository.git, base, head)
        expect(report.level() == "additive" and not problems, "an override downgrades the finding it names", failures)
        repository.write("compat/overrides.txt", f"{key}\n")
        head = repository.commit("override without reason")
        _, problems = classify(repository.git, base, head)
        expect(any("reason" in problem for problem in problems), "an override without a reason is refused", failures)
        repository.write("compat/overrides.txt", "kotlin-removed: nothing like this  # stale\n")
        head = repository.commit("stale override")
        _, problems = classify(repository.git, base, head)
        expect(any("matches no finding" in problem for problem in problems), "an override that matches nothing is refused", failures)

    # An edited schema can never be overridden.
    with fixture() as (repository, base):
        repository.write("lib/schemas/2.json", '{"version": 2, "edited": true}')
        head = repository.commit("edited")
        report, _ = classify(repository.git, base, head)
        key = next(finding.key() for finding in report.findings if finding.category == "room-schema-edited")
        repository.write("compat/overrides.txt", f"{key}  # trying\n")
        head = repository.commit("override the schema edit")
        report, problems = classify(repository.git, base, head)
        expect("error" in report.flags() and any("matches no finding" in problem for problem in problems),
               "an edited schema cannot be overridden", failures)

    # check-pr: each classification needs its heading, and only that.
    with fixture() as (repository, base):
        repository.write("lib/api/lib.klib.api", KLIB_BASE.replace("final fun io.example/helper", "final fun io.example/renamed"))
        head = repository.commit("breaking without changelog")
        expect(quietly(command_check_pr, repository.git, argparse.Namespace(base=base, head=head, swift_report=None)) == 1,
               "check-pr fails a breaking change with no Breaking bullet", failures)
        repository.write("CHANGELOG.md", CHANGELOG_BASE.replace("### Added\n\n### Fixed", "### Added\n\n- `renamed`.\n\n### Fixed", 1))
        head = repository.commit("breaking with an Added bullet")
        expect(quietly(command_check_pr, repository.git, argparse.Namespace(base=base, head=head, swift_report=None)) == 1,
               "check-pr fails a breaking change with only an Added bullet", failures)
        repository.write("CHANGELOG.md", CHANGELOG_BASE.replace("### Added\n\n### Fixed", "### Breaking\n\n- `helper` is now `renamed`.\n\n### Added\n\n### Fixed", 1))
        head = repository.commit("breaking with changelog")
        expect(quietly(command_check_pr, repository.git, argparse.Namespace(base=base, head=head, swift_report=None)) == 0,
               "check-pr passes once the Breaking bullet is there", failures)

        # check-release: 0.1.0 -> 0.1.1 with a breaking change fails; 0.2.0 passes.
        expect(quietly(command_check_release, repository.git,
                       argparse.Namespace(from_tag="0.1.0", version="0.1.1", head=head, swift_report=None)) == 1,
               "check-release refuses a breaking change in a patch", failures)
        expect(quietly(command_check_release, repository.git,
                       argparse.Namespace(from_tag="0.1.0", version="0.2.0", head=head, swift_report=None)) == 0,
               "check-release accepts a breaking change in a 0.x minor", failures)
        expect(quietly(command_check_release, repository.git,
                       argparse.Namespace(from_tag="0.1.0", version="0.1.0", head=head, swift_report=None)) == 1,
               "check-release refuses a version that is not greater", failures)
        expect(quietly(command_check_release, repository.git,
                       argparse.Namespace(from_tag=None, version=None, head=head, swift_report=None)) == 1,
               "check-release finds the last tag and the version in gradle.properties (0.1.1: too small)", failures)

    with fixture() as (repository, base):
        repository.write("lib/api/lib.klib.api", KLIB_BASE + "final fun io.example/more(): kotlin/Int // io.example/more|more(){}[0]\n")
        head = repository.commit("additive without changelog")
        expect(quietly(command_check_pr, repository.git, argparse.Namespace(base=base, head=head, swift_report=None)) == 1,
               "check-pr fails an additive change with no bullet", failures)
        repository.write("CHANGELOG.md", CHANGELOG_BASE.replace("### Added\n\n### Fixed", "### Added\n\n- `more`.\n\n### Fixed", 1))
        head = repository.commit("additive with changelog")
        expect(quietly(command_check_pr, repository.git, argparse.Namespace(base=base, head=head, swift_report=None)) == 0,
               "check-pr passes an additive change with an Added bullet", failures)
        expect(quietly(command_check_release, repository.git,
                       argparse.Namespace(from_tag="0.1.0", version="0.1.1", head=head, swift_report=None)) == 1,
               "check-release refuses an addition in a 0.x patch", failures)

    with fixture() as (repository, base):
        head = repository.commit("nothing")
        expect(quietly(command_check_pr, repository.git, argparse.Namespace(base=base, head=head, swift_report=None)) == 0,
               "check-pr asks nothing of a change that touches no contract", failures)
        expect(quietly(command_check_release, repository.git,
                       argparse.Namespace(from_tag="0.1.0", version="0.1.1", head=head, swift_report=None)) == 0,
               "check-release accepts a patch when nothing changed", failures)
        # A behavioural change marked Breaking by a person, with no record change, still needs a minor.
        repository.write("CHANGELOG.md", CHANGELOG_BASE.replace("### Added\n\n### Fixed", "### Breaking\n\n- Automatic replay is now on by default.\n\n### Added\n\n### Fixed", 1))
        head = repository.commit("behaviour change")
        expect(quietly(command_check_release, repository.git,
                       argparse.Namespace(from_tag="0.1.0", version="0.1.1", head=head, swift_report=None)) == 1,
               "check-release honours a Breaking bullet a person wrote", failures)

    with fixture() as (repository, base):
        expect(quietly(command_check_release, repository.git,
                       argparse.Namespace(from_tag=None, version="0.1.0", head=base, swift_report=None)) == 1,
               "check-release refuses to re-release the last tag", failures)
        repository.git.run("tag", "-d", "0.1.0")
        expect(quietly(command_check_release, repository.git,
                       argparse.Namespace(from_tag=None, version="0.1.0", head=base, swift_report=None)) == 0,
               "check-release passes the first release, with no earlier tag", failures)

    with fixture() as (repository, base):
        try:
            classify(repository.git, "0000000000000000000000000000000000000000", base)
            expect(False, "a base missing from the clone is refused", failures)
        except SystemExit as error:
            expect("Fetch enough history" in str(error), "a base missing from the clone is refused", failures)

    print()
    if failures:
        print(f"SELF-TEST FAILED: {len(failures)} check(s).")
        return 1
    print("SELF-TEST PASSED.")
    return 0


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    commands = parser.add_subparsers(dest="command")
    commands.required = True
    for name in ("classify", "check-pr"):
        sub = commands.add_parser(name)
        sub.add_argument("--base", required=True)
        sub.add_argument("--head", default="HEAD")
        sub.add_argument("--swift-report", help="output of the Swift interface diagnosis, when there is one")
    release = commands.add_parser("check-release")
    release.add_argument("--from", dest="from_tag")
    release.add_argument("--version")
    release.add_argument("--head", default="HEAD")
    release.add_argument("--swift-report")
    commands.add_parser("self-test")
    arguments = parser.parse_args(argv)
    if arguments.command == "self-test":
        return self_test()
    git = Git(arguments.repo)
    if arguments.command == "classify":
        return command_classify(git, arguments)
    if arguments.command == "check-pr":
        return command_check_pr(git, arguments)
    return command_check_release(git, arguments)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
