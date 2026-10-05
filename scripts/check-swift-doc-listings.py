#!/usr/bin/env python3
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""Keeps the Swift code the documentation shows equal to code the Swift package's tests compile.

Every ```swift block in the pages below is a copy of a marked section of a Swift source file in
swift/Tests/SupabaseOfflineTests/DocumentationListings/, which the package's test target compiles
with every warning an error (and mostly runs), or it says why it is not compiled. The comment on
the line before the block says which:

    <!-- swift-listing: GettingStartedListings.swift:client -->
    ```swift
    let offline = SupabaseOfflineClient(
    ...
    ```

    <!-- not compiled: a line of a package manifest -->
    ```swift
    .package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.1.0"))
    ```

A section is marked in its source as the docs site's snippet extension marks one, so the site's
pages include the same sections directly (`--8<-- "<listing file>:client"`):

    // --8<-- [start:client]
    ...
    // --8<-- [end:client]

and its copy is its lines with their common indentation removed. On a docs site page, a ```swift
block holds such an include line and nothing else, or is marked not compiled.

Pages checked: the Markdown of the Swift package's documentation catalogs
(swift/Sources/*/*.docc), the README's "### Swift" section, and the site's Swift pages
(SITE_PAGES).

Usage (from the repository root):
    scripts/check-swift-doc-listings.py              check
    scripts/check-swift-doc-listings.py --update     rewrite each copy from its section
    scripts/check-swift-doc-listings.py --self-test  prove the check catches each kind of drift

Standard library only; runs on the system python3 of macOS (3.9) and on CI runners. It fails with
status 2 when it finds no page or no listing: a check that saw nothing proves nothing.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
import textwrap
from pathlib import Path
from typing import Dict, List, Optional, Tuple

LISTINGS = Path("swift/Tests/SupabaseOfflineTests/DocumentationListings")
CATALOGS = Path("swift/Sources")
README = Path("README.md")
README_SECTION = "### Swift"
SITE_PAGES = (Path("docs/getting-started/swift.md"), Path("docs/help/under-the-hood.md"))

LISTING_MARK = re.compile(r"^\s*<!--\s*swift-listing:\s*([\w.+-]+\.swift):([\w-]+)\s*-->\s*$")
UNCOMPILED_MARK = re.compile(r"^\s*<!--\s*not compiled:\s*(\S.*?)\s*-->\s*$")
FENCE = re.compile(r"^(\s*)(```+|~~~+)\s*(\w*)\s*$")
SECTION_START = re.compile(r"^\s*//\s*--8<--\s*\[start:([\w-]+)\]\s*$")
SECTION_END = re.compile(r"^\s*//\s*--8<--\s*\[end:([\w-]+)\]\s*$")
INCLUDE = re.compile(r'^\s*--8<--\s*"([^"]+\.swift):([\w-]+)"\s*$')


class Problem(Exception):
    """A page or a listing the check refuses."""


def sections_of(path: Path) -> Dict[str, str]:
    """Each marked section of a listing file, dedented, by name."""
    sections: Dict[str, str] = {}
    open_name: Optional[str] = None
    lines: List[str] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        start, end = SECTION_START.match(line), SECTION_END.match(line)
        if start:
            if open_name:
                raise Problem(f"{path}:{number}: section {start.group(1)!r} starts inside {open_name!r}")
            if start.group(1) in sections:
                raise Problem(f"{path}:{number}: section {start.group(1)!r} is marked twice")
            open_name, lines = start.group(1), []
        elif end:
            if end.group(1) != open_name:
                raise Problem(f"{path}:{number}: section {end.group(1)!r} ends but {open_name!r} is open")
            sections[open_name] = textwrap.dedent("\n".join(lines)).strip("\n")
            open_name = None
        elif open_name:
            lines.append(line)
    if open_name:
        raise Problem(f"{path}: section {open_name!r} never ends")
    return sections


def all_sections(root: Path) -> Dict[str, Dict[str, str]]:
    """Every listing file's sections, by file name."""
    files = sorted((root / LISTINGS).glob("*.swift"))
    return {path.name: sections_of(path) for path in files}


def pages(root: Path) -> List[Path]:
    """The pages checked, those that exist; the README's Swift section is cut out when read."""
    found = sorted((root / CATALOGS).glob("*/*.docc/**/*.md"))
    found += [root / page for page in SITE_PAGES if (root / page).is_file()]
    if (root / README).is_file():
        found.append(root / README)
    return found


def checked_span(path: Path, lines: List[str], root: Path) -> Tuple[int, int]:
    """The line range of a page that is checked: all of it, or the README's Swift section."""
    if path != root / README:
        return 0, len(lines)
    start = next((index for index, line in enumerate(lines) if line.strip() == README_SECTION), None)
    if start is None:
        raise Problem(f"{README} has no {README_SECTION!r} section")
    level = len(README_SECTION.split()[0])
    end = next(
        (index for index in range(start + 1, len(lines))
         if re.match(r"^#{1,%d} " % level, lines[index])),
        len(lines),
    )
    return start, end


def swift_blocks(lines: List[str], span: Tuple[int, int]) -> List[Tuple[int, int, str]]:
    """Each ```swift block in the span: the index of its opening fence, of its closing fence, and
    the block's own indentation."""
    blocks = []
    index, end = span
    while index < end:
        fence = FENCE.match(lines[index])
        if fence and fence.group(3) == "swift":
            closing = next(
                (other for other in range(index + 1, len(lines))
                 if lines[other].strip() == fence.group(2)),
                None,
            )
            if closing is None:
                raise Problem(f"line {index + 1}: a swift block never closes")
            blocks.append((index, closing, fence.group(1)))
            index = closing + 1
        elif fence:
            closing = next(
                (other for other in range(index + 1, len(lines))
                 if lines[other].strip() == fence.group(2)),
                len(lines),
            )
            index = closing + 1
        else:
            index += 1
    return blocks


def mark_before(lines: List[str], opening: int) -> str:
    """The line before a block's opening fence, blank lines skipped."""
    index = opening - 1
    while index >= 0 and not lines[index].strip():
        index -= 1
    return lines[index] if index >= 0 else ""


def check_page(path: Path, root: Path, sections: Dict[str, Dict[str, str]], update: bool) -> Tuple[List[str], int]:
    """The problems of one page, and how many blocks it checked; rewrites copies when updating."""
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    relative = path.relative_to(root)
    problems: List[str] = []
    is_site_page = path in [root / page for page in SITE_PAGES]
    try:
        span = checked_span(path, lines, root)
        blocks = swift_blocks(lines, span)
    except Problem as problem:
        return [f"{relative}: {problem}"], 0
    replacements: List[Tuple[int, int, List[str]]] = []
    for opening, closing, indent in blocks:
        where = f"{relative}:{opening + 1}"
        body = [line[len(indent):] if line.startswith(indent) else line.lstrip() for line in lines[opening + 1:closing]]
        mark = mark_before(lines, opening)
        if UNCOMPILED_MARK.match(mark):
            continue
        if is_site_page:
            include = INCLUDE.match(body[0]) if len(body) == 1 else None
            if not include:
                problems.append(f"{where}: a swift block on a site page holds one --8<-- include line, "
                                "or is marked <!-- not compiled: why -->")
                continue
            file_name, section = Path(include.group(1)).name, include.group(2)
            if include.group(1) != (LISTINGS / file_name).as_posix():
                problems.append(f"{where}: include {include.group(1)} from {LISTINGS.as_posix()}/")
            elif section not in sections.get(file_name, {}):
                problems.append(f"{where}: {file_name} has no section {section!r}")
            continue
        listing = LISTING_MARK.match(mark)
        if not listing:
            problems.append(f"{where}: a swift block needs <!-- swift-listing: <file>.swift:<section> --> "
                            "or <!-- not compiled: why --> on the line before it")
            continue
        file_name, section = listing.group(1), listing.group(2)
        expected = sections.get(file_name, {}).get(section)
        if expected is None:
            problems.append(f"{where}: {LISTINGS.as_posix()}/{file_name} has no section {section!r}")
            continue
        if "\n".join(body).strip("\n") != expected:
            if update:
                replacements.append((opening, closing, [indent + line if line else line for line in expected.split("\n")]))
            else:
                problems.append(f"{where}: differs from {file_name}:{section} "
                                "(scripts/check-swift-doc-listings.py --update copies it)")
    if replacements:
        for opening, closing, new_body in sorted(replacements, reverse=True):
            lines[opening + 1:closing] = new_body
        path.write_text("\n".join(lines) + ("\n" if text.endswith("\n") else ""), encoding="utf-8")
    return problems, len(blocks)


def check(root: Path, update: bool = False) -> int:
    try:
        sections = all_sections(root)
    except Problem as problem:
        print(f"check-swift-doc-listings: {problem}")
        return 1
    checked_pages = pages(root)
    section_count = sum(len(named) for named in sections.values())
    if not checked_pages or not section_count:
        print("check-swift-doc-listings: found no page or no listing section to check")
        return 2
    problems: List[str] = []
    block_count = 0
    for page in checked_pages:
        found, count = check_page(page, root, sections, update)
        problems += found
        block_count += count
    if problems:
        print("The documentation's Swift code is not the compiled code:")
        for problem in problems:
            print(f"  {problem}")
        return 1
    verb = "updated and checked" if update else "checked"
    print(f"Swift listings {verb}: {block_count} blocks on {len(checked_pages)} pages, "
          f"against {section_count} compiled sections in {len(sections)} files.")
    return 0


# ─────────────────────────────────────────────────────────────────────────────
# Self-test
# ─────────────────────────────────────────────────────────────────────────────

LISTING_FIXTURE = """import SupabaseOffline

struct Listings {
    func read() {
        // --8<-- [start:read]
        let todos = 1
        if todos > 0 {
            print(todos)
        }
        // --8<-- [end:read]
    }
}
"""

ARTICLE_FIXTURE = """# Getting started

<!-- swift-listing: Listings.swift:read -->
```swift
let todos = 1
if todos > 0 {
    print(todos)
}
```

<!-- not compiled: a line of a package manifest -->
```swift
.package(url: "https://example.com/package", from: "1.0.0")
```

```kotlin
val unchecked = 1
```
"""

README_FIXTURE = """# Project

```swift
let outside = "the Swift section only is checked"
```

### Swift

<!-- swift-listing: Listings.swift:read -->
```swift
let todos = 1
if todos > 0 {
    print(todos)
}
```

## Documentation
"""

SITE_FIXTURE = """# iOS apps in Swift

```swift
--8<-- "swift/Tests/SupabaseOfflineTests/DocumentationListings/Listings.swift:read"
```
"""


def self_test() -> int:
    failures = 0

    def write_tree(root: Path, changes: Dict[str, Optional[str]]) -> None:
        files = {
            (LISTINGS / "Listings.swift").as_posix(): LISTING_FIXTURE,
            "swift/Sources/SupabaseOffline/SupabaseOffline.docc/GettingStarted.md": ARTICLE_FIXTURE,
            README.as_posix(): README_FIXTURE,
            SITE_PAGES[0].as_posix(): SITE_FIXTURE,
        }
        files.update(changes)
        for name, content in files.items():
            if content is None:
                continue
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(content, encoding="utf-8")

    def run(changes: Dict[str, Optional[str]], update: bool = False) -> Tuple[int, str, Path]:
        directory = Path(tempfile.mkdtemp(prefix="swift-doc-listings-"))
        write_tree(directory, changes)
        captured: List[str] = []
        original = sys.stdout

        class Capture:
            def write(self, text: str) -> None:
                captured.append(text)

            def flush(self) -> None:
                pass

        sys.stdout = Capture()  # type: ignore[assignment]
        try:
            status = check(directory, update=update)
        finally:
            sys.stdout = original
        return status, "".join(captured), directory

    article = "swift/Sources/SupabaseOffline/SupabaseOffline.docc/GettingStarted.md"
    cases = [
        ("pages equal to their sections pass", {}, 0, "4 blocks on 3 pages"),
        ("a changed line in an article", {article: ARTICLE_FIXTURE.replace("print(todos)", "print(todos + 1)")},
         1, "differs from Listings.swift:read"),
        ("a block with no mark", {article: ARTICLE_FIXTURE.replace("<!-- swift-listing: Listings.swift:read -->\n", "")},
         1, "needs <!-- swift-listing"),
        ("a section that does not exist", {article: ARTICLE_FIXTURE.replace(":read -->", ":write -->")},
         1, "has no section 'write'"),
        ("a not-compiled mark without a reason",
         {article: ARTICLE_FIXTURE.replace("not compiled: a line of a package manifest", "not compiled: ")},
         1, "needs <!-- swift-listing"),
        ("a changed line in the README's Swift section",
         {README.as_posix(): README_FIXTURE.replace("if todos > 0", "if todos > 1")}, 1, "README.md:"),
        ("a block outside the README's Swift section is not checked",
         {README.as_posix(): README_FIXTURE.replace('"the Swift section only is checked"', '"changed"')}, 0, "blocks"),
        ("a site page that copies code instead of including it",
         {SITE_PAGES[0].as_posix(): "```swift\nlet todos = 1\n```\n"}, 1, "one --8<-- include line"),
        ("a site page that includes a section that does not exist",
         {SITE_PAGES[0].as_posix(): SITE_FIXTURE.replace(":read", ":write")}, 1, "has no section 'write'"),
        ("a section that never ends",
         {(LISTINGS / "Listings.swift").as_posix(): LISTING_FIXTURE.replace("        // --8<-- [end:read]\n", "")},
         1, "never ends"),
        ("no listing at all", {(LISTINGS / "Listings.swift").as_posix(): "import SupabaseOffline\n"}, 2, "found no"),
    ]
    for name, changes, expected_status, expected_text in cases:
        status, output, _ = run(changes)
        if status != expected_status or expected_text not in output:
            failures += 1
            print(f"FAIL {name}: status {status}, output {output.strip()!r}")
    status, output, directory = run({article: ARTICLE_FIXTURE.replace("print(todos)", "print(0)")}, update=True)
    rewritten = (directory / article).read_text(encoding="utf-8")
    if status != 0 or rewritten != ARTICLE_FIXTURE:
        failures += 1
        print(f"FAIL --update rewrites a changed copy from its section: status {status}, {output.strip()!r}")
    total = len(cases) + 1
    print(f"Self-test: {total - failures}/{total} passed")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--update", action="store_true", help="rewrite each copy from its section")
    group.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args()
    if arguments.self_test:
        return self_test()
    return check(Path.cwd(), update=arguments.update)


if __name__ == "__main__":
    sys.exit(main())
