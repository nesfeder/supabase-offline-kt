#!/usr/bin/env python3
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""Keeps the project's words in American spelling. Whole-tree: every run reads every file git
tracks of the kinds below, so no base commit is needed.

It fails on a British form (behaviour, colour, licence, cancelled, labelled, recognise, centre,
catalogue, towards, whilst and the rest of BRITISH_FORMS below), matched as a whole word in any
case, a hyphen ending a word. Every -ise, -ised, -ising and -isation word counts as British but
those American English spells so too (AMERICAN_ISE_WORDS: promise, otherwise, exercise, and any
word ending in -wise). What it reads, by kind of file:

    Markdown and mkdocs.yml     the prose; fenced code blocks and inline code are left out
    Kotlin, Kotlin script,      comments, doc comments and string literals as prose (inline code
    Swift, Python               in a comment left out), and every name in the code, split into
                                its words (a_cancelled_pass, aCancelledPass, CANCELLED_PASS)
    shell, YAML, TOML, CSS,     every line, as prose and as names
    XML, SQL, JSON, text and
    properties files

In every kind, a URL of another site keeps its own spelling. A name that mirrors a platform's or
an upstream library's keeps its spelling too (`isCancelled`, GitHub Actions' `cancelled()`): ALLOWED
lists each such name, and the few places that must quote a British form, with the reason. An
allowance that no longer matches anything in the tree fails the check, so the list cannot go
stale. EXCLUDED lists the files left out, with the reason.

Usage:
    scripts/check-spelling.py               check the tree
    scripts/check-spelling.py --self-test   prove the check catches each kind of British form

It fails with status 2 when it finds no file to check: a check that saw nothing proves nothing.
Runs on the system python3 of macOS (3.9) and on CI runners; standard library only.
"""

import fnmatch
import functools
import os
import re
import subprocess
import sys
import tempfile
from typing import Iterator, List, Optional, Set, Tuple

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Words that end in -ise in American English too, so the general -ise rule leaves them alone. A
# match is allowed when its base word, with any prefix below taken off, is one of these.
AMERICAN_ISE_WORDS = frozenset(
    """
    advertise advise anise apprise arise bruise chaise chastise circumcise clockwise comprise
    compromise concise counterclockwise cruise demise despise devise disguise enterprise excise
    exercise expertise franchise guise improvise incise likewise merchandise mortise noise
    otherwise paradise poise porpoise praise precise premise promise raise reprise revise rise
    sunrise supervise surmise surprise televise tortoise treatise turquoise uprise valise vise
    wise
    """.split()
)

# What follows the "is" of an -ise word: "recognise", "recognised", "recognising", "recognisation".
ISE_SUFFIX = r"(?:e|es|ed|er|ers|ing|able|ation|ations|ational)"

# (British pattern, how to make the American form of a match): each pattern is matched as a whole
# word, ignoring case; the rewrites are applied in order to the matched text, in lower case.
BRITISH_FORMS: Tuple[Tuple[str, Tuple[Tuple[str, str], ...]], ...] = (
    (
        r"(?:behavi|col|fav|hon|neighb|lab|flav|rum|hum|harb|vap|arm|endeav|od|splend|val|vig|rig"
        r"|sav|parl|cand|clam|ferv)our\w*",
        ((r"our", "or"),),
    ),
    # Every -ise, -ised, -ising, -isation word but those is_american_ise() accepts; "practise" has
    # its own rule below, and "disable" is no -ise word at all.
    (
        r"(?!practis|disable\b)\w+is" + ISE_SUFFIX,
        ((r"is(?=" + ISE_SUFFIX + r"$)", "iz"),),
    ),
    (r"(?:analys|paralys)(?:e|ed|ing)", ((r"ys", "yz"),)),
    (
        r"(?:cancel|label|travel|model|signal|channel|level|total|fuel|marshal|tunnel|dial)l"
        r"(?:ed|ing|er|ers)",
        ((r"ll(?=ed|ing|er)", "l"),),
    ),
    (r"licence(?:s|d)?", ((r"licence", "license"),)),
    (r"centre(?:s|d)?", ((r"centred", "centered"), (r"centre", "center"))),
    (r"(?:catalogue|analogue)(?:s|d)?", ((r"ogued", "oged"), (r"ogue", "og"))),
    (r"artefacts?", ((r"artefact", "artifact"),)),
    (r"fulfil(?:s|ment|ments)?", ((r"fulfil", "fulfill"),)),
    (r"(?:enrolment|instalment|skilful|wilful)s?", ((r"(enrol|instal|skil|wil)", r"\1l"),)),
    (r"(?:judgement|acknowledgement)s?", ((r"gement", "gment"),)),
    (r"(?:towards|afterwards|backwards|onwards)", ((r"wards", "ward"),)),
    # "forwards" is also the verb ("the plugin forwards every call"): only the adverb is British.
    (
        r"forwards(?=\s*(?:$|[.,;:!?)\]]|\s(?:and|or|in|from|by|through|of)\b))",
        ((r"wards", "ward"),),
    ),
    (
        r"(?:move|moves|moved|moving|go|goes|going|step|steps|skip|skips|look|looks|scroll|scrolls)"
        r"\s+forwards(?!\s*(?:$|[.,;:!?)\]]|\s(?:and|or|in|from|by|through|of)\b))",
        ((r"wards", "ward"),),
    ),
    (r"(?:whilst|amongst)", ((r"whilst", "while"), (r"amongst", "among"))),
    (r"grey(?:s|ed|ing|ish)?", ((r"grey", "gray"),)),
    (r"(?:defence|offence|pretence)s?", ((r"ence", "ense"),)),
    (r"programme(?:s|d)?", ((r"programme", "program"),)),
    (r"practis(?:e|es|ed|ing)", ((r"practis", "practic"),)),
    (r"focuss(?:ed|ing)", ((r"focuss", "focus"),)),
    (r"learnt", ((r"learnt", "learned"),)),
    (r"(?:kilo)?(?:metre|litre)s?", ((r"tre", "ter"),)),
    (r"ageing", ((r"ageing", "aging"),)),
    (r"speciality", ((r"speciality", "specialty"),)),
)

# A prefix the British form may carry: "unlabelled", "unrecognised".
PREFIXES = ("un", "re", "mis", "pre", "non", "over", "under", "dis")
PREFIX = "(?:" + "|".join(PREFIXES) + ")?"

# A hyphen ends a word: "colour-coded" and "user-cancelled" are checked.
PATTERNS: Tuple[Tuple["re.Pattern[str]", Tuple[Tuple[str, str], ...]], ...] = tuple(
    (re.compile(r"(?<!\w)" + PREFIX + british + r"(?!\w)", re.IGNORECASE), rewrites)
    for british, rewrites in BRITISH_FORMS
)


def is_american_ise(word: str) -> bool:
    """Whether [word] is an -ise word American English spells so too ("promise", "otherwise")."""
    base = re.sub(r"is" + ISE_SUFFIX + r"$", "ise", word.lower())
    for prefix in ("",) + PREFIXES:
        if base.startswith(prefix):
            unprefixed = base[len(prefix):]
            if unprefixed in AMERICAN_ISE_WORDS or unprefixed.endswith("wise"):
                return True
    return False


def american_form(british: str, rewrites: Tuple[Tuple[str, str], ...]) -> str:
    """The American spelling of [british], capitalized as its first letter is."""
    american = british.lower()
    for pattern, replacement in rewrites:
        american = re.sub(pattern, replacement, american)
    return american[:1].upper() + american[1:] if british[:1].isupper() else american


# The framework's header carries the doc comments of the bridge's public declarations, so a
# British form in one is in the header too: its allowance goes when the header next changes.
BRIDGE = (
    "supabase-offline-swift-bridge/src/iosMain/kotlin/io/github/nesfeder/supabase/offline/bridge/"
)
IN_THE_HEADER = "in the framework's header, which a text-only change keeps byte-identical"

# (Files it applies to, as a glob over the path; the allowed text; the reason). A British form
# inside a match is allowed. Each allowance must match a form somewhere in the tree.
ALLOWED: Tuple[Tuple[str, str, str], ...] = (
    ("*", r"(?<![\w$])isCancelled(?!\w)", "kotlinx.coroutines' Job.isCancelled, Task.isCancelled"),
    ("*", r"(?<![\w$])CANCELLED(?![\w$])", "BridgeErrorKind.CANCELLED, in the framework's header"),
    (
        "*.swift",
        r"(?<=\.)cancelled(?![\w$])",
        "the Swift name of BridgeErrorKind.CANCELLED, and AsyncStream's Termination.cancelled",
    ),
    ("*", r"(?<![\w$])cancelled\(\)", "GitHub Actions' status function"),
    (".github/*", r"(['\"])cancelled\1", "a GitHub Actions job or step result"),
    (
        ".github/workflows/*",
        r"success \| failure \| cancelled \| skipped",
        "GitHub Actions' step outcomes",
    ),
    (
        "gradle.properties",
        r"(?<![\w$])POM_LICENCE_(?:NAME|URL|DIST)(?![\w$])",
        "the POM properties the publishing plugin reads",
    ),
    (BRIDGE + "BridgeCancellable.kt", r"returns: cancelling it cancels", IN_THE_HEADER),
    (BRIDGE + "BridgeClient.kt", r"every call still running afterwards ends with", IN_THE_HEADER),
    (BRIDGE + "BridgeClient.kt", r"The close itself cannot be cancelled:", IN_THE_HEADER),
)

# (Glob over the path, the reason it is not read).
EXCLUDED: Tuple[Tuple[str, str], ...] = (
    ("scripts/check-spelling.py", "names every British form it catches"),
    ("supabase-offline-swift-bridge/header/*", "generated from the bridge's sources, read above"),
    ("supabase-offline-kt/schemas/*", "Room's export of the database schema"),
    ("*.api", "generated API records"),
    ("*.signatures", "generated API records"),
    ("*.pbxproj", "Xcode's project file"),
    ("*/Package.resolved", "SwiftPM's lock file"),
)

MARKDOWN = "markdown"
KOTLIN = "kotlin"
SWIFT = "swift"
PYTHON = "python"
TEXT = "text"

SUFFIX_KINDS = {
    ".md": MARKDOWN,
    ".kt": KOTLIN,
    ".kts": KOTLIN,
    ".swift": SWIFT,
    ".py": PYTHON,
    ".sh": TEXT,
    ".yml": TEXT,
    ".yaml": TEXT,
    ".toml": TEXT,
    ".css": TEXT,
    ".xml": TEXT,
    ".sql": TEXT,
    ".json": TEXT,
    ".txt": TEXT,
    ".properties": TEXT,
    ".gradle": TEXT,
    ".xcconfig": TEXT,
    ".example": TEXT,
}

FENCE = re.compile(r"^\s*(```|~~~)")
URL = re.compile(r"<?https?://[^\s<>)\]\"'`]+>?")
OWN_URL = re.compile(
    r"github\.com/nesfeder/supabase-offline-kt|nesfeder\.github\.io/supabase-offline-kt"
)
NAME = re.compile(r"(?<![\w$])[A-Za-z_$][\w$]*")
TEMPLATE_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
INLINE_CODE = re.compile(r"`[^`\n]*`")
SWIFT_RAW_OPENING = re.compile(r"#+\"")
KOTLIN_CHARACTER = re.compile(r"'(?:\\.[^']{0,5}|[^'\\\n])'")
# Any of the patterns: a line none of them can match is skipped at once.
ANY_BRITISH = re.compile(
    "|".join("(?:" + pattern.pattern + ")" for pattern, _ in PATTERNS), re.IGNORECASE
)


def kind_of(path: str) -> Optional[str]:
    """How [path] is read, or None when it is not."""
    if any(fnmatch.fnmatch(path, glob) for glob, _ in EXCLUDED):
        return None
    if path == "mkdocs.yml":
        return MARKDOWN
    return SUFFIX_KINDS.get(os.path.splitext(path)[1])


def checked_files(root: str) -> List[str]:
    """Every file git tracks under [root] that has a kind, relative to [root]."""
    listed = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=root,
        capture_output=True,
        check=True,
    ).stdout.decode("utf-8")
    return sorted(path for path in listed.split("\0") if path and kind_of(path) is not None)


def without_other_urls(line: str) -> str:
    """[line] with every URL of another site blanked out, each character in its place."""

    def blanked(match: "re.Match[str]") -> str:
        url = match.group(0)
        return url if OWN_URL.search(url) else " " * len(url)

    return URL.sub(blanked, line)


def markdown_views(text: str) -> Tuple[str, str]:
    """The prose of Markdown [text], fenced code and inline code blanked out; no code to read."""
    lines = []
    in_fence = None
    open_ticks = 0
    for line in text.split("\n"):
        fence = FENCE.match(line)
        if in_fence is not None:
            if fence and fence.group(1) == in_fence:
                in_fence = None
            lines.append("")
            continue
        if fence:
            in_fence = fence.group(1)
            open_ticks = 0
            lines.append("")
            continue
        if not line.strip():
            open_ticks = 0
            lines.append(line)
            continue
        kept = []
        index = 0
        while index < len(line):
            if line[index] == "`":
                run = len(line[index:]) - len(line[index:].lstrip("`"))
                ticks = line[index:index + run]
                if open_ticks == 0:
                    open_ticks = run
                elif run == open_ticks:
                    open_ticks = 0
                kept.append(" " * len(ticks))
                index += run
                continue
            kept.append(" " if open_ticks else line[index])
            index += 1
        lines.append("".join(kept))
    return "\n".join(lines), ""


class Views:
    """Two copies of a source text: one keeps its prose, one its code, each blank elsewhere."""

    def __init__(self, text: str) -> None:
        self.text = text
        self.prose = [character if character == "\n" else " " for character in text]
        self.code = list(self.prose)

    def keep_prose(self, start: int, end: int) -> None:
        for index in range(start, min(end, len(self.text))):
            if self.text[index] != "\n":
                self.prose[index] = self.text[index]

    def keep_comment(self, start: int, end: int) -> None:
        """Keeps a comment's text as prose, but for its inline code."""
        self.keep_prose(start, end)
        for match in INLINE_CODE.finditer(self.text, start, end):
            for index in range(match.start(), match.end()):
                self.prose[index] = " "

    def keep_code(self, start: int, end: int) -> None:
        for index in range(start, min(end, len(self.text))):
            if self.text[index] != "\n":
                self.code[index] = self.text[index]

    def joined(self) -> Tuple[str, str]:
        return "".join(self.prose), "".join(self.code)


class CLikeLexer:
    """Splits Kotlin or Swift into prose (comments, strings, backticked names) and code."""

    def __init__(self, text: str, language: str) -> None:
        self.text = text
        self.language = language
        self.views = Views(text)

    def views_of_text(self) -> Tuple[str, str]:
        self.code_until(0, None)
        return self.views.joined()

    def code_until(self, index: int, closer: Optional[str]) -> int:
        """Reads code from [index] to the unmatched [closer], or the end; returns where it ended."""
        text = self.text
        opener = {"}": "{", ")": "("}.get(closer or "")
        depth = 0
        while index < len(text):
            character = text[index]
            if text.startswith("//", index):
                end = text.find("\n", index)
                end = len(text) if end < 0 else end
                self.views.keep_comment(index + 2, end)
                index = end
            elif text.startswith("/*", index):
                index = self.block_comment(index)
            elif self.language == SWIFT and SWIFT_RAW_OPENING.match(text, index):
                index = self.swift_raw_string(index)
            elif character == '"':
                index = self.string(index)
            elif character == "'" and self.language == KOTLIN:
                match = KOTLIN_CHARACTER.match(text, index)
                index += len(match.group(0)) if match else 1
            elif character == "`":
                end = text.find("`", index + 1)
                line_end = text.find("\n", index + 1)
                if end < 0 or 0 <= line_end < end:
                    index += 1
                else:
                    self.views.keep_prose(index + 1, end)
                    index = end + 1
            else:
                if character == opener:
                    depth += 1
                elif character == closer:
                    if depth == 0:
                        return index + 1
                    depth -= 1
                self.views.keep_code(index, index + 1)
                index += 1
        return index

    def block_comment(self, index: int) -> int:
        """Reads a block comment, nested ones included; returns where it ended."""
        text = self.text
        depth = 0
        start = index
        while index < len(text):
            if text.startswith("/*", index):
                depth += 1
                index += 2
            elif text.startswith("*/", index):
                depth -= 1
                index += 2
                if depth == 0:
                    self.views.keep_comment(start + 2, index - 2)
                    return index
            else:
                index += 1
        self.views.keep_comment(start + 2, index)
        return index

    def string(self, index: int) -> int:
        """Reads a string literal, its templates as code; returns where it ended."""
        text = self.text
        triple = text.startswith('"""', index)
        index += 3 if triple else 1
        while index < len(text):
            character = text[index]
            if triple and text.startswith('"""', index):
                while text.startswith('"', index):
                    index += 1
                return index
            if not triple and character in '"\n':
                return index + 1
            if character == "\\" and not (triple and self.language == KOTLIN):
                if self.language == SWIFT and text.startswith("\\(", index):
                    index = self.code_until(index + 2, ")")
                else:
                    index += 2
                continue
            if character == "$" and self.language == KOTLIN:
                if text.startswith("${", index):
                    index = self.code_until(index + 2, "}")
                    continue
                name = TEMPLATE_NAME.match(text, index + 1)
                if name:
                    self.views.keep_code(name.start(), name.end())
                    index = name.end()
                    continue
            self.views.keep_prose(index, index + 1)
            index += 1
        return index

    def swift_raw_string(self, index: int) -> int:
        """Reads a Swift raw string (#"…"#), its interpolations as code; returns where it ended."""
        text = self.text
        hashes = len(SWIFT_RAW_OPENING.match(text, index).group(0)) - 1
        index += hashes
        triple = text.startswith('"""', index)
        index += 3 if triple else 1
        closing = ('"""' if triple else '"') + "#" * hashes
        interpolation = "\\" + "#" * hashes + "("
        while index < len(text):
            if text.startswith(closing, index):
                return index + len(closing)
            if not triple and text[index] == "\n":
                return index + 1
            if text.startswith(interpolation, index):
                index = self.code_until(index + len(interpolation), ")")
                continue
            self.views.keep_prose(index, index + 1)
            index += 1
        return index


class PythonLexer:
    """Splits Python into prose (comments, strings) and code; an f-string's fields are code."""

    def __init__(self, text: str) -> None:
        self.text = text
        self.views = Views(text)

    def views_of_text(self) -> Tuple[str, str]:
        self.code_until(0, None)
        return self.views.joined()

    def code_until(self, index: int, closer: Optional[str]) -> int:
        """Reads code from [index] to the unmatched [closer], or the end; returns where it ended."""
        text = self.text
        depth = 0
        while index < len(text):
            character = text[index]
            if character == "#":
                end = text.find("\n", index)
                end = len(text) if end < 0 else end
                self.views.keep_comment(index + 1, end)
                index = end
            elif character in "'\"":
                index = self.string(index, self.prefix_before(index))
            else:
                if closer is not None and character == "{":
                    depth += 1
                elif closer is not None and character == "}":
                    if depth == 0:
                        return index + 1
                    depth -= 1
                self.views.keep_code(index, index + 1)
                index += 1
        return index

    def prefix_before(self, index: int) -> str:
        """The string prefix (r, b, f, rb …) just before the quote at [index], in lower case."""
        start = index
        while start > 0 and index - start < 2 and self.text[start - 1] in "rRbBuUfFtT":
            start -= 1
        if start > 0 and (self.text[start - 1].isalnum() or self.text[start - 1] == "_"):
            return ""
        return self.text[start:index].lower()

    def string(self, index: int, prefix: str) -> int:
        """Reads a string literal, an f-string's fields as code; returns where it ended."""
        text = self.text
        quote = text[index]
        delimiter = quote * 3 if text.startswith(quote * 3, index) else quote
        formatted = "f" in prefix or "t" in prefix
        index += len(delimiter)
        while index < len(text):
            if text.startswith(delimiter, index):
                return index + len(delimiter)
            character = text[index]
            if len(delimiter) == 1 and character == "\n":
                return index + 1
            if character == "\\":
                self.views.keep_prose(index + 1, index + 2)
                index += 2
                continue
            if formatted and (text.startswith("{{", index) or text.startswith("}}", index)):
                index += 2
                continue
            if formatted and character == "{":
                index = self.code_until(index + 1, "}")
                continue
            self.views.keep_prose(index, index + 1)
            index += 1
        return index


def views_of(path: str, text: str) -> Tuple[str, str]:
    """The prose and the code of [text], each character in its place and blank elsewhere."""
    kind = kind_of(path) or TEXT
    if kind == MARKDOWN:
        return markdown_views(text)
    if kind in (KOTLIN, SWIFT):
        return CLikeLexer(text, kind).views_of_text()
    if kind == PYTHON:
        return PythonLexer(text).views_of_text()
    return text, text


def words_of(name: str) -> Iterator[Tuple[int, str]]:
    """Each word of a name with its offset: a_cancelled_pass, aCancelledPass, CANCELLED_PASS."""
    for match in re.finditer(r"[A-Z]+(?![a-z])|[A-Z]?[a-z]+", name):
        yield match.start(), match.group(0)


def british_in(text: str, offset: int) -> Iterator[Tuple[int, int, str, str]]:
    """(start, end, form, American) for each British form in [text], shifted by [offset]."""
    if not ANY_BRITISH.search(text):
        return
    for pattern, rewrites in PATTERNS:
        for match in pattern.finditer(text):
            form = match.group(0)
            if not is_american_ise(form):
                start = offset + match.start()
                yield start, start + len(form), form, american_form(form, rewrites)


@functools.lru_cache(maxsize=None)
def british_word(word: str) -> Optional[Tuple[str, str]]:
    """(form, American) when the whole of [word], one word of a name, is a British form."""
    for start, end, form, american in british_in(word, 0):
        if end - start == len(word):
            return form, american
    return None


def allowed_spans(path: str, line: str) -> List[Tuple[int, int, int]]:
    """(start, end, allowance) for each allowed text on [line] of [path]."""
    spans = []
    for number, (glob, allowed, _) in enumerate(ALLOWED):
        if fnmatch.fnmatch(path, glob):
            matches = re.finditer(allowed, line)
            spans.extend((match.start(), match.end(), number) for match in matches)
    return spans


def violations_in(path: str, text: str, used: Optional[Set[int]] = None) -> List[str]:
    """One `path:line: 'form' …, American '…'` per British form in [text]; notes allowances used."""
    found = []
    prose, code = views_of(path, text)
    lines = text.split("\n")
    prose_lines = prose.split("\n")
    code_lines = code.split("\n") if code else [""] * len(lines)
    for number, (line, prose_line, code_line) in enumerate(
        zip(lines, prose_lines, code_lines), start=1
    ):
        # An underscore ends a word in prose too: a_cancelled_pass named in a comment is checked.
        prose_words = without_other_urls(prose_line).replace("_", " ")
        candidates = [
            (start, end, form, american, "")
            for start, end, form, american in british_in(prose_words, 0)
        ]
        for name in NAME.finditer(without_other_urls(code_line)):
            for offset, word in words_of(name.group(0)):
                british = british_word(word)
                if british is not None:
                    start = name.start() + offset
                    candidates.append((start, start + len(word), *british, name.group(0)))
        spans = allowed_spans(path, line) if candidates else []
        seen = set()
        for start, end, form, american, name in sorted(candidates):
            if (start, form.lower()) in seen:
                continue
            seen.add((start, form.lower()))
            allowance = next((n for low, high, n in spans if low <= start and end <= high), None)
            if allowance is not None:
                if used is not None:
                    used.add(allowance)
                continue
            where = f" in '{name}'" if name and name != form else ""
            found.append(f"{path}:{number}: '{form}'{where}, American '{american}'")
    return found


def violations(root: str) -> Tuple[int, List[str], Set[int]]:
    """How many files were read, every British form found in them, and the allowances used."""
    files = checked_files(root)
    found: List[str] = []
    used: Set[int] = set()
    for path in files:
        with open(os.path.join(root, path), encoding="utf-8") as handle:
            found.extend(violations_in(path, handle.read(), used))
    return len(files), found, used


def check(root: str) -> int:
    scanned, found, used = violations(root)
    if scanned == 0:
        print("No tracked file found to check.", file=sys.stderr)
        return 2
    for line in found:
        print(f"::error::{line}: the project's words are spelled the American way")
    stale = [allowance for number, allowance in enumerate(ALLOWED) if number not in used]
    for glob, allowed, reason in stale:
        print(
            f"::error::scripts/check-spelling.py: the allowance {allowed!r} for {glob} "
            f"({reason}) matches nothing: remove it"
        )
    print(
        f"Checked {scanned} files: {len(found)} British form(s), "
        f"{len(stale)} stale allowance(s)."
    )
    return 1 if found or stale else 0


# (name, text read as Markdown, how many British forms it holds).
SELF_TEST_CASES: Tuple[Tuple[str, str, int], ...] = (
    ("behaviour", "Reads behave as the behaviour table says.", 1),
    ("a capitalized heading", "# Behaviours", 1),
    ("colour", "The colours are set in the theme.", 1),
    ("an -ise word", "The engine serialises the row and recognises the key.", 2),
    ("an -isation word", "Normalisation happens first.", 1),
    ("analyse", "Analyse the log.", 1),
    ("cancelled", "A cancelled pass sends nothing, and a cancelling caller waits.", 2),
    ("labelled", "The issue is labelled upstream-pin.", 1),
    ("with a prefix", "An unlabelled argument and an unrecognised key.", 2),
    ("before a hyphen", "A colour-coded banner.", 1),
    ("after a hyphen", "A user-cancelled pass.", 1),
    ("an -ise word of no list", "Rows are parallelised and canonicalised.", 2),
    ("an -isation word of no list", "Containerisation and modularisation.", 2),
    ("an -our word", "The rumour and the vigour.", 2),
    (
        "-ise words American spells so",
        "A promise, otherwise precise, concise; exercise your expertise; advertise; a rising "
        "surprise, improvised, revised and supervised; pairwise noise.",
        0,
    ),
    ("licence", "MIT licence.", 1),
    ("centre", "A centred card.", 1),
    ("catalogue", "The version catalogue.", 1),
    ("artefact", "The build artefacts.", 1),
    ("fulfil", "It must fulfil the promise.", 1),
    ("judgement", "Use your judgement.", 1),
    ("acknowledgement", "An acknowledgement within seven days.", 1),
    ("towards and afterwards", "It moves towards the end, and afterwards it stops.", 2),
    ("forwards as an adverb", "It moves the mark forwards.", 1),
    ("forwards as an adverb mid-sentence", "Time moves forwards toward the end.", 1),
    ("whilst and amongst", "Whilst offline, amongst others.", 2),
    ("grey", "A grey badge.", 1),
    ("in a link label", "See [Behaviours](behaviors/index.md).", 1),
    ("in a relative link target", "See [the page](../behaviours/index.md).", 1),
    ("in this repository's URL", "See https://github.com/nesfeder/supabase-offline-kt/blob/main/docs/behaviours/x.md.", 1),
    ("American forms", "A behavior, a color, a license, canceled, labeled, toward, afterward, gray.", 0),
    ("-ise words that are American too", "Advise and revise; otherwise a promise or an exercise.", 0),
    ("forwards as a verb", "The plugin forwards every call to the client, and forwards it on.", 0),
    ("cancellation", "A cancellation is not a connectivity failure.", 0),
    ("a word that contains a form", "The specialist reads the realism of the grayscale.", 0),
    ("an identifier in inline code", "`isCancelled` and `CancellationException` keep their names.", 0),
    ("an identifier in prose", "Swift's isCancelled is checked.", 0),
    ("inline code with a backtick", "``a `behaviour` value`` is code.", 0),
    ("a fenced block", "```kotlin\nval colour = 1 // behaviour\n```", 0),
    ("an indented tilde fence", "    ~~~\n    behaviour\n    ~~~", 0),
    ("text after a fence", "```\nbehaviour\n```\nThe behaviour.", 1),
    ("another site's URL", "See https://example.com/behaviours/colour for that.", 0),
    ("inline code across a line break", "The value `is a\nbehaviour` of code.", 0),
    ("prose after code spans", "Run `a` and `b`, then the behaviour shows.", 1),
    ("prose after a span late in a line", "The site is written to `site/`. `--strict` turns the behaviour on.", 1),
    ("disable", "Disable the network, then disable it again.", 0),
)

# (name, the file it is read as, text, how many British forms it holds): the other kinds of file.
SOURCE_CASES: Tuple[Tuple[str, str, str, int], ...] = (
    ("a line comment", "P.kt", "val count = 1 // the behaviour of the count", 1),
    ("a doc comment", "P.kt", "/**\n * Recognises a key; its colour.\n */\nfun key() = 1", 2),
    ("a nested block comment", "P.kt", "/* a /* behaviour */ behaviour */ val x = 1", 2),
    ("a string literal", "P.kt", 'error("The pass was cancelled")', 1),
    ("a raw string", "P.kt", 'val text = """\n    the behaviour\n"""', 1),
    ("a template's text", "P.kt", 'val text = "${count} rows, cancelled ${name}"', 1),
    ("a name in a template", "P.kt", 'val text = "rows: $cancelledCount"', 1),
    ("an underscored test name", "P.kt", "@Test fun a_cancelled_pass_sends_nothing() {}", 1),
    ("a backticked test name", "P.kt", "@Test fun `a cancelled pass sends nothing`() {}", 1),
    ("a camel-case name", "P.kt", "val wasCancelledFirst = true", 1),
    ("a constant", "P.kt", 'const val CANCELLED_AND_CLEAN = "clean"', 1),
    ("a local name", "P.kt", "val cancelled = async { sync() }", 1),
    ("a platform name", "P.kt", "if (job.isCancelled) return // see [Job.isCancelled]", 0),
    ("the header's constant", "P.kt", "Kind.CANCELLED -> stop() // [BridgeErrorKind.CANCELLED]", 0),
    ("inline code in a comment", "P.kt", "// `behaviour` is a column name", 0),
    ("a character literal", "P.kt", "val quote = '\"' // the behaviour after it", 1),
    ("code after a string", "P.kt", 'val text = "a \\" quote"; val behaviourFlag = 1', 1),
    ("another site's URL in a comment", "P.kt", "// https://example.com/behaviours/colour", 0),
    ("American Kotlin", "P.kt", '/** Canceled toward. */\nval canceled = "behavior" // color', 0),
    ("a Swift doc comment", "P.swift", "/// Recognises the colour.\nfunc key() {}", 2),
    ("a Swift string", "P.swift", 'let text = "cancelled at \\(time) by \\(name)"', 1),
    ("a Swift interpolated name", "P.swift", 'let text = "\\(cancelledCount) rows"', 1),
    ("a Swift raw string", "P.swift", 'let text = #"the "behaviour" \\#(value)"#', 1),
    ("a Swift test name", "P.swift", "@Test func aCancelledDownloadThrows() async {}", 1),
    ("the header constant's Swift name", "P.swift", "if case .cancelled = kind { return }", 0),
    ("a Swift task's flag", "P.swift", "if Task.isCancelled { return }", 0),
    ("a Swift name of our own", "P.swift", "private var cancelled = false", 1),
    ("a Python comment", "p.py", "count = 1  # the behaviour of the count", 1),
    ("a Python docstring", "p.py", 'def key():\n    """Normalises the key."""', 1),
    ("a Python f-string", "p.py", 'print(f"{count} rows cancelled, {name}")', 1),
    ("a Python f-string's field", "p.py", 'print(f"{normalise(count)} rows")', 1),
    ("a Python name", "p.py", "def normalise_swift(text):\n    return text", 1),
    ("a Python string with a quote", "p.py", "text = 'it\\'s fine'; behaviour = 1", 1),
    ("GitHub's result in a script", ".github/scripts/p.py", 'needs(ios="cancelled")', 0),
    ("a result outside .github", "scripts/p.py", 'needs(ios="cancelled")', 1),
    ("a workflow comment", ".github/workflows/p.yml", "# never cancelled halfway\nrun: echo", 1),
    ("GitHub's status function", ".github/workflows/p.yml", "if: ${{ !cancelled() }}", 0),
    (
        "GitHub's outcomes",
        ".github/workflows/p.yml",
        "case x in success | failure | cancelled | skipped) ;; esac",
        0,
    ),
    ("a shell message", "p.sh", 'echo "the run was cancelled"', 1),
    ("a shell name", "p.sh", "normalise_names() { :; }", 1),
    ("a CSS comment", "p.css", "/* Hover and focus colour. */", 1),
    ("a TOML comment", "p.toml", "# inserts the licence header", 1),
    ("a text file", "p.txt", "Licence: MIT", 1),
)

# (British word, the American form the check must propose for it).
SUGGESTION_CASES: Tuple[Tuple[str, str], ...] = (
    ("Behaviours", "Behaviors"),
    ("serialisation", "serialization"),
    ("recognised", "recognized"),
    ("labelling", "labeling"),
    ("cancelled", "canceled"),
    ("acknowledgements", "acknowledgments"),
    ("fulfilment", "fulfillment"),
    ("centred", "centered"),
    ("catalogued", "cataloged"),
    ("licences", "licenses"),
    ("whilst", "while"),
    ("towards", "toward"),
    ("grey", "gray"),
    ("unlabelled", "unlabeled"),
    ("parallelised", "parallelized"),
    ("canonicalisation", "canonicalization"),
    ("rumour", "rumor"),
)

# (name, file, text, the one line the check must print for it).
MESSAGE_CASES: Tuple[Tuple[str, str, str, str], ...] = (
    (
        "a word in a name",
        "P.kt",
        "fun a_cancelled_pass() {}",
        "P.kt:1: 'cancelled' in 'a_cancelled_pass', American 'canceled'",
    ),
    (
        "a capitalized word in a name",
        "P.swift",
        "func aCancelledPass() {}",
        "P.swift:1: 'Cancelled' in 'aCancelledPass', American 'Canceled'",
    ),
    (
        "a line of a doc comment",
        "P.kt",
        "/**\n *\n * Recognised.\n */",
        "P.kt:3: 'Recognised', American 'Recognized'",
    ),
)


def self_test() -> int:
    failures = []
    for name, text, expected in SELF_TEST_CASES:
        found = violations_in("probe.md", text)
        if len(found) != expected:
            failures.append(f"{name}: expected {expected} British form(s), got {found}")
    for name, path, text, expected in SOURCE_CASES:
        found = violations_in(path, text)
        if len(found) != expected:
            failures.append(f"{name}: expected {expected} British form(s), got {found}")
    for british, american in SUGGESTION_CASES:
        found = violations_in("probe.md", f"The {british} here.")
        if found != [f"probe.md:1: '{british}', American '{american}'"]:
            failures.append(f"{british}: expected the American form {american}, got {found}")
    for name, path, text, expected_line in MESSAGE_CASES:
        found = violations_in(path, text)
        if found != [expected_line]:
            failures.append(f"{name}: expected [{expected_line!r}], got {found}")
    with tempfile.TemporaryDirectory() as root:
        subprocess.run(["git", "init", "-q", root], check=True)
        if check(root) != 2:
            failures.append("an empty tree did not fail with status 2")
        files = {
            "page.md": "The colour of the page.\n",
            "mkdocs.yml": "nav:\n  - Behaviours: behaviours/index.md\n",
            "Source.kt": "// the behaviour\nval isCancelled = true\n",
            "scripts/check-spelling.py": "BRITISH = 'behaviour'\n",
            "image.png": "behaviour\n",
            "untracked.md": "The colour of a file git does not track.\n",
        }
        for path, text in files.items():
            os.makedirs(os.path.dirname(os.path.join(root, path)), exist_ok=True)
            with open(os.path.join(root, path), "w", encoding="utf-8") as handle:
                handle.write(text)
        tracked = [path for path in files if path != "untracked.md"]
        subprocess.run(["git", "-C", root, "add", *tracked], check=True)
        scanned, found, used = violations(root)
        if scanned != 3 or len(found) != 4:
            failures.append(f"a tracked tree: expected 3 files and 4 forms, got {scanned}, {found}")
        if used != {0}:
            failures.append(f"a tracked tree: expected only the first allowance used, got {used}")
        if check(root) != 1:
            failures.append("a tree with British forms and unused allowances did not fail")
    for failure in failures:
        print(f"::error::self-test: {failure}")
    total = (
        len(SELF_TEST_CASES) + len(SOURCE_CASES) + len(SUGGESTION_CASES) + len(MESSAGE_CASES) + 4
    )
    print(f"Self-test: {total - len(failures)} of {total} cases as expected.")
    return 1 if failures else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(check(REPO_ROOT))
