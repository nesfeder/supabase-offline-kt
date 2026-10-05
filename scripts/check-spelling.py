#!/usr/bin/env python3
# Copyright (c) 2026 nesfeder
# SPDX-License-Identifier: MIT
"""Keeps the project's prose in American spelling. Whole-tree: every run reads every Markdown file
git tracks, and mkdocs.yml, so no base commit is needed.

It fails on a British form (behaviour, colour, licence, cancelled, labelled, recognise, centre,
catalogue, towards, whilst and the rest of BRITISH_FORMS below), matched as a whole word in any
case, a hyphen ending a word. Every -ise, -ised, -ising and -isation word counts as British but
those American English spells so too (AMERICAN_ISE_WORDS: promise, otherwise, exercise, and any
word ending in -wise). What is not prose is left out before anything is matched:

    fenced code blocks                       ``` or ~~~, at any indent
    inline code                              `isCancelled`, ``code with a ` in it``
    URLs of other sites                      https://… that is not this repository or its site

So an identifier that follows its platform (`isCancelled`, `CancellationException`) is written
in inline code, and a link to another site keeps its own spelling. ALLOWED_PHRASES lists the few
places prose must quote a British form, each with its reason.

Usage:
    scripts/check-spelling.py               check the tree
    scripts/check-spelling.py --self-test   prove the check catches each kind of British form

It fails with status 2 when it finds no file to check: a check that saw nothing proves nothing.
"""

import os
import re
import subprocess
import sys
import tempfile
from typing import Iterator, List, Tuple

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
    # its own rule below.
    (
        r"(?!practis)\w+is" + ISE_SUFFIX,
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


# Prose that must quote a British form exactly, with the reason. A match inside one is allowed.
ALLOWED_PHRASES: Tuple[Tuple[str, str], ...] = ()

FENCE = re.compile(r"^\s*(```|~~~)")
URL = re.compile(r"<?https?://[^\s<>)\]]+>?")
OWN_URL = re.compile(r"github\.com/nesfeder/supabase-offline-kt|nesfeder\.github\.io/supabase-offline-kt")


def checked_files(root: str) -> List[str]:
    """Every Markdown file git tracks under [root], and mkdocs.yml, relative to [root]."""
    listed = subprocess.run(
        ["git", "ls-files", "-z", "--", "*.md", "mkdocs.yml"],
        cwd=root,
        capture_output=True,
        check=True,
    ).stdout.decode("utf-8")
    return sorted(path for path in listed.split("\0") if path)


def prose_lines(text: str) -> Iterator[Tuple[int, str]]:
    """Each line of [text] with fenced code, inline code and other sites' URLs blanked out."""
    in_fence = None
    open_ticks = 0
    for number, line in enumerate(text.split("\n"), start=1):
        fence = FENCE.match(line)
        if in_fence is not None:
            if fence and fence.group(1) == in_fence:
                in_fence = None
            continue
        if fence:
            in_fence = fence.group(1)
            open_ticks = 0
            continue
        if not line.strip():
            open_ticks = 0
            yield number, line
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
        prose = "".join(kept)
        prose = URL.sub(lambda match: match.group(0) if OWN_URL.search(match.group(0)) else " ", prose)
        yield number, prose


def violations_in(path: str, text: str) -> List[str]:
    """One `path:line: 'word' → American` per British form in [text]."""
    found = []
    for number, line in prose_lines(text):
        for pattern, rewrites in PATTERNS:
            for match in pattern.finditer(line):
                allowed = any(
                    phrase_match.start() <= match.start() and match.end() <= phrase_match.end()
                    for phrase, _ in ALLOWED_PHRASES
                    for phrase_match in re.finditer(re.escape(phrase), line)
                )
                if not allowed and not is_american_ise(match.group(0)):
                    american = american_form(match.group(0), rewrites)
                    found.append(f"{path}:{number}: '{match.group(0)}', American '{american}'")
    return found


def violations(root: str) -> Tuple[int, List[str]]:
    """How many files were read, and every British form found in them."""
    files = checked_files(root)
    found: List[str] = []
    for path in files:
        with open(os.path.join(root, path), encoding="utf-8") as handle:
            found.extend(violations_in(path, handle.read()))
    return len(files), found


def check(root: str) -> int:
    scanned, found = violations(root)
    if scanned == 0:
        print("No tracked Markdown file found to check.", file=sys.stderr)
        return 2
    for line in found:
        print(f"::error::{line}: the project's prose is spelled the American way")
    print(f"Checked {scanned} Markdown and YAML files: {len(found)} British form(s).")
    return 1 if found else 0


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


def self_test() -> int:
    failures = []
    for name, text, expected in SELF_TEST_CASES:
        found = violations_in("probe.md", text)
        if len(found) != expected:
            failures.append(f"{name}: expected {expected} British form(s), got {found}")
    for british, american in SUGGESTION_CASES:
        found = violations_in("probe.md", f"The {british} here.")
        if found != [f"probe.md:1: '{british}', American '{american}'"]:
            failures.append(f"{british}: expected the American form {american}, got {found}")
    with tempfile.TemporaryDirectory() as root:
        subprocess.run(["git", "init", "-q", root], check=True)
        if check(root) != 2:
            failures.append("an empty tree did not fail with status 2")
        with open(os.path.join(root, "page.md"), "w", encoding="utf-8") as handle:
            handle.write("The colour of the page.\n")
        with open(os.path.join(root, "mkdocs.yml"), "w", encoding="utf-8") as handle:
            handle.write("nav:\n  - Behaviours: behaviours/index.md\n")
        with open(os.path.join(root, "untracked.md"), "w", encoding="utf-8") as handle:
            handle.write("The colour of a file git does not track.\n")
        subprocess.run(["git", "-C", root, "add", "page.md", "mkdocs.yml"], check=True)
        scanned, found = violations(root)
        if scanned != 2 or len(found) != 3:
            failures.append(f"a tracked tree: expected 2 files and 3 forms, got {scanned} and {found}")
    for failure in failures:
        print(f"::error::self-test: {failure}")
    total = len(SELF_TEST_CASES) + len(SUGGESTION_CASES) + 2
    print(f"Self-test: {total - len(failures)} of {total} cases as expected.")
    return 1 if failures else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if sys.argv[1:]:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(check(REPO_ROOT))
