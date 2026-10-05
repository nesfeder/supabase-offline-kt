#!/usr/bin/env python3
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""Proves the Swift package's public API shows nothing of the core framework underneath it, and
holds it to its checked-in record.

Usage (from the repository root, after the package is built for the simulator):
  check-swift-public-api.py --products DIR            DIR is xcodebuild's Build/Products/Debug-iphonesimulator
  check-swift-public-api.py --products DIR --update   rewrite swift/api/*.api from that build
  check-swift-public-api.py --docc ARCHIVE...         lint built documentation archives (.doccarchive)
  check-swift-public-api.py --self-test               prove each check catches what it is for (no Xcode)

--products fails when:
  1. the public symbol graph of SupabaseOffline or SupabaseOfflineTesting (swift-symbolgraph-extract
     at the public access level, extension graphs included) holds, in any name, declaration or
     documentation comment, a core type (the `SOC` prefix, `Bridge`), the core module's name, or a
     word naming the runtime underneath (Kotlin, KMP, Konan, kfun);
  2. a consumer that imports only the two products can name a core type: a probe naming
     BridgeClient must fail to compile with "cannot find type", while a probe using the public API
     must compile (so the first fails for that reason alone);
  3. the public interface differs from its record, swift/api/<module>.api.

--docc fails when a documentation archive's JSON (its pages, references and navigator index) holds
any of the words of 1.

The record is made from the symbol graph rather than an emitted .swiftinterface: the graph's
declarations are a versioned data format that DocC renders and that is extracted from the same
debug build the tests run, while a module interface needs a separate library-evolution build and
its text carries compiler-version guards (`#if compiler(>=5.3) && $Feature`) and attributes that move
with the toolchain. One line per declaration, `<kind> <path> | <declaration>`, followed by
` | where <constraints>` for a member of a constrained extension and ` | @available(<…>)` for a
declaration with availability of its own, sorted; a protocol's requirements are kind `requirement`;
a conformance is `conformance <type> | <protocol>`; the first line is the package's platform, read
from swift/Package.swift. A removed or changed line is a
breaking change and an added one an addition (scripts/api-compat.py rates them).
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

MODULES = ("SupabaseOffline", "SupabaseOfflineTesting")
ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "swift" / "Package.swift"
RECORDS = ROOT / "swift" / "api"
RECORD_HEADER = (
    "// The public interface of {module}, from its symbol graph; scripts/api-compat.py rates a\n"
    "// change to it. Do not edit: scripts/check-swift-public-api.py --products DIR --update writes it.\n"
)
# Conformances a compiler infers (every type, or every Sendable one) and may or may not print.
IMPLICIT_PROTOCOLS = {"Swift.Copyable", "Swift.Escapable", "Swift.BitwiseCopyable", "Swift.SendableMetatype"}
# `SOC` and `Bridge` are type prefixes, so they match in their own case; the rest in any case.
LEAK_CASE_SENSITIVE = re.compile(r"\bSOC[A-Z]|Bridge")
LEAK_ANY_CASE = re.compile(r"supabaseofflinecore|kotlin|\bkmp\b|konan|kfun", re.IGNORECASE)

LEAK_PROBE = """import SupabaseOffline
import SupabaseOfflineTesting

let core: BridgeClient? = nil
"""

CLEAN_PROBE = """import Foundation
import SupabaseOffline
import SupabaseOfflineTesting

func probe() async throws {
    let config = SupabaseOfflineClient.Config(accessToken: { nil }, cacheSize: .unlimited, autoSync: false)
    let client = SupabaseOfflineClient.inMemory(config: config, server: FakeServer { _ in .json("[]") })
    let change: OfflineJSON = ["notes": nil]
    _ = change
    do {
        try await client.close()
    } catch OfflineError.clientClosed {
    }
}
"""


def leaks_in(value: object, where: str) -> list[str]:
    """Every string inside `value` that names the core or the runtime, with its JSON path."""
    found: list[str] = []
    if isinstance(value, dict):
        for key, item in value.items():
            found += leaks_in(item, f"{where}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            found += leaks_in(item, f"{where}[{index}]")
    elif isinstance(value, str):
        if LEAK_CASE_SENSITIVE.search(value) or LEAK_ANY_CASE.search(value):
            found.append(f"{where}: {value[:160]}")
    return found


def check_symbol_graphs(directory: Path) -> tuple[list[str], int]:
    """The leaks in every symbol graph in `directory`, and how many public symbols they hold."""
    problems: list[str] = []
    symbols = 0
    graphs = sorted(directory.glob("*.symbols.json"))
    if not graphs:
        return [f"no symbol graph in {directory}"], 0
    for graph in graphs:
        content = json.loads(graph.read_text(encoding="utf-8"))
        symbols += len(content.get("symbols", []))
        problems += [f"{graph.name}: {leak}" for leak in leaks_in(content, "$")]
    return problems, symbols


def ios_minimum(manifest_text: str) -> str:
    """The iOS version the manifest's `platforms:` names, such as `16.0`."""
    match = re.search(r"platforms:\s*\[[^\]]*\.iOS\(\s*(?:\.v(\d+)(?:_(\d+))?|\"(\d+(?:\.\d+)?)\")\s*\)", manifest_text)
    if not match:
        sys.exit("swift/Package.swift names no iOS platform this check can read (`platforms: [.iOS(.v16)]`)")
    if match.group(3):
        version = match.group(3)
        return version if "." in version else f"{version}.0"
    return f"{match.group(1)}.{match.group(2) or 0}"


def declaration(symbol: dict) -> str:
    return "".join(fragment["spelling"] for fragment in symbol.get("declarationFragments", []))


def constraint_text(constraint: dict) -> str:
    """One generic constraint as Swift writes it: `Element: OfflineFilterValue`, `Value == String`."""
    operator = {"conformance": ":", "superclass": ":", "sameType": " =="}.get(constraint.get("kind"), " ?")
    return f"{constraint.get('lhs')}{operator} {constraint.get('rhs')}"


def version_text(version: dict) -> str:
    """A symbol graph version as Swift writes it: `17.0`, or `17.0.1` when it has a patch."""
    parts = [version.get("major", 0), version.get("minor", 0)] + ([version["patch"]] if version.get("patch") else [])
    return ".".join(str(part) for part in parts)


def availability_text(entries: list) -> str:
    """A symbol's own availability as `@available(…)`, one clause per domain; empty when it has none."""
    clauses = []
    for entry in entries or []:
        words = [entry.get("domain", "*")]
        if "introduced" in entry:
            words.append(version_text(entry["introduced"]))
        for key in ("deprecated", "obsoleted"):
            if key in entry:
                words.append(f"{key} {version_text(entry[key])}")
        if entry.get("isUnconditionallyDeprecated"):
            words.append("deprecated")
        if entry.get("isUnconditionallyUnavailable"):
            words.append("unavailable")
        if entry.get("renamed"):
            words.append(f"renamed {entry['renamed']}")
        clauses.append(" ".join(words))
    return f"@available({', '.join(sorted(clauses))})" if clauses else ""


def symbol_line(kind: str, name: str, symbol: dict) -> str:
    """A declaration's record line: its declaration, its extension's constraints, its availability."""
    line = f"{kind} {name} | {declaration(symbol)}"
    constraints = (symbol.get("swiftExtension") or {}).get("constraints") or []
    if constraints:
        line += " | where " + ", ".join(constraint_text(constraint) for constraint in constraints)
    availability = availability_text(symbol.get("availability"))
    if availability:
        line += f" | {availability}"
    return line


def interface_record(graphs: list[dict], module: str, platform: str) -> str:
    """The record of one module's public interface, from its symbol graphs (its own and its extensions')."""
    symbols = {}
    names = {}
    for content in graphs:
        for symbol in content.get("symbols", []):
            precise = symbol["identifier"]["precise"]
            if "::SYNTHESIZED::" in precise:
                continue
            symbols[precise] = symbol
            names[precise] = ".".join(symbol["pathComponents"])
        for relationship in content.get("relationships", []):
            if relationship.get("targetFallback"):
                names.setdefault(relationship["target"], relationship["targetFallback"])
    requirements = set()
    lines = set()
    for content in graphs:
        for relationship in content.get("relationships", []):
            source, target, kind = relationship["source"], relationship["target"], relationship["kind"]
            if kind in ("requirementOf", "optionalRequirementOf"):
                requirements.add(source)
            if kind not in ("conformsTo", "inheritsFrom") or (source not in symbols and target not in symbols):
                continue
            if "::SYNTHESIZED::" in source or names.get(target) in IMPLICIT_PROTOCOLS:
                continue
            constraints = ", ".join(
                constraint_text(constraint) for constraint in relationship.get("swiftConstraints", [])
            )
            where = f" where {constraints}" if constraints else ""
            word = "conformance" if kind == "conformsTo" else "inheritance"
            lines.add(f"{word} {names.get(source, source)} | {names.get(target, target)}{where}")
    for precise, symbol in symbols.items():
        kind = "requirement" if precise in requirements else symbol["kind"]["identifier"].removeprefix("swift.")
        lines.add(symbol_line(kind, names[precise], symbol))
    def order(line: str):
        word, rest = line.split(" ", 1)
        return (word in ("conformance", "inheritance"), rest, word)
    body = "\n".join(sorted(lines, key=order))
    return RECORD_HEADER.format(module=module) + f"platform iOS {platform}\n" + body + "\n"


def record_difference(expected: str, recorded: str, name: str) -> list[str]:
    """What the build's interface has that the record lacks, and the reverse."""
    built, kept = set(expected.splitlines()), set(recorded.splitlines())
    return ([f"{name}: not in the record: {line}" for line in sorted(built - kept)]
            + [f"{name}: in the record, not in the build: {line}" for line in sorted(kept - built)])


def check_records(graphs: Path, platform: str, update: bool) -> tuple[list[str], int]:
    """Compares, or with `update` rewrites, every module's record; returns problems and line count."""
    problems = []
    line_count = 0
    for module in MODULES:
        paths = sorted(graphs.glob(f"{module}.symbols.json")) + sorted(graphs.glob(f"{module}@*.symbols.json"))
        contents = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
        if not contents:
            problems.append(f"no symbol graph for {module}")
            continue
        record = interface_record(contents, module, platform)
        line_count += record.count("\n") - RECORD_HEADER.count("\n")
        path = RECORDS / f"{module}.api"
        if update:
            RECORDS.mkdir(parents=True, exist_ok=True)
            path.write_text(record, encoding="utf-8")
        elif not path.is_file():
            problems.append(f"{path.relative_to(ROOT)} is missing")
        else:
            problems += record_difference(record, path.read_text(encoding="utf-8"), path.relative_to(ROOT).as_posix())
    return problems, line_count


def docc_leaks(archive: Path) -> tuple[list[str], int]:
    """Every string in an archive's JSON that names the core or its runtime, and how many files it read."""
    files = sorted(path for path in archive.rglob("*.json") if path.is_file())
    found = []
    for path in files:
        content = json.loads(path.read_text(encoding="utf-8"))
        found += [f"{path.relative_to(archive)}: {leak}" for leak in leaks_in(content, "$")]
    return found, len(files)


def check_docc(archives: list[Path]) -> int:
    problems = []
    counts = []
    for archive in archives:
        leaks, count = docc_leaks(archive)
        if not count:
            problems.append(f"{archive} holds no JSON")
        problems += [f"{archive.name}/{leak}" for leak in leaks]
        counts.append(f"{archive.name} ({count} JSON files)")
    if problems:
        print("The built documentation shows the core underneath the package:")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print(f"Documentation {', '.join(counts)}: none names the core or its runtime.")
    return 0


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, capture_output=True, text=True, check=False)


def simulator_sdk() -> str:
    result = run(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"])
    if result.returncode != 0:
        sys.exit(f"xcrun could not find the simulator SDK: {result.stderr.strip()}")
    return result.stdout.strip()


def extract_symbol_graphs(products: Path, output: Path, sdk: str, target: str) -> list[str]:
    problems = []
    for module in MODULES:
        result = run([
            "xcrun", "swift-symbolgraph-extract", "-module-name", module, "-target", target,
            "-sdk", sdk, "-I", str(products), "-F", str(products),
            "-minimum-access-level", "public", "-output-dir", str(output),
        ])
        if result.returncode != 0:
            problems.append(f"swift-symbolgraph-extract failed for {module}: {result.stderr.strip()}")
    return problems


def typecheck(
    products: Path, sdk: str, source: str, scratch: Path, name: str, target: str
) -> subprocess.CompletedProcess[str]:
    probe = scratch / f"{name}.swift"
    probe.write_text(source, encoding="utf-8")
    return run([
        "xcrun", "swiftc", "-typecheck", "-swift-version", "6", "-target", target, "-sdk", sdk,
        "-I", str(products), "-F", str(products), str(probe),
    ])


def check_consumer(products: Path, sdk: str, scratch: Path, target: str) -> list[str]:
    problems = []
    clean = typecheck(products, sdk, CLEAN_PROBE, scratch, "CleanConsumer", target)
    if clean.returncode != 0:
        problems.append(f"a consumer of the public API does not compile:\n{clean.stderr.strip()}")
    leak = typecheck(products, sdk, LEAK_PROBE, scratch, "LeakingConsumer", target)
    if leak.returncode == 0:
        problems.append("a consumer importing only the products can name BridgeClient")
    elif "cannot find type 'BridgeClient' in scope" not in leak.stderr:
        problems.append(f"the leak probe failed for another reason:\n{leak.stderr.strip()}")
    return problems


def check(products: Path, update: bool = False) -> int:
    sdk = simulator_sdk()
    platform = ios_minimum(MANIFEST.read_text(encoding="utf-8"))
    target = f"arm64-apple-ios{platform}-simulator"
    with tempfile.TemporaryDirectory(prefix="swift-public-api-") as temporary:
        scratch = Path(temporary)
        graphs = scratch / "graphs"
        graphs.mkdir()
        problems = extract_symbol_graphs(products, graphs, sdk, target)
        leaks, symbols = check_symbol_graphs(graphs)
        problems += leaks
        problems += check_consumer(products, sdk, scratch, target)
        differences, record_lines = check_records(graphs, platform, update) if not problems else ([], 0)
        graph_count = len(list(graphs.glob("*.symbols.json")))
    if problems:
        print("The Swift package's public API shows the core underneath it:")
        for problem in problems:
            print(f"  {problem}")
        return 1
    if differences:
        print("The Swift package's public interface differs from its record (swift/api/):")
        for difference in differences:
            print(f"  {difference}")
        print("After an intended change, update the record from this build and commit it:\n"
              f"  python3 scripts/check-swift-public-api.py --products {products} --update")
        return 1
    recorded = "written to" if update else "equal to"
    print(f"Public API of {', '.join(MODULES)}: {symbols} symbols in {graph_count} symbol graphs, none naming "
          f"the core or its runtime; a consumer cannot name BridgeClient; the interface ({record_lines} lines, "
          f"iOS {platform}) is {recorded} swift/api/.")
    return 0


def graph(*symbols: dict) -> dict:
    return {"module": {"name": "SupabaseOffline"}, "symbols": list(symbols), "relationships": []}


def symbol(name: str, declaration: str = "", doc: str = "") -> dict:
    return {
        "names": {"title": name},
        "declarationFragments": [{"kind": "text", "spelling": declaration or name}],
        "docComment": {"lines": [{"text": doc}]},
    }


def self_test() -> int:
    cases = [
        ("a clean graph", graph(symbol("SupabaseOfflineClient", doc="Offline-first access."),
                                symbol("OfflineError", doc="The bridges table is fine as data.")), False),
        ("a core type in a declaration", graph(symbol("connect", "func connect() -> BridgeClient")), True),
        ("the core's prefix", graph(symbol("SOCBridgeError")), True),
        ("the core module's name", graph(symbol("x", doc="Built on SupabaseOfflineCore.")), True),
        ("the runtime in a doc comment", graph(symbol("x", doc="Runs on Kotlin coroutines.")), True),
        ("the runtime in lower case", graph(symbol("x", doc="see kotlinx.coroutines")), True),
        ("KMP as a word", graph(symbol("x", doc="A KMP library.")), True),
        ("a crash symbol", graph(symbol("x", doc="kfun:io.example")), True),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="swift-public-api-self-test-") as temporary:
        for index, (name, content, should_fail) in enumerate(cases):
            directory = Path(temporary) / f"case-{index}"
            directory.mkdir()
            (directory / "SupabaseOffline.symbols.json").write_text(json.dumps(content), encoding="utf-8")
            problems, _ = check_symbol_graphs(directory)
            if bool(problems) != should_fail:
                failures += 1
                print(f"FAIL {name}: expected {'a leak' if should_fail else 'no leak'}, got {problems}")
        empty = Path(temporary) / "empty"
        empty.mkdir()
        if not check_symbol_graphs(empty)[0]:
            failures += 1
            print("FAIL an empty directory: expected a problem")
        failures += self_test_docc(Path(temporary))
        failures += self_test_record()
    total = len(cases) + 1 + len(DOCC_CASES) + 1 + len(RECORD_CASES)
    print(f"Self-test: {total - failures}/{total} passed")
    return 1 if failures else 0


DOCC_CASES = [
    ("a clean page", {"abstract": [{"type": "text", "text": "Offline-first reads."}]}, False),
    ("the runtime in an article", {"primaryContentSections": [{"content": [{"text": "Built on Kotlin."}]}]}, True),
    ("the core module in a reference", {"references": {"x": {"title": "SupabaseOfflineCore"}}}, True),
    ("a core type in a declaration", {"declarations": [{"tokens": [{"text": "BridgeClient"}]}]}, True),
    ("a crash frame in a code listing", {"code": ["kfun:io.example.run"]}, True),
]


def self_test_docc(scratch: Path) -> int:
    """The DocC lint's cases, each in an archive of its own; returns the number that failed."""
    failures = 0
    for index, (name, page, should_fail) in enumerate(DOCC_CASES):
        archive = scratch / f"docc-{index}.doccarchive"
        (archive / "data" / "documentation").mkdir(parents=True)
        (archive / "data" / "documentation" / "page.json").write_text(json.dumps(page), encoding="utf-8")
        leaks, _ = docc_leaks(archive)
        if bool(leaks) != should_fail:
            failures += 1
            print(f"FAIL DocC {name}: expected {'a leak' if should_fail else 'no leak'}, got {leaks}")
    empty = scratch / "empty.doccarchive"
    empty.mkdir()
    if docc_leaks(empty)[1] != 0:
        failures += 1
        print("FAIL DocC an archive with no JSON: expected none read")
    return failures


def record_symbol(precise: str, kind: str, path: list[str], text: str) -> dict:
    return {"identifier": {"precise": precise}, "kind": {"identifier": f"swift.{kind}"}, "pathComponents": path,
            "declarationFragments": [{"kind": "text", "spelling": text}]}


RECORD_GRAPH = {
    "symbols": [
        record_symbol("s:Client", "class", ["Client"], "final class Client"),
        record_symbol("s:Client.close", "method", ["Client", "close()"], "func close() async throws"),
        record_symbol("s:Client.close::SYNTHESIZED::s:Other", "method", ["Other", "close()"],
                      "func close() async throws"),
        record_symbol("s:Strategy", "enum", ["Strategy"], "enum Strategy"),
        record_symbol("s:Strategy.cacheOnly", "enum.case", ["Strategy", "cacheOnly"], "case cacheOnly"),
        record_symbol("s:Builder", "protocol", ["Builder"], "protocol Builder : Sendable"),
        record_symbol("s:Builder.applying", "method", ["Builder", "applying(_:)"],
                      "func applying(_ change: Change) -> Self"),
        dict(record_symbol("s:Value.filterText", "property", ["Value", "filterText"], "var filterText: String { get }"),
             swiftExtension={"extendedModule": "Module", "constraints": [
                 {"kind": "conformance", "lhs": "Self", "rhs": "RawRepresentable"},
                 {"kind": "sameType", "lhs": "Self.RawValue", "rhs": "String"}]}),
        dict(record_symbol("s:Client.sign", "method", ["Client", "sign()"], "func sign() async throws"),
             availability=[{"domain": "iOS", "introduced": {"major": 17, "minor": 0}}]),
    ],
    "relationships": [
        {"kind": "conformsTo", "source": "s:Client", "target": "s:Sendable", "targetFallback": "Swift.Sendable"},
        {"kind": "conformsTo", "source": "s:Client", "target": "s:Copyable", "targetFallback": "Swift.Copyable"},
        {"kind": "requirementOf", "source": "s:Builder.applying", "target": "s:Builder"},
        {"kind": "conformsTo", "source": "s:Box", "target": "s:Equatable", "targetFallback": "Swift.Equatable",
         "swiftConstraints": [{"kind": "conformance", "lhs": "Value", "rhs": "Equatable"}]},
    ],
}

RECORD_CASES = [
    ("a declaration", "method Client.close() | func close() async throws"),
    ("a protocol requirement", "requirement Builder.applying(_:) | func applying(_ change: Change) -> Self"),
    ("an enum case", "enum.case Strategy.cacheOnly | case cacheOnly"),
    ("a conformance", "conformance Client | Swift.Sendable"),
    ("the platform", "platform iOS 16.0"),
    ("an extension's constraints",
     "property Value.filterText | var filterText: String { get }"
     " | where Self: RawRepresentable, Self.RawValue == String"),
    ("a declaration's availability", "method Client.sign() | func sign() async throws | @available(iOS 17.0)"),
]


def self_test_record() -> int:
    """The record's cases: what it holds and what it leaves out; returns the number that failed."""
    failures = 0
    record = interface_record([RECORD_GRAPH], "Module", ios_minimum("platforms: [.iOS(.v16)],"))
    lines = record.splitlines()
    for name, line in RECORD_CASES:
        if line not in lines:
            failures += 1
            print(f"FAIL record {name}: {line!r} not in\n{record}")
    if any("SYNTHESIZED" in line or "Other" in line or "Copyable" in line or "Box" in line for line in lines):
        failures += 1
        print(f"FAIL record: holds a synthesized copy, an implicit conformance or a foreign type's\n{record}")
    if ios_minimum('platforms: [.iOS("17.4")]') != "17.4" or ios_minimum("platforms: [.iOS(.v17_2)]") != "17.2":
        failures += 1
        print("FAIL record: the platform's other spellings")
    removed = "\n".join(line for line in lines if not line.startswith("method "))
    if not any("in the record, not in the build" in difference
               for difference in record_difference(removed, record, "Module.api")):
        failures += 1
        print("FAIL record: a removed declaration is not reported")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--products", type=Path, help="xcodebuild's Build/Products/Debug-iphonesimulator")
    group.add_argument("--docc", type=Path, nargs="+", help="built documentation archives (.doccarchive)")
    group.add_argument("--self-test", action="store_true")
    parser.add_argument("--update", action="store_true", help="with --products: rewrite swift/api/*.api")
    arguments = parser.parse_args()
    if arguments.update and not arguments.products:
        parser.error("--update needs --products")
    if arguments.self_test:
        return self_test()
    if arguments.docc:
        return check_docc(arguments.docc)
    return check(arguments.products, update=arguments.update)


if __name__ == "__main__":
    sys.exit(main())
