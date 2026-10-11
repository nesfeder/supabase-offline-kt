#!/usr/bin/env bash
#
# Keeps internal code out of the SDK's public surface. Whole-tree: every run scans every main
# source set of both modules, so no base commit is needed.
#
# It fails when, in a main source set of either module:
#
#   1. internal-in-public-package   a top-level declaration with `internal` visibility is housed
#                                   in a public package (a package with no `internal` segment);
#   2. public-in-internal-package   a top-level declaration with public visibility (explicit, or
#                                   Kotlin's default) is housed in an internal package without an
#                                   error-level opt-in marker (an annotation class declared with
#                                   @RequiresOptIn at level ERROR) and @HiddenFromObjC;
#   3. published-api                @PublishedApi appears anywhere.
#
# It also keeps the ABI records honest (see compat/abi-exclusions.txt). It fails when:
#
#   4. marker-not-excluded          an error-level opt-in marker is not listed as
#                                   `annotation <fully qualified name>` in compat/abi-exclusions.txt,
#                                   so the hooks it gates would reach the records as public API;
#   5. exclusion-without-marker     compat/abi-exclusions.txt names something that is not an
#                                   error-level opt-in marker in these sources, which would hide
#                                   real API from the records;
#   6. exclusion-malformed          a line of compat/abi-exclusions.txt is not `annotation <name>`,
#                                   or the file is missing;
#   7. gated-list-stale             compat/gated-declarations.txt is not the list --list-gated
#                                   prints, so a new gated hook would not show in the pull request.
#
# Comments, string literals (including raw strings and string templates), character literals and
# backticked names are stripped before anything is matched, so the words `public` and `internal`
# in prose or in a string never count. Annotations are read on the line above a declaration and
# on the same line, including multi-line annotation arguments.
#
# GRANDFATHERED below once held known violations, each with a reason; the public-API work moved
# the code and emptied it. An entry that no longer matches anything fails the check, and nothing
# may be added to it.
#
# It also fails, with status 2, when it cannot list or scan the sources: a module's source
# directory is missing, a directory cannot be read, or no main-source file is found at all. A
# check that saw nothing proves nothing.
#
# Usage:
#   scripts/check-api-visibility.sh               check the tree
#   scripts/check-api-visibility.sh --self-test   prove the check catches each kind of violation
#   scripts/check-api-visibility.sh --list-gated  print every public declaration in an internal
#                                                 package that an error-level marker and
#                                                 @HiddenFromObjC gate, as `path|declaration|markers`;
#                                                 compat/gated-declarations.txt holds this list
#
# Portable: bash 3.2 (macOS) or later, POSIX awk (BSD awk, mawk or gawk), BSD or GNU find.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# The published modules. supabase-offline-swift-bridge is left out on purpose: it is never
# published and has no API record; its public declarations are the Swift package's Objective-C
# surface, which scripts/check-bridge-header.py keeps to the bridge's own types.
MODULES="supabase-offline-kt supabase-offline-kt-testing"

# rule|path relative to the repository root|declaration name|reason
GRANDFATHERED='
'

# ─────────────────────────────────────────────────────────────────────────────
# Pass 1 (one awk run per file): strip comments and literals, then report every top-level
# declaration and every @PublishedApi as tab-separated records:
#
#   DECL  path line packageKind visibility keyword name annotationNames annotationText modifiers
#         packageName
#   PUBLISHEDAPI  path line name
# ─────────────────────────────────────────────────────────────────────────────
read -r -d '' SCAN_AWK <<'AWK' || true
function trim(text) { sub(/^[[:space:]]+/, "", text); sub(/[[:space:]]+$/, "", text); return text }

# Reads the leading annotations of `text` into annNames / annText and returns what follows them.
# Sets annOpen when an annotation's argument list continues on a later line.
function takeAnnotations(text,    name, taken, depth, pos, len, ch) {
    annOpen = 0
    while (substr(text, 1, 1) == "@") {
        if (!match(text, /^@([a-z]+:)?[A-Za-z_][A-Za-z0-9_.]*/)) break
        name = substr(text, 2, RLENGTH - 1)
        sub(/^[a-z]+:/, "", name)
        sub(/.*\./, "", name)
        taken = substr(text, 1, RLENGTH)
        text = substr(text, RLENGTH + 1)
        if (substr(text, 1, 1) == "(") {
            depth = 0; pos = 1; len = length(text)
            while (pos <= len) {
                ch = substr(text, pos, 1)
                if (ch == "(") depth++
                else if (ch == ")") { depth--; if (depth == 0) break }
                pos++
            }
            taken = taken substr(text, 1, pos)
            text = (pos < len) ? substr(text, pos + 1) : ""
            if (depth > 0) annOpen = 1
        }
        annNames = annNames "," name
        annText = annText " " taken
        text = trim(text)
    }
    return text
}

# Recognizes a declaration head in `text` (annotations already removed). On a match sets
# declKeyword, declName, declModifiers and declVisibility and returns 1.
function readDeclaration(text,    head, previous, probe, depth, pos, len, ch, after) {
    declModifiers = " "
    while (match(text, /^(public|internal|private|protected|open|abstract|final|data|sealed|enum|annotation|inline|value|const|lateinit|suspend|operator|infix|tailrec|external|expect|actual|override|inner|companion)[[:space:]]+/)) {
        declModifiers = declModifiers trim(substr(text, 1, RLENGTH)) " "
        text = substr(text, RLENGTH + 1)
    }
    if (match(text, /^fun[[:space:]]+interface([[:space:]]|$)/)) {
        declKeyword = "interface"
        text = substr(text, RLENGTH + 1)
    } else if (match(text, /^(class|interface|object|fun|val|var|typealias)([[:space:]]|[<(]|$)/)) {
        declKeyword = substr(text, 1, RLENGTH)
        sub(/[[:space:]<(]$/, "", declKeyword)
        text = substr(text, length(declKeyword) + 1)
    } else {
        return 0
    }
    # Drop type parameters and receiver generics, then take the identifier that names it.
    head = text
    do { previous = head; gsub(/<[^<>]*>/, "", head) } while (head != previous)
    # An unnamed object or function is an expression continuing the line above (`val x =` then
    # `object : T {` or `fun(value: Int) = ...`), but only when what follows the keyword proves
    # it: `:` or `{` after `object`, a parameter list NOT followed by `.` after `fun`. A
    # parenthesized receiver (`fun (() -> Unit).name()`) is a declaration.
    probe = trim(head)
    if (declKeyword == "object" && (substr(probe, 1, 1) == ":" || substr(probe, 1, 1) == "{")) return 0
    if (declKeyword == "fun" && substr(probe, 1, 1) == "(") {
        depth = 0; pos = 1; len = length(probe)
        while (pos <= len) {
            ch = substr(probe, pos, 1)
            if (ch == "(") depth++
            else if (ch == ")") { depth--; if (depth == 0) break }
            pos++
        }
        after = substr(probe, pos + 1)
        if (substr(after, 1, 1) == "." || substr(after, 1, 2) == "?.") head = " " substr(after, index(after, ".") + 1)
        else return 0
    }
    if (declKeyword == "fun") sub(/\(.*/, "", head)
    else if (declKeyword == "val" || declKeyword == "var") sub(/[:=({].*/, "", head)
    else sub(/[^[:space:]A-Za-z0-9_].*/, "", head)
    head = trim(head)
    if (declKeyword == "fun" || declKeyword == "val" || declKeyword == "var") sub(/.*\./, "", head)
    else sub(/[[:space:]].*/, "", head)
    declName = (head == "") ? "<anonymous>" : head

    if (index(declModifiers, " internal ")) declVisibility = "internal"
    else if (index(declModifiers, " private ")) declVisibility = "private"
    else if (index(declModifiers, " protected ")) declVisibility = "protected"
    else declVisibility = "public"
    return 1
}

function resetPending() { pendingNames = ""; pendingText = ""; inAnnotationArgs = 0 }

BEGIN {
    sp = 0; mode[0] = "C"; blockDepth = 0; braceDepth = 0; parenDepth = 0
    packageKind = "public"; packageName = ""; publishedLine = 0
    resetPending()
}

{
    line = $0; out = ""; n = length(line); i = 1
    while (i <= n) {
        c = substr(line, i, 1); c2 = substr(line, i, 2); c3 = substr(line, i, 3)
        if (blockDepth > 0) {
            if (c2 == "/*") { blockDepth++; i += 2; continue }
            if (c2 == "*/") { blockDepth--; i += 2; if (blockDepth == 0 && sp == 0) out = out " "; continue }
            i++; continue
        }
        m = mode[sp]
        if (m == "C" || m == "T") {
            if (c2 == "//") break
            if (c2 == "/*") { blockDepth = 1; i += 2; continue }
            if (c3 == "\"\"\"") { if (sp == 0) out = out "\"\""; sp++; mode[sp] = "R"; i += 3; continue }
            if (c == "\"") { if (sp == 0) out = out "\"\""; sp++; mode[sp] = "S"; i++; continue }
            if (c == "'") {
                j = i + 1
                while (j <= n) {
                    d = substr(line, j, 1)
                    if (d == "\\") { j += 2; continue }
                    if (d == "'") break
                    j++
                }
                if (sp == 0) out = out "'_'"
                i = j + 1; continue
            }
            if (c == "`") {
                j = index(substr(line, i + 1), "`")
                i = (j == 0) ? n + 1 : i + j + 1
                if (sp == 0) out = out "_"
                continue
            }
            if (m == "T") {
                if (c == "{") templateDepth[sp]++
                else if (c == "}") { templateDepth[sp]--; if (templateDepth[sp] == 0) sp-- }
                i++; continue
            }
            out = out c; i++; continue
        }
        if (m == "S") {
            if (c == "\\") { i += 2; continue }
            if (c == "\"") { sp--; i++; continue }
            if (c2 == "${") { sp++; mode[sp] = "T"; templateDepth[sp] = 1; i += 2; continue }
            i++; continue
        }
        if (m == "R") {
            if (c3 == "\"\"\"") {
                j = i + 3
                while (substr(line, j, 1) == "\"") j++
                sp--; i = j; continue
            }
            if (c2 == "${") { sp++; mode[sp] = "T"; templateDepth[sp] = 1; i += 2; continue }
            i++; continue
        }
    }

    gsub(/\t/, " ", out)
    startBrace = braceDepth; startParen = parenDepth
    text = trim(out)

    if (match(text, /@(kotlin\.)?PublishedApi([^A-Za-z0-9_]|$)/)) publishedLine = FNR

    if (startBrace == 0 && startParen > 0) {
        if (inAnnotationArgs) pendingText = pendingText " " text
    } else if (startBrace == 0 && text != "") {
        if (text ~ /^@file:/) {
            inAnnotationArgs = 0
        } else if (text ~ /^package[[:space:]]/) {
            packageName = trim(substr(text, 8))
            packageKind = (packageName ~ /(^|\.)internal(\.|$)/) ? "internal" : "public"
            resetPending()
        } else if (text ~ /^import[[:space:]]/) {
            resetPending()
        } else {
            annNames = ""; annText = ""
            rest = takeAnnotations(text)
            pendingNames = pendingNames annNames
            pendingText = pendingText annText
            if (rest == "") {
                inAnnotationArgs = annOpen
            } else if (readDeclaration(rest)) {
                printf "DECL\t%s\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", path, FNR, packageKind, declVisibility, declKeyword, declName, pendingNames, pendingText, declModifiers, packageName
                if (publishedLine > 0) { printf "PUBLISHEDAPI\t%s\t%d\t%s\n", path, publishedLine, declName; publishedLine = 0 }
                resetPending()
            } else {
                resetPending()
            }
        }
    } else if (startBrace > 0 && publishedLine > 0 && text != "") {
        annNames = ""; annText = ""
        rest = takeAnnotations(text)
        if (rest != "" && readDeclaration(rest)) {
            printf "PUBLISHEDAPI\t%s\t%d\t%s\n", path, publishedLine, declName
            publishedLine = 0
        }
    }

    for (k = 1; k <= length(out); k++) {
        ch = substr(out, k, 1)
        if (ch == "{") braceDepth++
        else if (ch == "}") braceDepth--
        else if (ch == "(") parenDepth++
        else if (ch == ")") parenDepth--
    }
    if (parenDepth == 0) inAnnotationArgs = 0
}

END {
    if (publishedLine > 0) printf "PUBLISHEDAPI\t%s\t%d\t%s\n", path, publishedLine, "<unattached>"
}
AWK

# ─────────────────────────────────────────────────────────────────────────────
# Pass 2: apply the three rules to the records. Reads the records twice: first to find the
# error-level opt-in markers, then to judge each declaration.
#
#   rule  path line name detail
# ─────────────────────────────────────────────────────────────────────────────
read -r -d '' RULES_AWK <<'AWK' || true
BEGIN { FS = "\t" }
NR == FNR {
    if ($1 == "DECL" && $6 == "class" && index($10, " annotation ") && index($8 ",", ",RequiresOptIn,") && $9 ~ /RequiresOptIn[^@]*ERROR/) {
        marker[$7] = 1
    }
    next
}
$1 == "DECL" && $4 == "public" && $5 == "internal" {
    printf "internal-in-public-package\t%s\t%s\t%s\tinternal %s in a public package\n", $2, $3, $7, $6
}
$1 == "DECL" && $4 == "internal" && $5 == "public" {
    count = split($8, names, ",")
    hasMarker = 0
    for (k = 1; k <= count; k++) if (names[k] in marker) hasMarker = 1
    hidden = index($8 ",", ",HiddenFromObjC,") > 0
    if (!hasMarker || !hidden) {
        missing = ""
        if (!hasMarker) missing = "an error-level opt-in marker"
        if (!hidden) missing = missing (missing == "" ? "" : " and ") "@HiddenFromObjC"
        printf "public-in-internal-package\t%s\t%s\t%s\tpublic %s in an internal package without %s\n", $2, $3, $7, $6, missing
    }
}
$1 == "PUBLISHEDAPI" {
    printf "published-api\t%s\t%s\t%s\t@PublishedApi\n", $2, $3, $4
}
AWK

# ─────────────────────────────────────────────────────────────────────────────
# Pass 3: compare the error-level markers with the exclusions file (rules 4 to 6). Reads the
# records, then the file: one `annotation <fully qualified name>` per line, `#` starts a comment.
# ─────────────────────────────────────────────────────────────────────────────
read -r -d '' EXCLUSIONS_AWK <<'AWK' || true
BEGIN { FS = "\t" }
NR == FNR {
    if ($1 == "DECL" && $6 == "class" && index($10, " annotation ") && index($8 ",", ",RequiresOptIn,") && $9 ~ /RequiresOptIn[^@]*ERROR/) {
        fqn = ($11 == "") ? $7 : $11 "." $7
        markers[fqn] = $2 "\t" $3
    }
    next
}
{
    line = $0
    sub(/#.*/, "", line)
    sub(/^[[:space:]]+/, "", line)
    sub(/[[:space:]]+$/, "", line)
    if (line == "") next
    count = split(line, parts, /[[:space:]]+/)
    if (count != 2 || parts[1] != "annotation") {
        printf "exclusion-malformed\t%s\t%d\t%s\tnot `annotation <fully qualified name>`\n", exclusionsPath, FNR, line
    } else {
        excluded[parts[2]] = 1
        if (!(parts[2] in markers)) {
            printf "exclusion-without-marker\t%s\t%d\t%s\tno error-level opt-in marker of that name in the sources\n", exclusionsPath, FNR, parts[2]
        }
    }
}
END {
    for (fqn in markers) {
        if (!(fqn in excluded)) {
            split(markers[fqn], where, "\t")
            printf "marker-not-excluded\t%s\t%s\t%s\tadd `annotation %s` to %s\n", where[1], where[2], fqn, fqn, exclusionsPath
        }
    }
}
AWK

# Prints `path|keyword name|markers` for every public declaration in an internal package that an
# error-level marker and @HiddenFromObjC gate: the hooks another module of this project calls.
read -r -d '' GATED_AWK <<'AWK' || true
BEGIN { FS = "\t" }
NR == FNR {
    if ($1 == "DECL" && $6 == "class" && index($10, " annotation ") && index($8 ",", ",RequiresOptIn,") && $9 ~ /RequiresOptIn[^@]*ERROR/) marker[$7] = 1
    next
}
$1 == "DECL" && $4 == "internal" && $5 == "public" && index($8 ",", ",HiddenFromObjC,") {
    count = split($8, names, ",")
    found = ""
    for (k = 1; k <= count; k++) if (names[k] in marker) found = found (found == "" ? "" : ",") names[k]
    if (found != "") printf "%s|%s %s|%s\n", $2, $6, $7, found
}
AWK

EXCLUSIONS_FILE="compat/abi-exclusions.txt"
GATED_FILE="compat/gated-declarations.txt"

# Lists every main-source Kotlin file under root "$1", one per line, sorted. Fails when a module's
# source directory is missing or `find` reports an error, so the check never covers less than
# the whole tree without saying so.
list_sources() {
    local root="$1" module listing
    listing="$(
        for module in $MODULES; do
            if [ ! -d "$root/$module/src" ]; then
                echo "check-api-visibility: $module/src is missing under $root" >&2
                exit 1
            fi
            find "$root/$module/src" -type f -name '*.kt' -path '*Main/kotlin/*' || exit 1
        done
    )" || return 1
    [ -z "$listing" ] || printf '%s\n' "$listing" | LC_ALL=C sort
}

# Prints the pass-1 records for every file listed in "$2", paths relative to root "$1".
scan_records() {
    local root="$1" sources="$2" file relative
    while IFS= read -r file; do
        relative="${file#"$root"/}"
        awk -v path="$relative" "$SCAN_AWK" "$file" || return 1
    done < "$sources"
}

# Checks root "$1" against the allow-list in "$2". Prints a report; returns 1 on any
# violation or stale allow-list entry, and 2 when the sources cannot be listed or scanned.
check_tree() {
    local root="$1" allow_list="$2" work sources records findings rule path line name detail key
    local entry reason declarations files expected violations=0 grandfathered=0 stale=0
    work="$(mktemp -d "${TMPDIR:-/tmp}/check-api-visibility.XXXXXX")"
    sources="$work/sources.txt"
    records="$work/records.tsv"
    findings="$work/findings.tsv"

    if ! list_sources "$root" > "$sources"; then
        echo "ERROR: could not list the main-source files under $root; nothing was checked." >&2
        rm -rf "${work:?}"
        return 2
    fi
    files="$(awk 'END { print NR }' "$sources")"
    if [ "$files" -eq 0 ]; then
        echo "ERROR: found no main-source files under $root; nothing was checked." >&2
        rm -rf "${work:?}"
        return 2
    fi
    if ! scan_records "$root" "$sources" > "$records" \
        || ! awk "$RULES_AWK" "$records" "$records" > "$findings"; then
        echo "ERROR: could not scan the main-source files under $root; nothing was checked." >&2
        rm -rf "${work:?}"
        return 2
    fi
    if [ -f "$root/$EXCLUSIONS_FILE" ]; then
        if ! awk -v exclusionsPath="$EXCLUSIONS_FILE" "$EXCLUSIONS_AWK" "$records" \
            "$root/$EXCLUSIONS_FILE" >> "$findings"; then
            echo "ERROR: could not read $EXCLUSIONS_FILE; nothing was checked." >&2
            rm -rf "${work:?}"
            return 2
        fi
    else
        printf 'exclusion-malformed\t%s\t0\t<file>\tthe file is missing\n' "$EXCLUSIONS_FILE" >> "$findings"
    fi
    if ! awk "$GATED_AWK" "$records" "$records" | LC_ALL=C sort > "$work/gated.txt"; then
        echo "ERROR: could not list the gated declarations under $root; nothing was checked." >&2
        rm -rf "${work:?}"
        return 2
    fi
    if ! cmp -s "$work/gated.txt" "$root/$GATED_FILE"; then
        printf 'gated-list-stale\t%s\t0\t<file>\tdiffers from --list-gated; regenerate it with scripts/check-api-visibility.sh --list-gated > %s\n' \
            "$GATED_FILE" "$GATED_FILE" >> "$findings"
        expected="$root/$GATED_FILE"
        [ -f "$expected" ] || expected=/dev/null
        diff -u -L "$GATED_FILE" -L "--list-gated" "$expected" "$work/gated.txt" > "$work/gated.diff" || true
    fi

    declarations="$(awk -F '\t' '$1 == "DECL" { count++ } END { print count + 0 }' "$records")"
    echo "Scanned $declarations top-level declarations in $files main-source files."

    while IFS="$(printf '\t')" read -r rule path line name detail; do
        [ -n "$rule" ] || continue
        key="$rule|$path|$name|"
        if grep -F -q -- "$key" <<< "$allow_list"; then
            grandfathered=$((grandfathered + 1))
            echo "  grandfathered  $rule  $path:$line  $name"
        else
            violations=$((violations + 1))
            echo "  VIOLATION      $rule  $path:$line  $name — $detail"
        fi
    done < "$findings"
    if [ -s "$work/gated.diff" ]; then
        echo "  $GATED_FILE against --list-gated:"
        sed 's/^/    /' "$work/gated.diff"
    fi

    while IFS= read -r entry; do
        [ -n "$entry" ] || continue
        rule="${entry%%|*}"; reason="${entry#*|}"; path="${reason%%|*}"; reason="${reason#*|}"; name="${reason%%|*}"
        if ! awk -F '\t' -v rule="$rule" -v path="$path" -v name="$name" \
            '$1 == rule && $2 == path && $4 == name { found = 1 } END { exit found ? 0 : 1 }' "$findings"; then
            stale=$((stale + 1))
            echo "  STALE          $rule  $path  $name — the allow-list entry matches nothing; remove it"
        fi
    done <<EOF
$allow_list
EOF

    rm -rf "${work:?}"
    echo "Result: $violations violation(s), $grandfathered grandfathered, $stale stale allow-list entr(y/ies)."
    [ "$violations" -eq 0 ] && [ "$stale" -eq 0 ]
}

# ─────────────────────────────────────────────────────────────────────────────
# Self-test: plant one violation of each kind beside a compliant file full of traps, and prove the
# check fails on each and passes on the compliant file alone. Then take away what the check reads
# and prove it fails instead of passing on nothing.
# ─────────────────────────────────────────────────────────────────────────────
write_compliant_files() {
    local src="$1/supabase-offline-kt/src/commonMain/kotlin"
    mkdir -p "$src/io/example/api" "$src/io/example/internal/util"
    mkdir -p "$1/supabase-offline-kt-testing/src/commonMain/kotlin"
    cat > "$src/io/example/api/PublicApi.kt" <<'KOTLIN'
@file:OptIn(ExperimentalObjCRefinement::class)

package io.example.api

import kotlin.experimental.ExperimentalObjCRefinement

/**
 * Prose that must never count: an internal fun hidden() and an internal class Leak.
 * /* nested block comment: internal val nested = 1 */ still inside the outer comment.
 */
public class PublicThing internal constructor(private val value: Int) {
    internal fun memberHelper(): Int = value
    private val braces = '{'
    private val quote = '"'
}

// internal fun commentedOut() = Unit

public const val BANNER: String = "internal fun inString() { unbalanced"
public val RAW: String = """
    internal class InRawString {
""".trimIndent()
public fun describe(count: Int?): String = "count is ${count ?: "internal val none {"}"

/** An error-level opt-in marker, declared over several lines. */
@RequiresOptIn(
    message = "internal testing hook",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
public annotation class PlantedTestingApi

public fun interface PublicCallback {
    public fun invoke()
}
KOTLIN
    cat > "$src/io/example/internal/util/InternalHelpers.kt" <<'KOTLIN'
@file:OptIn(ExperimentalObjCRefinement::class)

package io.example.internal.util

import io.example.api.PlantedTestingApi
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

internal class InternalThing {
    public fun widenedMember(): Int = 1
}

private fun fileHelper(): Int = 2

@PlantedTestingApi
@HiddenFromObjC
public fun hookOnSeparateLines(): Int = 3

@PlantedTestingApi @HiddenFromObjC public fun <T : Any> List<T>.hookOnOneLine(): Int = size

internal val continuedObject: Comparator<Int> =
    object : Comparator<Int> {
        override fun compare(a: Int, b: Int): Int = a - b
    }

internal val continuedFunction: (Int) -> Int =
    fun(value: Int): Int = value
KOTLIN
    mkdir -p "$1/compat"
    cat > "$1/$EXCLUSIONS_FILE" <<'TEXT'
# The hooks' marker, left out of the ABI records.
annotation io.example.api.PlantedTestingApi
TEXT
    cat > "$1/$GATED_FILE" <<'TEXT'
supabase-offline-kt/src/commonMain/kotlin/io/example/internal/util/InternalHelpers.kt|fun hookOnOneLine|PlantedTestingApi
supabase-offline-kt/src/commonMain/kotlin/io/example/internal/util/InternalHelpers.kt|fun hookOnSeparateLines|PlantedTestingApi
TEXT
}

run_self_test() {
    local work failures=0 kind expected_rule expected_name expected_text output status
    work="$(mktemp -d "${TMPDIR:-/tmp}/check-api-visibility-self-test.XXXXXX")"

    plant() {
        local root="$1" kind="$2"
        local src="$root/supabase-offline-kt/src/commonMain/kotlin"
        case "$kind" in
            internal-in-public-package)
                cat > "$src/io/example/api/Planted.kt" <<'KOTLIN'
package io.example.api

/** Looks public; is not. */
internal fun plantedInternalHelper(): Int = 1
KOTLIN
                ;;
            public-in-internal-package)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

public fun plantedPublicHelper(): Int = 1
KOTLIN
                ;;
            public-in-internal-package-marker-only)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

import io.example.api.PlantedTestingApi

@PlantedTestingApi
public fun plantedMarkedButExported(): Int = 1
KOTLIN
                ;;
            public-in-internal-package-warning-marker)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

import kotlin.native.HiddenFromObjC

@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
internal annotation class SoftMarker

@SoftMarker
@HiddenFromObjC
public fun plantedWarningLevelHook(): Int = 1
KOTLIN
                ;;
            public-object-in-internal-package)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

public object PlantedPublicObject
KOTLIN
                ;;
            receiver-function-type)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

public fun (() -> Unit).plantedReceiverHook() { this(); this() }
KOTLIN
                ;;
            receiver-generic-suspend-function-type)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

public fun <T> (suspend () -> T).plantedRetrying(): Unit = Unit
KOTLIN
                ;;
            receiver-parenthesized-type)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

public fun (String).plantedShout(): String = uppercase()
KOTLIN
                ;;
            receiver-nullable-function-type)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

public fun ((Int) -> Int)?.plantedOrIdentity(): (Int) -> Int = this ?: { it }
KOTLIN
                ;;
            internal-receiver-in-public-package)
                cat > "$src/io/example/api/Planted.kt" <<'KOTLIN'
package io.example.api

internal fun (() -> Unit).plantedTwice() { this(); this() }
KOTLIN
                ;;
            object-name-on-next-line)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

public object
PlantedLateObject
KOTLIN
                ;;
            function-name-on-next-line)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

public fun
plantedLateFunction(): Int = 1
KOTLIN
                ;;
            marker-not-excluded)
                cat > "$src/io/example/api/PlantedMarker.kt" <<'KOTLIN'
package io.example.api

@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
public annotation class PlantedUnlistedApi
KOTLIN
                ;;
            exclusion-without-marker)
                printf 'annotation io.example.api.NoSuchMarker\n' >> "$root/$EXCLUSIONS_FILE"
                ;;
            exclusion-of-a-warning-marker)
                cat > "$src/io/example/api/PlantedMarker.kt" <<'KOTLIN'
package io.example.api

@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
public annotation class PlantedExperimentalApi
KOTLIN
                printf 'annotation io.example.api.PlantedExperimentalApi\n' >> "$root/$EXCLUSIONS_FILE"
                ;;
            exclusion-by-package)
                printf 'names io.example.internal.**\n' >> "$root/$EXCLUSIONS_FILE"
                ;;
            exclusions-file-missing)
                rm "$root/$EXCLUSIONS_FILE"
                ;;
            gated-hook-not-listed)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
@file:OptIn(ExperimentalObjCRefinement::class)

package io.example.internal.util

import io.example.api.PlantedTestingApi
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

@PlantedTestingApi
@HiddenFromObjC
public fun plantedNewHook(): Int = 1
KOTLIN
                ;;
            gated-list-missing)
                rm "$root/$GATED_FILE"
                ;;
            published-api)
                cat > "$src/io/example/internal/util/Planted.kt" <<'KOTLIN'
package io.example.internal.util

internal class Holder {
    @PublishedApi
    internal fun plantedPublishedMember(): Int = 1
}
KOTLIN
                ;;
        esac
    }

    expect_failure() {
        kind="$1"; expected_rule="$2"; expected_name="$3"
        local root="$work/$kind"
        write_compliant_files "$root"
        plant "$root" "$kind"
        status=0
        output="$(check_tree "$root" "" 2>&1)" || status=$?
        if [ "$status" -ne 0 ] && grep -F -q -- "VIOLATION      $expected_rule" <<< "$output" \
            && grep -F -q -- "$expected_name" <<< "$output"; then
            echo "ok   $kind: caught ($expected_rule, $expected_name)"
        else
            echo "FAIL $kind: not caught (exit $status)"
            printf '%s\n' "$output" | sed 's/^/     /'
            failures=$((failures + 1))
        fi
    }

    expect_failure internal-in-public-package internal-in-public-package plantedInternalHelper
    expect_failure public-in-internal-package public-in-internal-package plantedPublicHelper
    expect_failure public-in-internal-package-marker-only public-in-internal-package plantedMarkedButExported
    expect_failure public-in-internal-package-warning-marker public-in-internal-package plantedWarningLevelHook
    expect_failure public-object-in-internal-package public-in-internal-package PlantedPublicObject
    # A parenthesized receiver is a declaration, not an anonymous function.
    expect_failure receiver-function-type public-in-internal-package plantedReceiverHook
    expect_failure receiver-generic-suspend-function-type public-in-internal-package plantedRetrying
    expect_failure receiver-parenthesized-type public-in-internal-package plantedShout
    expect_failure receiver-nullable-function-type public-in-internal-package plantedOrIdentity
    expect_failure internal-receiver-in-public-package internal-in-public-package plantedTwice
    # Kotlin allows a line break before the name; the check reports the declaration unnamed.
    expect_failure object-name-on-next-line public-in-internal-package "<anonymous>"
    expect_failure function-name-on-next-line public-in-internal-package "<anonymous>"
    expect_failure published-api published-api plantedPublishedMember
    expect_failure marker-not-excluded marker-not-excluded io.example.api.PlantedUnlistedApi
    expect_failure exclusion-without-marker exclusion-without-marker io.example.api.NoSuchMarker
    expect_failure exclusion-of-a-warning-marker exclusion-without-marker io.example.api.PlantedExperimentalApi
    expect_failure exclusion-by-package exclusion-malformed "names io.example.internal.**"
    expect_failure exclusions-file-missing exclusion-malformed "the file is missing"
    expect_failure gated-hook-not-listed gated-list-stale "+supabase-offline-kt/src/commonMain/kotlin/io/example/internal/util/Planted.kt|fun plantedNewHook|PlantedTestingApi"
    expect_failure gated-list-missing gated-list-stale "compat/gated-declarations.txt"

    local gated="$work/gated" listing
    write_compliant_files "$gated"
    status=0
    listing="$(list_gated "$gated")" || status=$?
    if [ "$status" -eq 0 ] && [ "$listing" = "$(cat "$gated/$GATED_FILE")" ]; then
        echo "ok   --list-gated: prints both gated hooks, as compat/gated-declarations.txt holds them"
    else
        echo "FAIL --list-gated: exit $status, got"
        printf '%s\n' "$listing" | sed 's/^/     /'
        failures=$((failures + 1))
    fi
    # A hook hidden from Objective-C but gated only by a warning-level marker is not gated.
    write_compliant_files "$gated-warning"
    plant "$gated-warning" public-in-internal-package-warning-marker
    status=0
    listing="$(list_gated "$gated-warning")" || status=$?
    if [ "$status" -eq 0 ] && [ "$listing" = "$(cat "$gated-warning/$GATED_FILE")" ]; then
        echo "ok   --list-gated: leaves out a hook that only a warning-level marker gates"
    else
        echo "FAIL --list-gated: listed a hook that only a warning-level marker gates (exit $status)"
        printf '%s\n' "$listing" | sed 's/^/     /'
        failures=$((failures + 1))
    fi

    local clean="$work/compliant"
    write_compliant_files "$clean"
    status=0
    output="$(check_tree "$clean" "" 2>&1)" || status=$?
    if [ "$status" -eq 0 ] && grep -F -q "Result: 0 violation(s)" <<< "$output" \
        && grep -F -q "Scanned 12 top-level declarations" <<< "$output"; then
        echo "ok   compliant tree: passes with all 12 top-level declarations seen"
    else
        echo "FAIL compliant tree: exit $status"
        printf '%s\n' "$output" | sed 's/^/     /'
        failures=$((failures + 1))
    fi

    # Each tree below leaves the check less to read than it should. It must fail with status 2.
    expect_refusal() {
        kind="$1"; expected_text="$2"
        status=0
        output="$(check_tree "$work/$kind" "" 2>&1)" || status=$?
        if [ "$status" -eq 2 ] && grep -F -q -- "nothing was checked" <<< "$output" \
            && grep -F -q -- "$expected_text" <<< "$output"; then
            echo "ok   $kind: refused ($expected_text)"
        else
            echo "FAIL $kind: not refused (exit $status)"
            printf '%s\n' "$output" | sed 's/^/     /'
            failures=$((failures + 1))
        fi
    }

    write_compliant_files "$work/missing-module"
    rm -r "${work:?}/missing-module/supabase-offline-kt-testing"
    expect_refusal missing-module "supabase-offline-kt-testing/src is missing"

    local empty="$work/no-sources"
    mkdir -p "$empty/supabase-offline-kt/src" "$empty/supabase-offline-kt-testing/src"
    expect_refusal no-sources "found no main-source files"

    if [ "$(id -u)" -eq 0 ]; then
        echo "skip unreadable-directory: running as root, which can read any directory"
    else
        write_compliant_files "$work/unreadable-directory"
        mkdir "$work/unreadable-directory/supabase-offline-kt/src/iosMain"
        chmod 000 "$work/unreadable-directory/supabase-offline-kt/src/iosMain"
        expect_refusal unreadable-directory "could not list the main-source files"
        chmod 755 "$work/unreadable-directory/supabase-offline-kt/src/iosMain"
    fi

    rm -rf "${work:?}"
    if [ "$failures" -eq 0 ]; then
        echo "SELF-TEST PASSED: every planted violation was caught, the compliant tree passed," \
            "and every tree the check could not read was refused."
    else
        echo "SELF-TEST FAILED: $failures case(s)."
        return 1
    fi
}

# Prints the gated declarations under root "$1", sorted: the contents compat/gated-declarations.txt
# must have. Returns 2 when the sources cannot be listed or scanned.
list_gated() {
    local root="$1" work status=0
    work="$(mktemp -d "${TMPDIR:-/tmp}/check-api-visibility-gated.XXXXXX")"
    if ! list_sources "$root" > "$work/sources.txt" || [ ! -s "$work/sources.txt" ] \
        || ! scan_records "$root" "$work/sources.txt" > "$work/records.tsv" \
        || ! awk "$GATED_AWK" "$work/records.tsv" "$work/records.tsv" | LC_ALL=C sort > "$work/gated.txt"; then
        echo "ERROR: could not list the gated declarations under $root." >&2
        status=2
    else
        cat "$work/gated.txt"
    fi
    rm -rf "${work:?}"
    return "$status"
}

case "${1:-}" in
    --self-test) run_self_test ;;
    --list-gated) list_gated "$REPO_ROOT" ;;
    "") check_tree "$REPO_ROOT" "$GRANDFATHERED" ;;
    *) echo "usage: $0 [--self-test | --list-gated]" >&2; exit 2 ;;
esac
