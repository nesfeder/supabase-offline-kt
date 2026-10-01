#!/usr/bin/env python3
"""Publishes the library to a repository in the build directory and checks what Maven Central
would receive.

Every published module applies ``supabase-offline.published``, which gives it the task
``publishAllPublicationsToBuildDirectoryRepository``, writing to ``build/maven-repository``. This
script runs that task in every module, then reads each artifact under ``GROUP`` at
``VERSION_NAME`` (both from ``gradle.properties``) and fails when:

  1. the artifacts, the files each carries, their Gradle module variants and dependencies, their
     POM dependencies, the klibs' unique names or the Android classes' Kotlin module file differ
     from the record, ``compat/published-artifacts.txt``;
  2. a POM lacks a field Maven Central requires (name, description, URL, license, developer, SCM),
     has other coordinates than the build's, a name or description other than the module's
     ``POM_NAME`` / ``POM_DESCRIPTION``, or any email address;
  3. a sources jar holds no Kotlin source;
  4. a documentation jar lacks the HTML reference: its index, and a page for each public package
     of the module's sources; or has a page for an internal package;
  5. a file lacks its ``.md5``, ``.sha1``, ``.sha256`` or ``.sha512``, or one does not match it (a
     signature, ``.asc``, needs none: Gradle writes none for it and Maven Central needs none);
  6. a signature is not an ASCII-armored PGP signature, or some files are signed and others are
     not, or, when the signing key is in the environment (``ORG_GRADLE_PROJECT_signingInMemoryKey``,
     as in the release job), any file is unsigned;
  7. a file, or a file inside an archive, holds the absolute path of the repository or of the home
     directory, which would publish the builder's machine.

The unique name and the Kotlin module file are recorded because they also name every internal
member of the compiled library: a plugin that renamed the archive would rename them all.

Usage (from the repository root):
  check-published-artifacts.py               publish, then check
  check-published-artifacts.py --update      publish, then write the record from what was published
  check-published-artifacts.py --no-publish  check build/maven-repository as it stands (combines
                                             with --update)
  check-published-artifacts.py --self-test   prove the check sees each kind of problem

The record lists facts, not contents: rewrite it with ``--update`` when a change means to alter
what is published, and review its diff, since a dependency line there is what every consumer's
build resolves. ``$VERSION`` stands for VERSION_NAME.

Runs on the system python3 of macOS (3.9) and on CI runners; standard library only.
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RECORD = Path("compat/published-artifacts.txt")
REPOSITORY = Path("build/maven-repository")
PUBLISH_TASK = "publishAllPublicationsToBuildDirectoryRepository"
CHECKSUMS = ("md5", "sha1", "sha256", "sha512")
POM_NAMESPACE = {"pom": "http://maven.apache.org/POM/4.0.0"}
# A Gradle file repository names a snapshot's files by the time it published them.
SNAPSHOT_STAMP = r"\d{8}\.\d{6}-\d+"
RECORD_HEADER = """\
# Everything Maven Central receives for each published artifact, apart from what is inside its
# files: the files, the Gradle module variants and their dependencies, the POM's dependencies, the
# klibs' unique names and the Android classes' Kotlin module file. $VERSION stands for
# VERSION_NAME. scripts/check-published-artifacts.py publishes the library to the build directory
# and fails when this differs; after a change that means to alter it, run
#   python3 scripts/check-published-artifacts.py --update
# and review the diff: a dependency line here is what every consumer's build resolves.
"""


class Problems(list):
    """The problems found, each a sentence naming the artifact or file."""


def read_properties(path: Path) -> dict[str, str]:
    """The key=value lines of a .properties file; comments and blank lines are skipped."""
    properties: dict[str, str] = {}
    if not path.is_file():
        return properties
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")) or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties


def file_version_pattern(version: str) -> str:
    """The version as it appears in a file name: itself, or a snapshot's timestamped form."""
    if version.endswith("-SNAPSHOT"):
        return re.escape(version[: -len("-SNAPSHOT")]) + "-(?:SNAPSHOT|" + SNAPSHOT_STAMP + ")"
    return re.escape(version)


def normalize(text: str, version: str) -> str:
    """Replaces the version where it stands as a whole token, in either form, with $VERSION."""
    return re.sub(r"(?<![\w.-])" + file_version_pattern(version) + r"(?![\w.-])", "$VERSION", text)


def suffix_of(file_name: str, artifact: str, version: str) -> str | None:
    """What follows `<artifact>-<version>` in a published file's name, or None when it is not one."""
    match = re.fullmatch(re.escape(artifact) + "-" + file_version_pattern(version) + r"(.+)", file_name)
    return match.group(1) if match else None


def is_checksum(name: str) -> bool:
    """Whether the file is a checksum of another."""
    return any(name.endswith("." + algorithm) for algorithm in CHECKSUMS)


def open_zip(data: bytes) -> zipfile.ZipFile | None:
    """The archive in data, or None when it is not one."""
    if not data.startswith(b"PK"):
        return None
    try:
        return zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return None


class Artifact:
    """One published artifact directory: `<group path>/<name>/<version>/`."""

    def __init__(self, name: str, directory: Path, version: str) -> None:
        self.name = name
        self.directory = directory
        self.version = version
        self.files: dict[str, Path] = {}
        for path in sorted(directory.iterdir()):
            if path.is_file() and not is_checksum(path.name) and not path.name.endswith(".asc"):
                suffix = suffix_of(path.name, name, version)
                if suffix is not None:
                    self.files[suffix] = path

    def read(self, suffix: str) -> bytes | None:
        """The bytes of the file with this suffix, or None when there is none."""
        path = self.files.get(suffix)
        return path.read_bytes() if path else None

    def module_json(self) -> dict:
        """The Gradle module file, or an empty mapping when it is missing or unreadable."""
        data = self.read(".module")
        try:
            return json.loads(data) if data else {}
        except ValueError:
            return {}

    def module_name(self) -> str:
        """The published module this artifact belongs to: its own name for a module's root."""
        component = self.module_json().get("component", {})
        return component.get("module", self.name) if "url" in component else self.name


def find_artifacts(repository: Path, group: str, version: str) -> list[Artifact]:
    """Every artifact under the group that has a directory for the version."""
    group_directory = repository.joinpath(*group.split("."))
    if not group_directory.is_dir():
        return []
    return [
        Artifact(child.name, child / version, version)
        for child in sorted(group_directory.iterdir())
        if (child / version).is_dir()
    ]


# ─────────────────────────────────────────────────────────────────────────────
# The record: facts that must equal compat/published-artifacts.txt
# ─────────────────────────────────────────────────────────────────────────────


def version_text(version: object) -> str:
    """A module-file dependency version, such as `requires=3.8.0`, or `(no version)`."""
    if not isinstance(version, dict) or not version:
        return "(no version)"
    return " ".join(f"{key}={json.dumps(version[key]) if not isinstance(version[key], str) else version[key]}"
                    for key in sorted(version))


def attributes_text(attributes: object) -> str:
    """A dependency's attributes, such as ` [org.gradle.category=platform]`, or nothing."""
    if not isinstance(attributes, dict) or not attributes:
        return ""
    return " [" + ", ".join(f"{key}={attributes[key]}" for key in sorted(attributes)) + "]"


def module_facts(artifact: Artifact) -> list[str]:
    """The variants of the Gradle module file and what each depends on or points at."""
    facts = []
    for variant in artifact.module_json().get("variants", []):
        name = variant.get("name", "?")
        prefix = f"{artifact.name} variant {name}"
        available = variant.get("available-at")
        if available:
            facts.append(f"{prefix} available-at {available.get('module')}")
        for kind in ("dependencies", "dependencyConstraints"):
            for dependency in variant.get(kind, []):
                word = "dependency" if kind == "dependencies" else "constraint"
                coordinates = f"{dependency.get('group')}:{dependency.get('module')}"
                excludes = dependency.get("excludes")
                excluded = f" excludes={json.dumps(excludes, sort_keys=True)}" if excludes else ""
                facts.append(f"{prefix} {word} {coordinates} {version_text(dependency.get('version'))}"
                             f"{attributes_text(dependency.get('attributes'))}{excluded}")
        if not available and not variant.get("files"):
            facts.append(f"{prefix} no-files")
    return facts


def pom_root(artifact: Artifact) -> ElementTree.Element | None:
    """The POM's root element, or None when it is missing or unreadable."""
    data = artifact.read(".pom")
    if data is None:
        return None
    try:
        return ElementTree.fromstring(data)
    except ElementTree.ParseError:
        return None


def pom_text(element: ElementTree.Element | None, path: str) -> str:
    """The stripped text at path under element, or an empty string."""
    if element is None:
        return ""
    found = element.find(path, POM_NAMESPACE)
    return (found.text or "").strip() if found is not None else ""


def pom_facts(artifact: Artifact) -> list[str]:
    """The POM's dependencies and managed dependencies, with scope and type."""
    root = pom_root(artifact)
    facts = []
    if root is None:
        return facts
    for section, word in (("pom:dependencyManagement/pom:dependencies/pom:dependency", "managed"),
                          ("pom:dependencies/pom:dependency", "dependency")):
        for dependency in root.findall(section, POM_NAMESPACE):
            coordinates = ":".join(pom_text(dependency, f"pom:{field}") or "(none)"
                                   for field in ("groupId", "artifactId", "version"))
            details = " ".join(f"{field}={pom_text(dependency, 'pom:' + field)}"
                               for field in ("scope", "type", "optional")
                               if pom_text(dependency, "pom:" + field))
            facts.append(f"{artifact.name} pom {word} {coordinates} {details}".rstrip())
    return facts


def archive_facts(artifact: Artifact) -> list[str]:
    """The unique name of every klib in the artifact, and the Android classes' Kotlin module file."""
    facts = []
    for suffix, path in artifact.files.items():
        archive = open_zip(path.read_bytes())
        if archive is None:
            continue
        for entry in archive.namelist():
            if entry == "default/manifest" or entry.endswith("/default/manifest"):
                manifest = archive.read(entry).decode("utf-8", "replace")
                match = re.search(r"^unique_name=(.*)$", manifest, re.MULTILINE)
                name = match.group(1).replace("\\:", ":") if match else "(none)"
                facts.append(f"{artifact.name} klib {suffix}!{entry} unique_name={name}")
        if "classes.jar" in archive.namelist():
            classes = open_zip(archive.read("classes.jar"))
            for entry in (classes.namelist() if classes else []):
                if entry.startswith("META-INF/") and entry.endswith(".kotlin_module"):
                    facts.append(f"{artifact.name} kotlin-module {suffix}!classes.jar!{entry}")
    return facts


def record_lines(artifacts: list[Artifact], version: str) -> list[str]:
    """Every recorded fact about the artifacts, sorted, with the version as $VERSION."""
    lines = []
    for artifact in artifacts:
        lines += [f"{artifact.name} file {suffix}" for suffix in artifact.files]
        lines += module_facts(artifact) + pom_facts(artifact) + archive_facts(artifact)
    return sorted({normalize(line, version) for line in lines})


def read_record(path: Path) -> list[str]:
    """The record's fact lines, without comments and blank lines."""
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")]


def record_problems(expected: list[str], actual: list[str]) -> Problems:
    """The facts only one side has."""
    problems = Problems()
    missing, extra = sorted(set(expected) - set(actual)), sorted(set(actual) - set(expected))
    problems += [f"recorded, not published: {line}" for line in missing]
    problems += [f"published, not recorded: {line}" for line in extra]
    return problems


# ─────────────────────────────────────────────────────────────────────────────
# Requirements every publication must meet
# ─────────────────────────────────────────────────────────────────────────────


def expected_pom_text(root: Path, module: str) -> dict[str, str]:
    """POM_NAME and POM_DESCRIPTION for the module: its own gradle.properties over the root's."""
    properties = read_properties(root / "gradle.properties")
    properties.update(read_properties(root / module / "gradle.properties"))
    return {key: properties.get(key, "") for key in ("POM_NAME", "POM_DESCRIPTION")}


def pom_problems(artifact: Artifact, group: str, version: str, expected: dict[str, str]) -> Problems:
    """What Maven Central requires of the POM, and what this project promises in it."""
    problems = Problems()
    data = artifact.read(".pom")
    root = pom_root(artifact)
    if root is None:
        return Problems([f"{artifact.name}: no readable POM"])
    where = f"{artifact.name}: the POM"
    for field, wanted in (("groupId", group), ("artifactId", artifact.name), ("version", version)):
        if pom_text(root, f"pom:{field}") != wanted:
            problems.append(f"{where} has the coordinates {pom_text(root, 'pom:' + field)!r} for"
                            f" {field}, not {wanted!r}")
    for field, key in (("name", "POM_NAME"), ("description", "POM_DESCRIPTION")):
        value = pom_text(root, f"pom:{field}")
        if not value:
            problems.append(f"{where} has no {field}")
        elif value != expected.get(key):
            problems.append(f"{where} has the {field} {value!r}, not {key} {expected.get(key)!r}")
    required = {
        "url": "pom:url",
        "license name": "pom:licenses/pom:license/pom:name",
        "license URL": "pom:licenses/pom:license/pom:url",
        "developer id": "pom:developers/pom:developer/pom:id",
        "developer name": "pom:developers/pom:developer/pom:name",
        "SCM URL": "pom:scm/pom:url",
        "SCM connection": "pom:scm/pom:connection",
        "SCM developer connection": "pom:scm/pom:developerConnection",
    }
    for label, path in required.items():
        if not pom_text(root, path):
            problems.append(f"{where} has no {label}")
    # Outside the SCM section, whose SSH URL has the form user@host.
    outside_scm = re.sub(rb"<scm>.*?</scm>", b"", data or b"", flags=re.DOTALL)
    if root.find(".//pom:email", POM_NAMESPACE) is not None or re.search(
            rb"[\w.+-]+@[\w-]+\.[\w.-]+", outside_scm):
        problems.append(f"{where} carries an email address")
    return problems


def zip_entries(artifact: Artifact, suffix: str) -> list[str] | None:
    """The entries of the artifact's archive with this suffix, or None when it is not there."""
    data = artifact.read(suffix)
    archive = open_zip(data) if data is not None else None
    return archive.namelist() if archive else None


def source_packages(artifacts: list[Artifact]) -> set[str]:
    """The packages of the Kotlin sources in the artifacts' sources jars, as dotted names."""
    packages = set()
    for artifact in artifacts:
        for entry in zip_entries(artifact, "-sources.jar") or []:
            parts = entry.split("/")
            if entry.endswith(".kt") and len(parts) > 2:
                packages.add(".".join(parts[1:-1]))
    return packages


def documentation_problems(module: str, artifacts: list[Artifact]) -> Problems:
    """The sources and documentation jars of one module's artifacts."""
    problems = Problems()
    public = sorted(package for package in source_packages(artifacts)
                    if "internal" not in package.split("."))
    for artifact in artifacts:
        sources = zip_entries(artifact, "-sources.jar")
        if sources is None:
            problems.append(f"{artifact.name}: no sources jar")
        elif not any(entry.endswith(".kt") for entry in sources):
            problems.append(f"{artifact.name}: the sources jar holds no Kotlin source")
        documentation = zip_entries(artifact, "-javadoc.jar")
        if documentation is None:
            problems.append(f"{artifact.name}: no documentation jar")
            continue
        pages = set(documentation)
        if "index.html" not in pages:
            problems.append(f"{artifact.name}: the documentation jar has no index.html")
        if not public:
            problems.append(f"{artifact.name}: no public package found in the module's sources jars")
        for package in public:
            if f"{module}/{package}/index.html" not in pages:
                problems.append(f"{artifact.name}: the documentation jar has no page for the"
                                f" public package {package}")
        leaked = sorted({entry.split("/")[1] for entry in pages
                         if entry.startswith(module + "/") and entry.count("/") >= 2
                         and "internal" in entry.split("/")[1].split(".")})
        for package in leaked:
            problems.append(f"{artifact.name}: the documentation jar documents the internal package"
                            f" {package}")
    return problems


def checksum_problems(repository: Path) -> Problems:
    """Every file in the repository but a signature beside its four checksums, each matching it."""
    problems = Problems()
    for path in sorted(repository.rglob("*")):
        if not path.is_file() or is_checksum(path.name) or path.name.endswith(".asc"):
            continue
        data = path.read_bytes()
        for algorithm in CHECKSUMS:
            checksum = path.with_name(f"{path.name}.{algorithm}")
            relative = checksum.relative_to(repository)
            if not checksum.is_file():
                problems.append(f"{relative}: missing")
                continue
            recorded = checksum.read_text(encoding="utf-8").split()
            if not recorded or recorded[0].lower() != hashlib.new(algorithm, data).hexdigest():
                problems.append(f"{relative}: does not match the file")
    return problems


PGP_SIGNATURE = b"-----BEGIN PGP SIGNATURE-----"


def signature_problems(artifacts: list[Artifact], key_given: bool) -> Problems:
    """Every published file signed when the key was given, otherwise all of them or none (a build
    without the key signs nothing); and every signature an ASCII-armored PGP signature."""
    problems = Problems()
    signed, unsigned = [], []
    for artifact in artifacts:
        for path in artifact.files.values():
            signature = path.with_name(path.name + ".asc")
            if not signature.is_file():
                unsigned.append(path.name)
                continue
            signed.append(path.name)
            if not signature.read_bytes().lstrip().startswith(PGP_SIGNATURE):
                problems.append(f"{signature.name}: not an ASCII-armored PGP signature")
    if unsigned and (signed or key_given):
        reason = "the signing key was given" if key_given else f"{len(signed)} other file(s) are"
        problems += [f"{name}: not signed, while {reason}" for name in sorted(unsigned)]
    return problems


def absolute_path_problems(repository: Path, forbidden: list[str]) -> Problems:
    """Files, or files in archives two levels deep, holding one of the forbidden paths."""
    problems = Problems()
    needles = [path.encode("utf-8") for path in forbidden if len(path) >= 5]

    def scan(label: str, data: bytes, depth: int) -> None:
        for needle in needles:
            if needle in data:
                problems.append(f"{label}: holds the absolute path {needle.decode()}")
        archive = open_zip(data) if depth < 2 else None
        for entry in (archive.namelist() if archive else []):
            scan(f"{label}!{entry}", archive.read(entry), depth + 1)

    for path in sorted(repository.rglob("*")):
        if path.is_file() and not is_checksum(path.name):
            scan(str(path.relative_to(repository)), path.read_bytes(), 0)
    return problems


def check(root: Path, repository: Path, record: Path, update: bool, forbidden: list[str],
          key_given: bool = False) -> int:
    """Checks the repository against the record and the requirements; returns the exit code.
    `key_given`: the build had the signing key, so every file must be signed."""
    properties = read_properties(root / "gradle.properties")
    group, version = properties.get("GROUP", ""), properties.get("VERSION_NAME", "")
    if not group or not version:
        print("::error::gradle.properties has no GROUP or VERSION_NAME")
        return 1
    artifacts = find_artifacts(repository, group, version)
    if not artifacts:
        print(f"::error::Nothing under {group} at {version} in {repository}")
        return 1
    actual = record_lines(artifacts, version)
    if update:
        record.write_text(RECORD_HEADER + "\n".join(actual) + "\n", encoding="utf-8")
        print(f"  Wrote {len(actual)} facts about {len(artifacts)} artifacts to {record}.")
    problems = Problems()
    if not record.is_file():
        problems.append(f"{record} is missing; run with --update and review it")
    else:
        problems += record_problems(read_record(record), actual)
    modules: dict[str, list[Artifact]] = {}
    for artifact in artifacts:
        modules.setdefault(artifact.module_name(), []).append(artifact)
    for module, members in sorted(modules.items()):
        expected = expected_pom_text(root, module)
        for artifact in members:
            problems += pom_problems(artifact, group, version, expected)
        problems += documentation_problems(module, members)
    problems += checksum_problems(repository)
    problems += signature_problems(artifacts, key_given)
    problems += absolute_path_problems(repository, forbidden)
    for problem in problems:
        print(f"  PROBLEM {problem}")
    if problems:
        print(f"::error::{len(problems)} problem(s) with the published artifacts; see the log."
              " If a change meant to alter what is published, run"
              " `python3 scripts/check-published-artifacts.py --update` and commit the record.")
        return 1
    files = sum(1 for path in repository.rglob("*") if path.is_file())
    print(f"  {len(artifacts)} artifacts of {len(modules)} modules at {version}, {files} files:"
          f" every POM, sources jar, documentation jar and checksum as required, and"
          f" {len(actual)} recorded facts unchanged.")
    return 0


def publish(root: Path, repository: Path) -> None:
    """Empties the build-directory repository and publishes every module to it."""
    target = (root / repository).resolve()
    if target.parent != (root / "build").resolve():
        raise SystemExit(f"refusing to empty {target}: it is not directly inside {root / 'build'}")
    if target.exists():
        shutil.rmtree(target)
    command = [str(root / "gradlew"), PUBLISH_TASK, "--stacktrace"]
    print("  $ " + " ".join(["./gradlew"] + command[1:]), flush=True)
    subprocess.run(command, cwd=root, check=True)


# ─────────────────────────────────────────────────────────────────────────────
# Self-test
# ─────────────────────────────────────────────────────────────────────────────

FIXTURE_GROUP = "io.example.group"
FIXTURE_POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <name>lib</name>
  <description>A library.</description>
  <url>https://example.com/lib</url>
  <licenses><license><name>MIT License</name><url>https://example.com/LICENSE</url></license></licenses>
  <developers><developer><id>someone</id><name>Some One</name><url>https://example.com</url></developer></developers>
  <scm><connection>scm:git:git://example.com/lib.git</connection><developerConnection>scm:git:ssh://git@example.com/lib.git</developerConnection><url>https://example.com/lib</url></scm>
  <dependencies><dependency><groupId>org.example</groupId><artifactId>dep</artifactId><version>1.2.3</version><scope>compile</scope></dependency></dependencies>
</project>
"""


def zip_bytes(entries: dict[str, bytes]) -> bytes:
    """An archive holding the entries."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def write_with_checksums(path: Path, data: bytes) -> None:
    """Writes the file and its four checksums."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    for algorithm in CHECKSUMS:
        path.with_name(f"{path.name}.{algorithm}").write_text(hashlib.new(algorithm, data).hexdigest())


def fixture_files(artifact: str, version: str) -> dict[str, bytes]:
    """The files of one fixture artifact, by suffix."""
    module = {
        "component": {"group": FIXTURE_GROUP, "module": artifact, "version": version},
        "variants": [{"name": "apiElements", "dependencies": [
            {"group": "org.example", "module": "dep", "version": {"requires": "1.2.3"}}],
            "files": [{"url": f"{artifact}-{version}.klib"}]}],
    }
    if artifact != "lib":
        module["component"] = {"url": f"../../lib/{version}/lib-{version}.module", "group": FIXTURE_GROUP,
                               "module": "lib", "version": version}
    files = {
        ".pom": FIXTURE_POM.format(group=FIXTURE_GROUP, artifact=artifact, version=version).encode(),
        ".module": json.dumps(module).encode(),
        "-sources.jar": zip_bytes({"commonMain/io/example/lib/Api.kt": b"package io.example.lib",
                                   "commonMain/io/example/lib/internal/Engine.kt": b"package x"}),
        "-javadoc.jar": zip_bytes({"index.html": b"<html/>", "lib/io.example.lib/index.html": b"<html/>"}),
    }
    if artifact == "lib-iosarm64":
        files[".klib"] = zip_bytes({"default/manifest": b"unique_name=io.example.group\\:lib\n"})
    if artifact == "lib-android":
        files[".aar"] = zip_bytes({"classes.jar": zip_bytes({"META-INF/io.example.group_lib.kotlin_module": b""})})
    return files


def write_fixture(root: Path, version: str, snapshot_stamp: str = "") -> Path:
    """A repository root with gradle.properties and three published artifacts of module `lib`."""
    (root / "gradle.properties").parent.mkdir(parents=True, exist_ok=True)
    (root / "gradle.properties").write_text(
        f"GROUP={FIXTURE_GROUP}\nVERSION_NAME={version}\nPOM_NAME=lib\nPOM_DESCRIPTION=A library.\n")
    repository = root / "build" / "maven-repository"
    file_version = version.replace("SNAPSHOT", snapshot_stamp) if snapshot_stamp else version
    for artifact in ("lib", "lib-android", "lib-iosarm64"):
        directory = repository.joinpath(*FIXTURE_GROUP.split("."), artifact, version)
        for suffix, data in fixture_files(artifact, version).items():
            write_with_checksums(directory / f"{artifact}-{file_version}{suffix}", data)
        write_with_checksums(directory / "maven-metadata.xml", b"<metadata/>")
    return repository


def sign_all(repository: Path, content: bytes = PGP_SIGNATURE + b"\nfixture\n") -> None:
    """Writes a signature beside every published file of the fixture, as a build with the key does
    (and no checksum beside it, as Gradle writes none)."""
    for path in sorted(repository.rglob("*")):
        name = path.name
        if (path.is_file() and not is_checksum(name) and not name.endswith(".asc")
                and name != "maven-metadata.xml"):
            path.with_name(name + ".asc").write_bytes(content)


def artifact_path(repository: Path, artifact: str, version: str, suffix: str) -> Path:
    """The fixture file of the artifact with this suffix."""
    directory = repository.joinpath(*FIXTURE_GROUP.split("."), artifact, version)
    return next(path for path in directory.iterdir()
                if suffix_of(path.name, artifact, version) == suffix)


def rewrite(repository: Path, artifact: str, suffix: str, change) -> None:
    """Replaces a fixture file's bytes with change(bytes), checksums updated."""
    path = artifact_path(repository, artifact, "1.0.0", suffix)
    write_with_checksums(path, change(path.read_bytes()))


def remove(repository: Path, artifact: str, suffix: str) -> None:
    """Deletes a fixture file and its checksums."""
    path = artifact_path(repository, artifact, "1.0.0", suffix)
    for other in path.parent.glob(path.name + "*"):
        other.unlink()


CASES = [
    ("a POM without a license", "has no license name",
     lambda repo: rewrite(repo, "lib", ".pom", lambda data: re.sub(rb"<licenses>.*?</licenses>", b"", data))),
    ("a POM with an email", "carries an email address",
     lambda repo: rewrite(repo, "lib-android", ".pom",
                          lambda data: data.replace(b"<url>https://example.com</url>",
                                                    b"<email>someone@example.com</email>"))),
    ("a POM whose name is not POM_NAME", "not POM_NAME",
     lambda repo: rewrite(repo, "lib", ".pom", lambda data: data.replace(b"<name>lib</name>", b"<name>other</name>"))),
    ("a POM without an SCM developer connection", "has no SCM developer connection",
     lambda repo: rewrite(repo, "lib-iosarm64", ".pom",
                          lambda data: re.sub(rb"<developerConnection>.*?</developerConnection>", b"", data))),
    ("a POM with other coordinates", "has the coordinates",
     lambda repo: rewrite(repo, "lib", ".pom", lambda data: data.replace(b"<version>1.0.0</version>",
                                                                          b"<version>9.9.9</version>"))),
    ("a sources jar without Kotlin", "holds no Kotlin source",
     lambda repo: rewrite(repo, "lib-android", "-sources.jar", lambda data: zip_bytes({"README": b"x"}))),
    ("a documentation jar without the public package", "no page for the public package io.example.lib",
     lambda repo: rewrite(repo, "lib", "-javadoc.jar", lambda data: zip_bytes({"index.html": b"<html/>"}))),
    ("a documentation jar with an internal package", "documents the internal package",
     lambda repo: rewrite(repo, "lib-iosarm64", "-javadoc.jar", lambda data: zip_bytes({
         "index.html": b"", "lib/io.example.lib/index.html": b"",
         "lib/io.example.lib.internal/index.html": b""}))),
    ("no documentation jar", "no documentation jar",
     lambda repo: remove(repo, "lib-android", "-javadoc.jar")),
    ("a checksum that does not match", ".sha256: does not match the file",
     lambda repo: artifact_path(repo, "lib", "1.0.0", ".module").with_suffix(".module.sha256").write_text("00")),
    ("a missing checksum", ".sha512: missing",
     lambda repo: artifact_path(repo, "lib-iosarm64", "1.0.0", ".klib").with_suffix(".klib.sha512").unlink()),
    ("a dependency version that moved", "published, not recorded: lib variant apiElements dependency"
     " org.example:dep requires=1.2.4",
     lambda repo: rewrite(repo, "lib", ".module", lambda data: data.replace(b'"1.2.3"', b'"1.2.4"'))),
    ("a POM dependency that moved", "recorded, not published: lib-android pom dependency org.example:dep:1.2.3",
     lambda repo: rewrite(repo, "lib-android", ".pom", lambda data: data.replace(b"1.2.3", b"1.3.0"))),
    ("an artifact that is not recorded", "published, not recorded: lib-jvm file .pom",
     lambda repo: [write_with_checksums(repo.joinpath(*FIXTURE_GROUP.split("."), "lib-jvm", "1.0.0",
                                                      f"lib-jvm-1.0.0{suffix}"), data)
                   for suffix, data in fixture_files("lib-jvm", "1.0.0").items()]),
    ("a klib renamed", "published, not recorded: lib-iosarm64 klib .klib!default/manifest unique_name=other",
     lambda repo: rewrite(repo, "lib-iosarm64", ".klib",
                          lambda data: zip_bytes({"default/manifest": b"unique_name=other\n"}))),
    ("a Kotlin module file renamed", "recorded, not published: lib-android kotlin-module",
     lambda repo: rewrite(repo, "lib-android", ".aar", lambda data: zip_bytes(
         {"classes.jar": zip_bytes({"META-INF/other.kotlin_module": b""})}))),
    ("an absolute path inside a klib", "holds the absolute path",
     lambda repo: rewrite(repo, "lib-iosarm64", ".klib", lambda data: zip_bytes({
         "default/manifest": b"unique_name=io.example.group\\:lib\n",
         "default/ir/files.knf": str(repo.parent.parent).encode() + b"/src/Api.kt"}))),
    ("one file signed and the others not", "not signed, while 1 other file(s) are",
     lambda repo: artifact_path(repo, "lib", "1.0.0", ".pom").with_suffix(".pom.asc").write_bytes(
         PGP_SIGNATURE + b"\n")),
    ("every file signed, one signature not a PGP signature", "not an ASCII-armored PGP signature",
     lambda repo: (sign_all(repo), artifact_path(repo, "lib-android", "1.0.0", ".aar").with_suffix(
         ".aar.asc").write_bytes(b"garbage"))),
    ("every file signed but one", "lib-iosarm64-1.0.0.klib: not signed, while",
     lambda repo: (sign_all(repo), artifact_path(repo, "lib-iosarm64", "1.0.0", ".klib").with_suffix(
         ".klib.asc").unlink())),
]
# Cases that run as a build with the signing key in its environment does.
KEY_GIVEN_CASES = [
    ("the key given and nothing signed", "not signed, while the signing key was given",
     lambda repo: None),
]


def self_test() -> int:
    """Plants each problem in a compliant fixture and proves the check reports it."""
    failures = 0
    with tempfile.TemporaryDirectory() as work:
        def run(root: Path, update: bool = False, key_given: bool = False) -> tuple[int, str]:
            output = io.StringIO()
            stdout, sys.stdout = sys.stdout, output
            try:
                code = check(root, root / REPOSITORY, root / RECORD, update, [str(root)], key_given)
            finally:
                sys.stdout = stdout
            return code, output.getvalue()

        reference = Path(work) / "reference"
        write_fixture(reference, "1.0.0")
        (reference / "compat").mkdir()
        code, output = run(reference, update=True)
        code, output = run(reference)
        record = (reference / RECORD).read_text()
        ok = code == 0 and "$VERSION" in record and "1.0.0" not in record.replace("1.2.3", "")
        print(f"{'ok  ' if ok else 'FAIL'} compliant fixture: {'passes' if ok else output}")
        failures += not ok

        snapshot = Path(work) / "snapshot"
        write_fixture(snapshot, "1.0.0-SNAPSHOT", snapshot_stamp="20260930.120000-1")
        (snapshot / "compat").mkdir()
        (snapshot / RECORD).write_text(record)
        code, output = run(snapshot)
        print(f"{'ok  ' if code == 0 else 'FAIL'} timestamped snapshot files read as the release's:"
              f" {'passes' if code == 0 else output}")
        failures += code != 0

        signed = Path(work) / "signed"
        write_fixture(signed, "1.0.0")
        (signed / "compat").mkdir()
        (signed / RECORD).write_text(record)
        sign_all(signed / REPOSITORY)
        code, output = run(signed, key_given=True)
        print(f"{'ok  ' if code == 0 else 'FAIL'} every file signed, as the release job signs them:"
              f" {'passes' if code == 0 else output}")
        failures += code != 0

        cases = [(case, False) for case in CASES] + [(case, True) for case in KEY_GIVEN_CASES]
        for index, ((name, expected, plant), key_given) in enumerate(cases):
            root = Path(work) / f"case-{index}"
            write_fixture(root, "1.0.0")
            (root / "compat").mkdir()
            (root / RECORD).write_text(record)
            plant(root / REPOSITORY)
            code, output = run(root, key_given=key_given)
            caught = code != 0 and expected in output
            print(f"{'ok  ' if caught else 'FAIL'} {name}: {'caught' if caught else output}")
            failures += not caught
    if failures:
        print(f"SELF-TEST FAILED: {failures} case(s).")
        return 1
    print(f"SELF-TEST PASSED: {len(CASES) + len(KEY_GIVEN_CASES)} problems caught, and the"
          " compliant fixtures passed.")
    return 0


def main(arguments: list[str]) -> int:
    """Runs the self-test, or publishes and checks."""
    if arguments == ["--self-test"]:
        return self_test()
    if not set(arguments) <= {"--update", "--no-publish"} or len(set(arguments)) != len(arguments):
        raise SystemExit("usage: check-published-artifacts.py [--update] [--no-publish] | --self-test")
    if "--no-publish" not in arguments:
        publish(ROOT, REPOSITORY)
    forbidden = [str(ROOT), os.path.expanduser("~")]
    # The same condition under which the build signs (supabase-offline.published).
    key_given = bool(os.environ.get("ORG_GRADLE_PROJECT_signingInMemoryKey", "").strip())
    return check(ROOT, ROOT / REPOSITORY, ROOT / RECORD, "--update" in arguments, forbidden,
                 key_given)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
