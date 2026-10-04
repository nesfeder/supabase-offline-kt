#!/usr/bin/env python3
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""Proves the Swift package's public API shows nothing of the core framework underneath it.

Usage (from the repository root, after the package is built for the simulator):
  check-swift-public-api.py --products DIR   DIR is xcodebuild's Build/Products/Debug-iphonesimulator
  check-swift-public-api.py --self-test      prove the check catches each kind of leak (no Xcode)

It fails when:
  1. the public symbol graph of SupabaseOffline or SupabaseOfflineTesting (swift-symbolgraph-extract
     at the public access level, extension graphs included) holds, in any name, declaration or
     documentation comment, a core type (the `SOC` prefix, `Bridge`), the core module's name, or a
     word naming the runtime underneath (Kotlin, KMP, Konan, kfun);
  2. a consumer that imports only the two products can name a core type: a probe naming
     BridgeClient must fail to compile with "cannot find type", while a probe using the public API
     must compile (so the first fails for that reason alone).
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
TARGET = "arm64-apple-ios16.0-simulator"
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
    let options = SupabaseOfflineClient.Options(accessToken: { nil }, cacheLimit: .unlimited)
    let client = SupabaseOfflineClient.inMemory(options: options, server: FakeServer { _ in .json("[]") })
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


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, capture_output=True, text=True, check=False)


def simulator_sdk() -> str:
    result = run(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"])
    if result.returncode != 0:
        sys.exit(f"xcrun could not find the simulator SDK: {result.stderr.strip()}")
    return result.stdout.strip()


def extract_symbol_graphs(products: Path, output: Path, sdk: str) -> list[str]:
    problems = []
    for module in MODULES:
        result = run([
            "xcrun", "swift-symbolgraph-extract", "-module-name", module, "-target", TARGET,
            "-sdk", sdk, "-I", str(products), "-F", str(products),
            "-minimum-access-level", "public", "-output-dir", str(output),
        ])
        if result.returncode != 0:
            problems.append(f"swift-symbolgraph-extract failed for {module}: {result.stderr.strip()}")
    return problems


def typecheck(products: Path, sdk: str, source: str, scratch: Path, name: str) -> subprocess.CompletedProcess[str]:
    probe = scratch / f"{name}.swift"
    probe.write_text(source, encoding="utf-8")
    return run([
        "xcrun", "swiftc", "-typecheck", "-swift-version", "6", "-target", TARGET, "-sdk", sdk,
        "-I", str(products), "-F", str(products), str(probe),
    ])


def check_consumer(products: Path, sdk: str, scratch: Path) -> list[str]:
    problems = []
    clean = typecheck(products, sdk, CLEAN_PROBE, scratch, "CleanConsumer")
    if clean.returncode != 0:
        problems.append(f"a consumer of the public API does not compile:\n{clean.stderr.strip()}")
    leak = typecheck(products, sdk, LEAK_PROBE, scratch, "LeakingConsumer")
    if leak.returncode == 0:
        problems.append("a consumer importing only the products can name BridgeClient")
    elif "cannot find type 'BridgeClient' in scope" not in leak.stderr:
        problems.append(f"the leak probe failed for another reason:\n{leak.stderr.strip()}")
    return problems


def check(products: Path) -> int:
    sdk = simulator_sdk()
    with tempfile.TemporaryDirectory(prefix="swift-public-api-") as temporary:
        scratch = Path(temporary)
        graphs = scratch / "graphs"
        graphs.mkdir()
        problems = extract_symbol_graphs(products, graphs, sdk)
        leaks, symbols = check_symbol_graphs(graphs)
        problems += leaks
        problems += check_consumer(products, sdk, scratch)
        graph_count = len(list(graphs.glob("*.symbols.json")))
    if problems:
        print("The Swift package's public API shows the core underneath it:")
        for problem in problems:
            print(f"  {problem}")
        return 1
    print(f"Public API of {', '.join(MODULES)}: {symbols} symbols in {graph_count} symbol graphs, none naming "
          "the core or its runtime; a consumer cannot name BridgeClient.")
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
    total = len(cases) + 1
    print(f"Self-test: {total - failures}/{total} passed")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--products", type=Path, help="xcodebuild's Build/Products/Debug-iphonesimulator")
    group.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args()
    if arguments.self_test:
        return self_test()
    return check(arguments.products)


if __name__ == "__main__":
    sys.exit(main())
