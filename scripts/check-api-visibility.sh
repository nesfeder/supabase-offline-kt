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
# Comments, string literals (including raw strings and string templates), character literals and
# backticked names are stripped before anything is matched, so the words `public` and `internal`
# in prose or in a string never count. Annotations are read on the line above a declaration and
# on the same line, including multi-line annotation arguments.
#
# Known violations are grandfathered in GRANDFATHERED below, each with a reason. An entry that no
# longer matches anything fails the check too, so the list can only shrink. The pull request that
# freezes the public API empties it by moving the code; nothing may be added to it.
#
# It also fails, with status 2, when it cannot list or scan the sources: a module's source
# directory is missing, a directory cannot be read, or no main-source file is found at all. A
# check that saw nothing proves nothing.
#
# Usage:
#   scripts/check-api-visibility.sh              check the tree
#   scripts/check-api-visibility.sh --self-test  prove the check catches each kind of violation
#
# Portable: bash 3.2 (macOS) or later, POSIX awk (BSD awk, mawk or gawk), BSD or GNU find.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MODULES="supabase-offline-kt supabase-offline-kt-testing"

# rule|path relative to the repository root|declaration name|reason
GRANDFATHERED='
internal-in-public-package|supabase-offline-kt/src/commonMain/kotlin/io/github/nesfeder/supabase/offline/OfflineLogger.kt|SdkLogger|the level-gating logger wrapper the engine threads everywhere; moves to an internal package with the public-API freeze
internal-in-public-package|supabase-offline-kt/src/commonMain/kotlin/io/github/nesfeder/supabase/offline/OfflinePostgrestQueryBuilder.kt|OfflinePostgrestQueryBuilderImpl|the implementation behind the public builder interface; moves with the public-API freeze
internal-in-public-package|supabase-offline-kt/src/commonMain/kotlin/io/github/nesfeder/supabase/offline/OfflinePostgrestQueryBuilder.kt|preferResponseForCacheOverlay|a helper of the builder implementation; moves with it
internal-in-public-package|supabase-offline-kt/src/commonMain/kotlin/io/github/nesfeder/supabase/offline/RefreshAccelerator.kt|buildRefreshAccelerator|wiring for the public RefreshAccelerator; moves with the public-API freeze
internal-in-public-package|supabase-offline-kt/src/commonMain/kotlin/io/github/nesfeder/supabase/offline/RefreshAccelerator.kt|refreshReadyEdges|a helper of buildRefreshAccelerator; moves with it
internal-in-public-package|supabase-offline-kt/src/commonMain/kotlin/io/github/nesfeder/supabase/offline/SupabaseOfflineClient.kt|SupabaseOfflineClientImpl|the implementation behind the public client interface; moves with the public-API freeze
'

# ─────────────────────────────────────────────────────────────────────────────
# Pass 1 (one awk run per file): strip comments and literals, then report every top-level
# declaration and every @PublishedApi as tab-separated records:
#
#   DECL  path line packageKind visibility keyword name annotationNames annotationText modifiers
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

# Recognises a declaration head in `text` (annotations already removed). On a match sets
# declKeyword, declName, declModifiers and declVisibility and returns 1.
function readDeclaration(text,    head, previous) {
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
    if (declKeyword == "fun") sub(/\(.*/, "", head)
    else if (declKeyword == "val" || declKeyword == "var") sub(/[:=({].*/, "", head)
    else sub(/[^[:space:]A-Za-z0-9_].*/, "", head)
    head = trim(head)
    if (declKeyword == "fun" || declKeyword == "val" || declKeyword == "var") sub(/.*\./, "", head)
    else sub(/[[:space:]].*/, "", head)
    # A top-level object or function always has a name. Without one it is an object expression or
    # an anonymous function continuing the line above (`val x =` then `object : T {`).
    if (head == "" && (declKeyword == "object" || declKeyword == "fun")) return 0
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
    packageKind = "public"; publishedLine = 0
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
                printf "DECL\t%s\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", path, FNR, packageKind, declVisibility, declKeyword, declName, pendingNames, pendingText, declModifiers
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
    local entry reason declarations files violations=0 grandfathered=0 stale=0
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

    declarations="$(awk -F '\t' '$1 == "DECL" { count++ } END { print count + 0 }' "$records")"
    echo "Scanned $declarations top-level declarations in $files main-source files."

    while IFS="$(printf '\t')" read -r rule path line name detail; do
        [ -n "$rule" ] || continue
        key="$rule|$path|$name|"
        if printf '%s\n' "$allow_list" | grep -F -q -- "$key"; then
            grandfathered=$((grandfathered + 1))
            echo "  grandfathered  $rule  $path:$line  $name"
        else
            violations=$((violations + 1))
            echo "  VIOLATION      $rule  $path:$line  $name — $detail"
        fi
    done < "$findings"

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
        if [ "$status" -ne 0 ] && printf '%s\n' "$output" | grep -F -q -- "VIOLATION      $expected_rule" \
            && printf '%s\n' "$output" | grep -F -q -- "$expected_name"; then
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
    expect_failure published-api published-api plantedPublishedMember

    local clean="$work/compliant"
    write_compliant_files "$clean"
    status=0
    output="$(check_tree "$clean" "" 2>&1)" || status=$?
    if [ "$status" -eq 0 ] && printf '%s\n' "$output" | grep -F -q "Result: 0 violation(s)" \
        && printf '%s\n' "$output" | grep -F -q "Scanned 12 top-level declarations"; then
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
        if [ "$status" -eq 2 ] && printf '%s\n' "$output" | grep -F -q -- "nothing was checked" \
            && printf '%s\n' "$output" | grep -F -q -- "$expected_text"; then
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

case "${1:-}" in
    --self-test) run_self_test ;;
    "") check_tree "$REPO_ROOT" "$GRANDFATHERED" ;;
    *) echo "usage: $0 [--self-test]" >&2; exit 2 ;;
esac
