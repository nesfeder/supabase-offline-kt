#!/usr/bin/env python3
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""Records the public functions and constructors of both published modules with their parameter
names, in order, and holds two rules over them. Whole-tree, from the sources; no Gradle.

Neither ABI record shows a parameter's name, yet a Kotlin caller passes arguments by name, so a
renamed or reordered parameter breaks source compatibility with nothing in the records changing.
This listing is the third record, beside them: `<module>/api/<module>.signatures`, one line per
public function or constructor of a public package (main source sets only), in the form

    suspend fun Offline.rpc(function: String, request: RpcRequestBuilder.() -> Unit = …)
    fun (BucketApi).ensureLocalFile(objectPath: String, isPublic: Boolean = …)  // androidMain
    constructor ReadStrategy.NetworkFirst(maxAge: Duration? = …)

each under its package. A receiver in parentheses is an extension's; a default value shows as `…`;
a declaration of one platform's source set names it. Declarations an opt-in marker listed in
compat/abi-exclusions.txt gates are left out, as the ABI records leave them out, and so is every
internal package.

It fails when:

  1. signatures-stale      a committed record differs from what the sources declare. Run it with
                           --update and commit the record; the diff is the review, and
                           scripts/api-compat.py rates a removed or changed line as breaking;
  2. suspend-without-throws a public suspend function that does not override another lacks
                           @Throws(Throwable::class). Without it, a failure reaching Swift through a
                           framework that exports the library ends the process. An override cannot
                           declare its own; it inherits its parent's.

It fails with status 2 when it finds no main source file to scan.

Usage:
    scripts/check-public-signatures.py               check the records and the rules
    scripts/check-public-signatures.py --update      rewrite the records from the sources
    scripts/check-public-signatures.py --self-test   prove the parser and both rules on probes
"""

import os
import re
import sys
import tempfile
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from kotlin_source import strip_comments_and_literals

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# The published modules. supabase-offline-swift-bridge is left out on purpose: it is never
# published, and the Swift package reaches it through its Objective-C header, which
# scripts/check-bridge-header.py records instead.
MODULES = ("supabase-offline-kt", "supabase-offline-kt-testing")
EXCLUSIONS_FILE = "compat/abi-exclusions.txt"
THROWS_ANNOTATION = "Throws(Throwable::class)"

MODIFIERS = (
    "public|internal|private|protected|open|abstract|final|data|sealed|enum|annotation|inline|"
    "value|const|lateinit|suspend|operator|infix|tailrec|external|expect|actual|override|inner|"
    "companion|noinline|crossinline|vararg"
)
ANNOTATION = r"@(?:\w+:)?[\w.]+(?:\s*\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\))?"
HEAD = re.compile(
    r"[ \t]*(?P<annotations>(?:" + ANNOTATION + r"\s+)*)"
    r"(?P<modifiers>(?:(?:" + MODIFIERS + r")\s+)*)"
    r"(?P<kind>fun\s+interface|class|interface|object|fun|constructor|val|var|typealias|init)\b"
)
ANNOTATIONS_ONLY = re.compile(r"[ \t]*(?:" + ANNOTATION + r"\s*)+$")
VISIBILITIES = {"public", "internal", "private", "protected"}


@dataclass
class Container:
    """An open brace: a type's body (with its name and whether it is API) or any other block."""

    is_type: bool
    name: str = ""
    is_api: bool = False


@dataclass
class Scan:
    """What one file declares."""

    signatures: List[str] = field(default_factory=list)
    unthrowing: List[str] = field(default_factory=list)


def matching_close(code: str, start: int) -> int:
    """The index just past the bracket closing the one at [start], skipping `->`."""
    pairs = {"(": ")", "[": "]", "{": "}", "<": ">"}
    stack = [pairs[code[start]]]
    index = start + 1
    while index < len(code) and stack:
        char = code[index]
        if char == "-" and code.startswith("->", index):
            index += 2
            continue
        if char in pairs and (char != "<" or stack[-1] == ">"):
            stack.append(pairs[char])
        elif char == stack[-1]:
            stack.pop()
        elif char in ")]}" and ">" in stack:
            # A `>` that was a comparison, not a type argument's close.
            while stack and stack[-1] == ">":
                stack.pop()
            if stack and char == stack[-1]:
                stack.pop()
        index += 1
    return index


def split_top_level(text: str) -> List[str]:
    """[text] split at the commas outside any bracket."""
    parts, depth, current, index = [], 0, [], 0
    while index < len(text):
        char = text[index]
        if text.startswith("->", index):
            current.append("->")
            index += 2
            continue
        if char in "([{<":
            depth += 1
        elif char in ")]}>":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
        index += 1
    parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def normalise(text: str) -> str:
    """[text] on one line, with the spacing ktfmt would give it."""
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(r"\s*([,?)\]>])", r"\1", text)
    text = re.sub(r"([(\[<])\s*", r"\1", text)
    return text.replace(",", ", ").replace(",  ", ", ")


def render_parameters(text: str) -> str:
    """The parameter list between a declaration's parentheses, as the record shows it."""
    rendered = []
    for parameter in split_top_level(text):
        parameter = re.sub(r"^(?:" + ANNOTATION + r"\s+)*", "", parameter)
        words = re.match(r"((?:(?:" + MODIFIERS + r"|val|var)\s+)*)(\w+)\s*:(.*)", parameter, re.S)
        if not words:
            rendered.append(normalise(parameter))
            continue
        modifiers, name, rest = words.groups()
        type_text, default = rest, None
        depth = 0
        for index, char in enumerate(rest):
            if rest.startswith("->", index):
                continue
            if char in "([{<":
                depth += 1
            elif char in ")]}>" and not rest.startswith("->", index - 1):
                depth -= 1
            elif char == "=" and depth == 0:
                type_text, default = rest[:index], rest[index + 1 :]
                break
        prefix = "vararg " if "vararg" in modifiers.split() else ""
        line = f"{prefix}{name}: {normalise(type_text)}"
        rendered.append(line + (" = …" if default is not None else ""))
    return ", ".join(rendered)


def annotation_names(text: str) -> List[str]:
    """The simple names of the annotations in [text], with their arguments, spacing removed."""
    return [re.sub(r"\s+", "", match.group(0)[1:]) for match in re.finditer(ANNOTATION, text)]


def is_excluded(annotations: Sequence[str], markers: Sequence[str]) -> bool:
    """Whether one of [annotations] is an excluded opt-in marker."""
    return any(annotation.split("(")[0].split(".")[-1] in markers for annotation in annotations)


def declaration_end(code: str, index: int) -> int:
    """Where the head of a type declaration starting its supertypes at [index] ends: its body's `{`,
    or the end of its last line."""
    while index < len(code):
        char = code[index]
        if char == "{":
            return index
        if char in "(<[":
            index = matching_close(code, index)
            continue
        if char == "\n":
            rest = code[index:].lstrip()
            line = code[:index].rstrip()
            if not (line.endswith((",", ":", "(")) or rest.startswith((":", ",", "where "))):
                return index
        index += 1
    return index


def scan_file(code: str, package: str, source_set: str, markers: Sequence[str]) -> Scan:
    """Every public callable of a public package in one stripped file."""
    scan = Scan()
    suffix = "" if source_set == "commonMain" else f"  // {source_set}"
    stack: List[Container] = []
    type_opens: Dict[int, Container] = {}
    pending_annotations: List[str] = []
    index, length = 0, len(code)

    def in_api_scope() -> Tuple[bool, str]:
        """Whether declarations here can be API, and the dotted name of their type, if any."""
        names = []
        for container in stack:
            if not container.is_type or not container.is_api:
                return False, ""
            names.append(container.name)
        return True, ".".join(names)

    def record(kind: str, modifiers: List[str], annotations: List[str], text: str, is_api: bool):
        if not is_api:
            return
        line = f"{package}: {text}{suffix}"
        scan.signatures.append(line)
        if kind == "fun" and "suspend" in modifiers and "override" not in modifiers:
            if not any(annotation == THROWS_ANNOTATION for annotation in annotations):
                scan.unthrowing.append(line)

    while index < length:
        at_line_start = index == 0 or code[index - 1] == "\n"
        if at_line_start and all(container.is_type for container in stack):
            line_end = code.find("\n", index)
            line_end = length if line_end == -1 else line_end
            line = code[index:line_end]
            if ANNOTATIONS_ONLY.match(line):
                pending_annotations += annotation_names(line)
                index = line_end
                continue
            head = HEAD.match(code, index)
            if head:
                annotations = pending_annotations + annotation_names(head.group("annotations"))
                modifiers = head.group("modifiers").split()
                kind = re.sub(r"\s+", " ", head.group("kind"))
                visible, container_name = in_api_scope()
                is_api = (
                    visible
                    and not (VISIBILITIES - {"public", "protected"}) & set(modifiers)
                    and "actual" not in modifiers
                    and not is_excluded(annotations, markers)
                )
                position = head.end()
                prefix = f"{container_name}." if container_name else ""
                if kind in ("class", "interface", "object", "fun interface"):
                    name_match = re.compile(r"\s*(\w+)?").match(code, position)
                    name = name_match.group(1) or "Companion"
                    position = name_match.end()
                    type_parameters = re.compile(r"\s*<").match(code, position)
                    if type_parameters:
                        position = matching_close(code, type_parameters.end() - 1)
                    constructor = re.compile(
                        r"\s*((?:" + ANNOTATION + r"\s+)*)((?:public|internal|private|protected)\s+)?"
                        r"(?:constructor\s*)?\("
                    ).match(code, position)
                    if constructor and kind == "class":
                        close = matching_close(code, constructor.end() - 1)
                        visibility = (constructor.group(2) or "public").strip()
                        record(
                            "constructor",
                            [],
                            [],
                            f"constructor {prefix}{name}("
                            + render_parameters(code[constructor.end() : close - 1])
                            + ")",
                            is_api and visibility in ("public", "protected"),
                        )
                        position = close
                    end = declaration_end(code, position)
                    if end < length and code[end] == "{":
                        type_opens[end] = Container(is_type=True, name=name, is_api=is_api)
                    pending_annotations = []
                    index = end
                    continue
                if kind in ("fun", "constructor"):
                    position_after = position
                    type_parameter_text = ""
                    type_parameters = re.compile(r"\s*<").match(code, position)
                    if kind == "fun" and type_parameters:
                        close = matching_close(code, type_parameters.end() - 1)
                        type_parameter_text = normalise(code[type_parameters.end() - 1 : close]) + " "
                        position_after = close
                    open_paren = position_after
                    while open_paren < length and code[open_paren] != "(":
                        if code[open_paren] in "<":
                            open_paren = matching_close(code, open_paren)
                            continue
                        open_paren += 1
                    name_text = normalise(code[position_after:open_paren])
                    close = matching_close(code, open_paren)
                    parameters = render_parameters(code[open_paren + 1 : close - 1])
                    if kind == "constructor":
                        text = f"constructor {container_name}({parameters})"
                    else:
                        receiver, _, name = name_text.rpartition(".")
                        if receiver:
                            name_text = f"({receiver}).{name}"
                        shown = [modifier for modifier in modifiers if modifier == "suspend"]
                        text = " ".join(shown + ["fun"]) + f" {type_parameter_text}{prefix}{name_text}({parameters})"
                    record(kind, modifiers, annotations, text, is_api)
                    pending_annotations = []
                    index = close
                    continue
                pending_annotations = []
            elif line.strip():
                pending_annotations = []
        char = code[index]
        if char == "{":
            stack.append(type_opens.pop(index, Container(is_type=False)))
        elif char == "}" and stack:
            stack.pop()
        index += 1
    return scan


def exclusion_markers(root: str) -> List[str]:
    """The simple names of the opt-in markers compat/abi-exclusions.txt lists."""
    with open(os.path.join(root, EXCLUSIONS_FILE), encoding="utf-8") as handle:
        return [
            line.split()[1].split(".")[-1]
            for line in handle
            if line.strip() and not line.startswith("#") and line.startswith("annotation ")
        ]


def scan_module(root: str, module: str, markers: Sequence[str]) -> Tuple[int, Scan]:
    """How many files one module's main source sets hold, and what they declare."""
    total, files = Scan(), 0
    source_root = os.path.join(root, module, "src")
    if not os.path.isdir(source_root):
        return 0, total
    for source_set in sorted(os.listdir(source_root)):
        if not source_set.endswith("Main"):
            continue
        for directory, _, names in sorted(os.walk(os.path.join(source_root, source_set))):
            for name in sorted(names):
                if not name.endswith(".kt"):
                    continue
                files += 1
                with open(os.path.join(directory, name), encoding="utf-8") as handle:
                    code = strip_comments_and_literals(handle.read())
                package_match = re.search(r"^\s*package\s+([\w.]+)", code, re.M)
                package = package_match.group(1) if package_match else ""
                if ".internal" in f".{package}." or package.endswith(".internal"):
                    continue
                scan = scan_file(code, package, source_set, markers)
                total.signatures += scan.signatures
                total.unthrowing += scan.unthrowing
    total.signatures.sort()
    return files, total


def record_path(root: str, module: str) -> str:
    return os.path.join(root, module, "api", f"{module}.signatures")


def render_record(module: str, signatures: Sequence[str]) -> str:
    header = (
        f"// Public functions and constructors of {module}, with their parameter names.\n"
        "// Written by scripts/check-public-signatures.py --update; checked in CI.\n"
    )
    return header + "".join(f"{line}\n" for line in signatures)


def run(root: str, update: bool) -> int:
    markers = exclusion_markers(root)
    scanned, problems = 0, []
    for module in MODULES:
        files, scan = scan_module(root, module, markers)
        scanned += files
        problems += [f"{line}: a public suspend function needs @{THROWS_ANNOTATION}" for line in scan.unthrowing]
        expected = render_record(module, scan.signatures)
        path = record_path(root, module)
        if update:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(expected)
            continue
        current = open(path, encoding="utf-8").read() if os.path.exists(path) else None
        if current != expected:
            before = set((current or "").splitlines())
            after = set(expected.splitlines())
            for line in sorted(before - after):
                problems.append(f"{os.path.relpath(path, root)}: recorded, not declared: {line}")
            for line in sorted(after - before):
                problems.append(f"{os.path.relpath(path, root)}: declared, not recorded: {line}")
            problems.append(f"{os.path.relpath(path, root)} is stale: run scripts/check-public-signatures.py --update and commit it")
    if scanned == 0:
        print("No main source file found to scan.", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"::error::{problem}")
    print(f"Scanned {scanned} main-source files: {len(problems)} problem(s).")
    return 1 if problems else 0


SELF_TEST_SOURCE = """
package io.example.probe

import kotlin.time.Duration

/** A probe. */
@SubclassOptInRequired(Gate::class)
public interface Probe {
    public fun from(table: String): Builder

    public fun from(schema: String, table: String): Builder

    @Throws(Throwable::class)
    public suspend fun send(body: String, retries: Int = 3, request: Request.() -> Unit = {}): Int

    public suspend fun unannotated(id: Long)

    override suspend fun close()

    internal fun hidden(secret: String)

    @Gate public fun gated(value: Int)

    public class Config internal constructor() {
        public var size: Long = 1

        public fun primaryKey(table: String, vararg columns: String) {
            fun local(ignored: Int) = Unit
        }
    }
}

public class Strategy(public val maxAge: Duration? = null) : Probe.Base {
    override fun equals(other: Any?): Boolean = other is Strategy

    private fun helper(x: Int) {}
}

public data class Value
internal constructor(public val id: Long, public val name: String)

@Throws(Throwable::class)
public suspend inline fun <reified T : Any> Probe.typed(
    function: String,
    parameters: T,
    noinline request: Request.() -> Unit = {},
): Int = 0

private fun notApi(a: Int) {}

internal class Hidden {
    public fun inside(b: Int) {}
}
"""

SELF_TEST_EXPECTED = [
    "io.example.probe: constructor Strategy(maxAge: Duration? = …)",
    "io.example.probe: fun (Probe).typed(function: String, parameters: T, request: Request.() -> Unit = …)",
    "io.example.probe: fun Probe.Config.primaryKey(table: String, vararg columns: String)",
    "io.example.probe: fun Probe.from(schema: String, table: String)",
    "io.example.probe: fun Probe.from(table: String)",
    "io.example.probe: fun Strategy.equals(other: Any?)",
    "io.example.probe: suspend fun Probe.close()",
    "io.example.probe: suspend fun Probe.send(body: String, retries: Int = …, request: Request.() -> Unit = …)",
    "io.example.probe: suspend fun Probe.unannotated(id: Long)",
]


def self_test() -> int:
    failures = []
    scan = scan_file(strip_comments_and_literals(SELF_TEST_SOURCE), "io.example.probe", "commonMain", ["Gate"])
    # The typed extension's suspend and type parameters are part of its line.
    expected = sorted(
        line.replace("fun (Probe).typed", "suspend fun <reified T : Any> (Probe).typed")
        for line in SELF_TEST_EXPECTED
    )
    if sorted(scan.signatures) != expected:
        failures.append("parsed:\n  " + "\n  ".join(sorted(scan.signatures)) + "\nexpected:\n  " + "\n  ".join(expected))
    if scan.unthrowing != ["io.example.probe: suspend fun Probe.unannotated(id: Long)"]:
        failures.append(f"the @Throws rule flagged {scan.unthrowing}, not exactly the unannotated one")
    with tempfile.TemporaryDirectory() as root:
        os.makedirs(os.path.join(root, "compat"))
        with open(os.path.join(root, EXCLUSIONS_FILE), "w", encoding="utf-8") as handle:
            handle.write("annotation io.example.probe.Gate\n")
        if run(root, update=False) != 2:
            failures.append("an empty tree did not fail with status 2")
        directory = os.path.join(root, MODULES[0], "src", "commonMain", "kotlin")
        os.makedirs(directory)
        source = SELF_TEST_SOURCE.replace("public suspend fun unannotated", "@Throws(Throwable::class) public suspend fun unannotated")
        with open(os.path.join(directory, "Probe.kt"), "w", encoding="utf-8") as handle:
            handle.write(source)
        if run(root, update=True) != 0 or run(root, update=False) != 0:
            failures.append("a tree with its record just written did not pass")
        with open(os.path.join(directory, "Probe.kt"), "w", encoding="utf-8") as handle:
            handle.write(source.replace("retries: Int = 3", "attempts: Int = 3"))
        if run(root, update=False) != 1:
            failures.append("a renamed parameter did not fail the check")
        with open(os.path.join(directory, "Probe.kt"), "w", encoding="utf-8") as handle:
            handle.write(source.replace("from(schema: String, table: String)", "from(table: String, schema: String)"))
        if run(root, update=False) != 1:
            failures.append("reordered parameters did not fail the check")
    for failure in failures:
        print(f"::error::self-test: {failure}")
    print(f"Self-test: {'failed' if failures else 'passed'}.")
    return 1 if failures else 0


if __name__ == "__main__":
    arguments = sys.argv[1:]
    if arguments == ["--self-test"]:
        sys.exit(self_test())
    if arguments in ([], ["--update"]):
        sys.exit(run(REPO_ROOT, update=arguments == ["--update"]))
    print(__doc__, file=sys.stderr)
    sys.exit(2)
