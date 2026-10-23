// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.jan.supabase.postgrest.query.filter.TextSearchType
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.EngineJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Reads the list values stored as a JSON array of strings. */
private val json: Json = EngineJson

/**
 * Schema for the throwaway builder [replaysFully] replays onto. Never sent anywhere — the builder
 * exists only so the real [replayCondition] branches can report what they applied.
 */
private const val SCRATCH_SCHEMA = "public"

/**
 * Property-conversion method for that same throwaway builder. `CAMEL_CASE_TO_SNAKE_CASE` rather
 * than `SERIAL_NAME`, which is JVM/Android-only and would not link on the iOS target. The value is
 * inert either way: replay addresses columns by their captured `String` names, which no conversion
 * method touches.
 */
private val SCRATCH_CONVERSION = PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE

/**
 * The operators [replayCondition] applies through the generic `filter(column, operator, value)`
 * call, by identifier: supabase-kt has no typed entry point for them, and a name compiles on every
 * supabase-kt minor, including those without the operator (`isdistinct` arrived in 3.8).
 */
internal val GENERICALLY_REPLAYED_OPERATORS: Set<String> = setOf("isdistinct")

/**
 * Replays filters onto a live [PostgrestRequestBuilder].
 *
 * Used by MutationQueueReplayer when syncing UPDATE / DELETE operations.
 *
 * - For [FilterDescriptor.Mode.AND], we just call each condition on the filter DSL: `filter {
 *   eq(...); lt(...); ilike(...) }`
 *
 * - For [FilterDescriptor.Mode.OR], we wrap the conditions in a logical OR block: `filter { or {
 *   eq(...); lt(...); } }`
 *
 * The underlying `PostgrestFilterBuilder` takes care of building the correct `or=(...)` /
 * `and=(...)` query params.
 *
 * @return how many of [FilterDescriptor.conditions] actually landed on [builder]. A count below
 *   `descriptor.conditions.size` means the replayed request targets a broader row set than the
 *   caller captured — see [replaysFully], which is how callers check that.
 */
internal fun replayOnto(
    builder: PostgrestRequestBuilder,
    descriptor: FilterDescriptor,
): Int {
    var applied = 0
    builder.filter {
        when (descriptor.mode) {
            FilterDescriptor.Mode.AND -> {
                descriptor.conditions.forEach { condition ->
                    if (replayCondition(condition)) applied++
                }
            }

            FilterDescriptor.Mode.OR -> {
                or {
                    descriptor.conditions.forEach { condition ->
                        if (replayCondition(condition)) applied++
                    }
                }
            }
        }
    }
    return applied
}

/**
 * Reports whether every condition in [descriptor] survives a replay.
 *
 * A condition whose stored value cannot be parsed back into the shape its operator needs is skipped
 * by [replayCondition]. On the queued UPDATE / DELETE path that silence is dangerous: the mutation
 * still executes, now scoped only by whatever conditions did land (and, in the worst case, by RLS
 * alone). The syncer calls this **before** issuing the request so a short replay can be dropped
 * instead of broadened.
 *
 * The check replays onto a throwaway builder rather than re-deriving the parse rules, so it cannot
 * drift from [replayOnto] when a new operator branch is added. The scratch builder's schema and
 * property-conversion method are arbitrary: replay addresses columns by the `String` names captured
 * in the descriptor, so neither value can change whether a condition lands.
 *
 * A descriptor carrying **no** conditions is reported as not replayable, even though it vacuously
 * loses nothing. [captureFilterExactly] returns `null` rather than an empty descriptor, so an empty
 * one can only come from a corrupted or hand-written stored row — and replaying it produces exactly
 * the unfiltered request the null-descriptor guard already refuses. Treating it as replayable would
 * let it through the one gate meant to catch it.
 *
 * @param descriptor the stored filter about to be replayed.
 * @return true when the descriptor has at least one condition and every one of them applied; false
 *   when it is empty or at least one condition was dropped.
 */
internal fun replaysFully(descriptor: FilterDescriptor): Boolean {
    if (descriptor.conditions.isEmpty()) return false
    val scratch = SelectRequestBuilder(SCRATCH_SCHEMA, SCRATCH_CONVERSION)
    return replayOnto(scratch, descriptor) == descriptor.conditions.size
}

/**
 * The value replaying [descriptor] would send in a form the server reads differently, or `null`: an
 * `in` element holding `"` or `\` and none of `,.:()`, which supabase-kt escapes but does not quote
 * ([misreadEscapedValueOf]). Such a write is refused when it is queued; one queued by an earlier
 * release, which did not refuse it, is found here before it is sent, since its send would match
 * nothing and it would leave the queue as if applied.
 */
internal fun misreadReplayValueOf(descriptor: FilterDescriptor): MisreadValue? {
    val scratch = SelectRequestBuilder(SCRATCH_SCHEMA, SCRATCH_CONVERSION)
    replayOnto(scratch, descriptor)
    return misreadEscapedValueOf(scratch)
}

/**
 * Apply a single condition to the [PostgrestFilterBuilder] using the same DSL that callers would
 * use online.
 *
 * @return true when the condition landed on the builder; false when its stored value could not be
 *   parsed back into the shape the operator needs, or the operator is one this replay does not
 *   name, and the condition was skipped.
 */
@Suppress("REDUNDANT_ELSE_IN_WHEN")
private fun PostgrestFilterBuilder.replayCondition(condition: FilterDescriptor.Condition): Boolean {
    val operator = condition.operator ?: return false
    val rawValue = condition.value

    // The `else` is redundant, hence the suppression, on a minor whose every operator has a branch.
    when (operator) {
        // Basic comparison
        FilterOperator.EQ -> eq(condition.column, rawValue)
        FilterOperator.NEQ -> neq(condition.column, rawValue)
        FilterOperator.GT -> gt(condition.column, rawValue)
        FilterOperator.GTE -> gte(condition.column, rawValue)
        FilterOperator.LT -> lt(condition.column, rawValue)
        FilterOperator.LTE -> lte(condition.column, rawValue)

        // Patterns
        FilterOperator.LIKE -> like(condition.column, rawValue)
        FilterOperator.ILIKE -> ilike(condition.column, rawValue)
        FilterOperator.MATCH -> regexMatch(condition.column, rawValue)
        FilterOperator.IMATCH -> regexIMatch(condition.column, rawValue)

        // As captured, so `not_null` and `unknown` reach the same rows as the caller's filter
        FilterOperator.IS -> filter(condition.column, FilterOperator.IS, rawValue)

        // IN
        FilterOperator.IN -> {
            // A parsed-but-empty list still applies: it re-encodes as the caller's original
            // match-nothing filter (`in.()`). Dropping the condition instead would replay the
            // mutation against every row.
            val list = parseArray(rawValue) ?: return false
            isIn(condition.column, list)
        }

        // Array / JSON operators. Each accepts two encodings — a `{a,b}` element list and an
        // `[a,b]` range — so both must be recognized; see [replayArrayOrRangeCondition].
        FilterOperator.CS ->
            return replayArrayOrRangeCondition(condition, FilterOperator.CS) { list ->
                cs(condition.column, list)
            }

        FilterOperator.CD ->
            return replayArrayOrRangeCondition(condition, FilterOperator.CD) { list ->
                cd(condition.column, list)
            }

        // Ranges
        FilterOperator.SL -> {
            val range = parseRange(rawValue) ?: return false
            sl(condition.column, range)
        }

        FilterOperator.SR -> {
            val range = parseRange(rawValue) ?: return false
            sr(condition.column, range)
        }

        FilterOperator.NXL -> {
            val range = parseRange(rawValue) ?: return false
            nxl(condition.column, range)
        }

        FilterOperator.NXR -> {
            val range = parseRange(rawValue) ?: return false
            nxr(condition.column, range)
        }

        FilterOperator.ADJ -> {
            val range = parseRange(rawValue) ?: return false
            adjacent(condition.column, range)
        }

        FilterOperator.OV ->
            return replayArrayOrRangeCondition(condition, FilterOperator.OV) { list ->
                ov(condition.column, list)
            }

        // Full text search: map onto textSearch + TextSearchType
        FilterOperator.FTS -> textSearch(condition.column, rawValue, TextSearchType.NONE)
        FilterOperator.PLFTS -> textSearch(condition.column, rawValue, TextSearchType.PLAINTO)
        FilterOperator.PHFTS -> textSearch(condition.column, rawValue, TextSearchType.PHRASETO)
        FilterOperator.WFTS -> textSearch(condition.column, rawValue, TextSearchType.WEBSEARCH)

        // Named by identifier, so this compiles on every supabase-kt minor; the generic call
        // encodes them as the caller's `filter(column, operator, value)` did. An operator named
        // nowhere here is skipped, and [replaysFully] reports it: nothing replays broader.
        else -> {
            if (operator.identifier !in GENERICALLY_REPLAYED_OPERATORS) return false
            filter(condition.column, operator, rawValue)
        }
    }
    return true
}

/**
 * Applies one of the array operators (`cs` / `cd` / `ov`), which supabase-kt encodes in two shapes
 * depending on what the caller passed:
 *
 * - a `List` becomes an element list — `cs.{a,b}` (`encodeOverlapAsArray`)
 * - a `Pair` becomes a **range** — `cs.[a,b]` (`encodeOverlapAsRange`)
 *
 * The two are different filters, so the range form must be replayed through the generic
 * `filter(column, operator, Pair)` entry point rather than re-encoded as a list. Before this split
 * the range form fell into [parseArray]'s JSON branch, failed to decode (its tokens are unquoted,
 * so it is not a valid JSON string array), yielded `null`, and the condition was silently dropped —
 * broadening the replayed mutation's target set.
 *
 * @param condition The captured condition.
 * @param operator The operator to re-encode a range form under.
 * @param replayList Applies the element-list form via the operator's typed DSL entry point.
 * @return true when either encoding was recognized and applied; false when the stored value matched
 *   neither, leaving the condition off the request.
 */
private fun PostgrestFilterBuilder.replayArrayOrRangeCondition(
    condition: FilterDescriptor.Condition,
    operator: FilterOperator,
    replayList: (List<Any>) -> Unit,
): Boolean {
    val range = parsePostgrestRangeLiteral(condition.value)
    if (range != null) {
        filter(condition.column, operator, range)
        return true
    }
    val list = parseArray(condition.value) ?: return false
    replayList(list)
    return true
}

/**
 * Recognizes supabase-kt's `[min,max]` range encoding for the array operators.
 *
 * Returns `null` for anything else — including a genuine JSON string array (`["a","b"]`), which
 * stays a list so the older capture form keeps decoding as it always has. That check is what makes
 * the two bracket forms separable: only the JSON one decodes.
 */
private fun parsePostgrestRangeLiteral(raw: String?): Pair<Any, Any>? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.length < 2 || !trimmed.startsWith('[') || !trimmed.endsWith(']')) return null
    if (decodeJsonStringList(trimmed) != null) return null

    // `encodeOverlapAsRange` interpolates both bounds verbatim, so tokenize them verbatim.
    val parts = splitVerbatimElements(trimmed.substring(1, trimmed.length - 1))
    return if (parts.size == 2) parts[0] to parts[1] else null
}

/**
 * The members of an `in` value — `(a,"b,c")`, or a JSON array of strings — read exactly as replay
 * reads them, or `null` when it is neither.
 */
internal fun inListMembersOf(value: String): List<String>? =
    parseArray(value)?.map { member -> member.toString() }

/**
 * Parse Condition.value into a list of strings for array-like operators:
 *
 * - If the string looks like JSON array (`[ "a", "b" ]`), we parse via kotlinx.serialization.
 * - Otherwise, we treat it as a comma-separated list, stripping the PostgREST wrapper the strict
 *   capture preserves from supabase-kt's param encoding: `(a,b,c)` for `in` and `{a,b,c}` for `cs`
 *   / `cd` / `ov`. The two wrappers are tokenized differently because supabase-kt encodes them
 *   differently — see [splitPostgrestListValue].
 *
 * An `in` list keeps every element, an empty one included, as PostgREST's list parser does: `(,a)`
 * and `("",a)` are the empty string and `a`. A list of exactly one empty element, `()` or `("")`,
 * is the empty list, which PostgREST sends as `= ANY('{}')` and which matches no row. In an array
 * literal or a bare list an empty element is dropped.
 *
 * A wrapped-but-empty value (`()` / `{}`) returns an empty list — distinct from `null` — so replay
 * can reproduce the caller's original empty filter instead of dropping the condition and broadening
 * the mutation's target set. `null` means "unparseable"; callers skip the condition only in that
 * case.
 *
 * [rowMatch]'s `in` evaluation parses through here too, so the overlay and the replayed request
 * cannot end up with different element lists for the same stored value.
 */
private fun parseArray(raw: String?): List<Any>? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    // JSON array form
    if (trimmed.startsWith("[")) {
        return decodeJsonStringList(trimmed)
    }

    // Comma-separated form, with or without the PostgREST list wrapper
    val unwrapped = trimmed.stripPostgrestListWrapper()
    val elements = splitPostgrestListValue(trimmed)
    if (trimmed.hasEscapedListWrapper()) {
        return if (elements == ONE_EMPTY_ELEMENT) emptyList() else elements
    }
    val kept = elements.filter { it.isNotEmpty() }
    return when {
        kept.isNotEmpty() -> kept
        unwrapped != trimmed -> emptyList()
        else -> null
    }
}

/**
 * The elements of `()` and `("")`, a list PostgREST reads as the empty list: its renderer sends a
 * list of one empty element as `= ANY('{}')`.
 */
private val ONE_EMPTY_ELEMENT = listOf("")

/** Decodes a JSON array of strings, or `null` when [raw] is not one. */
private fun decodeJsonStringList(raw: String): List<String>? = runCatching {
    json.decodeFromString(ListSerializer(String.serializer()), raw)
}
    .getOrNull()

/**
 * Tokenizes a captured list value, choosing the tokenizer from **how supabase-kt encoded the body**
 * rather than from the comma alone.
 *
 * supabase-kt has exactly one list encoder that escapes its elements — `encodeAsList`, which emits
 * the `in` operator's `(a,b)` and runs every element through `escapeValue`. Every other list or
 * range body is interpolated verbatim: `encodeOverlapAsArray` (`cs`/`cd`/`ov`'s `{a,b}`) joins
 * plain `toString()` output, and `encodeAsRange` / `encodeOverlapAsRange` build
 * `"(${first},${second})"` / `"[${first},${second}]"` by raw string interpolation. Applying the
 * escaped tokenizer to one of those verbatim bodies deletes the element's real `"` and `\`
 * characters and replays a filter that targets different rows — the exact failure class this parser
 * exists to prevent.
 *
 * @param value The captured value, wrapper included.
 * @return the value's elements, wrapper stripped.
 */
private fun splitPostgrestListValue(value: String): List<String> {
    val body = value.stripPostgrestListWrapper()
    return if (value.hasEscapedListWrapper()) {
        splitEscapedElements(body)
    } else {
        splitVerbatimElements(body)
    }
}

/**
 * True when [this] carries the `(…)` wrapper `encodeAsList` emits for `in` values — the one list
 * encoding whose elements went through `escapeValue`.
 *
 * The range operators (`sl` / `sr` / `nxl` / `nxr` / `adj`) also emit `(…)`, but via
 * `encodeAsRange`'s raw interpolation, so they are **not** escaped and must not consult this;
 * [parseRange] tokenizes verbatim unconditionally.
 */
private fun String.hasEscapedListWrapper(): Boolean =
    length >= 2 && startsWith('(') && endsWith(')')

/**
 * Splits a body supabase-kt interpolated verbatim — `{a,b}` (`encodeOverlapAsArray`), `[a,b]`
 * (`encodeOverlapAsRange`) and the range operators' `(a,b)` (`encodeAsRange`).
 *
 * None of those encoders calls `escapeValue`, so every byte between the commas is literal element
 * content: unescaping a `\` or stripping a `"` here would replay a *different* value. The flip side
 * is that an element containing a `,` is unrecoverable — the encoder emitted no marker separating
 * it from a real delimiter, so the loss happens before capture and the parser cannot undo it.
 */
private fun splitVerbatimElements(body: String): List<String> = body.split(',')

/**
 * Splits an unwrapped `in` list body on its element separators, honoring the quoting `escapeValue`
 * applies. This is the **only** body that gets escape handling; see [splitPostgrestListValue] for
 * why.
 *
 * `escapeValue` backslash-escapes `\` and `"`, then wraps the element in double quotes when it
 * contains any of `,` `.` `:` `(` `)`. A naive `split(',')` therefore tears `isIn(col,
 * listOf("a,b"))` — encoded `in.("a,b")` — into `a` and `b` and replays `in.(a,b)`, which matches a
 * different set of rows than the caller asked for. (Before the wrapper strip landed the same value
 * produced inert garbage; correcting the wrapper is what made this leg reachable.)
 *
 * A quoted element is taken verbatim; an unquoted one is trimmed, preserving the previous behavior
 * for the ordinary case. An unterminated quote — which `escapeValue` cannot emit — is closed
 * implicitly at the end of the input rather than rejected, since returning "no elements" would drop
 * the condition and broaden the mutation.
 */
private fun splitEscapedElements(body: String): List<String> {
    val elements = mutableListOf<String>()
    val current = StringBuilder()
    var sawQuotes = false
    var inQuotes = false
    var index = 0

    fun flush() {
        elements += if (sawQuotes) current.toString() else current.toString().trim()
        current.clear()
        sawQuotes = false
    }

    while (index < body.length) {
        val char = body[index]
        when {
            char == '\\' && index + 1 < body.length -> {
                current.append(body[index + 1])
                index += 2
            }

            char == '"' -> {
                inQuotes = !inQuotes
                sawQuotes = true
                index++
            }

            char == ',' && !inQuotes -> {
                flush()
                index++
            }

            else -> {
                current.append(char)
                index++
            }
        }
    }
    flush()
    return elements
}

/**
 * Parse Condition.value into a range represented as Pair<Any, Any>.
 *
 * Supported string forms:
 * - JSON array: `["min","max"]`
 * - Comma-separated, with or without the `(min,max)` wrapper supabase-kt encodes for the range
 *   operators (`sl` / `sr` / `nxl` / `nxr` / `adj`) — the strict capture preserves it.
 *
 * Bounds are tokenized verbatim ([splitVerbatimElements]). `encodeAsRange` builds the value as
 * `"(${range.first},${range.second})"` — raw interpolation, never `escapeValue` — so a bound's `"`
 * and `\` are literal content and have to survive untouched.
 *
 * The unavoidable consequence is that a bound containing a comma is **lossy, and this parser cannot
 * recover it**: `sl("d", "a,b" to "c")` encodes `sl.(a,b,c)`, which is byte-identical to a
 * three-token body, so replay takes the first two tokens and targets the different range
 * `sl.(a,b)`. That loss happens in the encoder, before anything is captured.
 */
private fun parseRange(raw: String?): Pair<Any, Any>? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    // JSON array form
    if (trimmed.startsWith("[")) {
        val list = decodeJsonStringList(trimmed)
        if (list != null && list.size >= 2) {
            return list[0] to list[1]
        }
        // Not JSON — fall through to the bracketed PostgREST range form (`[min,max]`).
    }

    // Comma-separated, stripping the PostgREST range or bracket wrapper if present.
    // Exactly two bounds, never "the first two of however many": `encodeAsRange` emits
    // `(${first},${second})` and nothing else, so a three-token body means a bound itself
    // contained a comma — `sl("d", "a,b" to "c")` encodes as `sl.(a,b,c)`, indistinguishable
    // from a bound of "a" and a bound of "b". There is no way to recover which, and guessing
    // the first two replays a filter the caller never wrote. Returning null instead makes the
    // condition un-replayable, which [replaysFully] turns into a refusal upstream.
    val parts =
        splitVerbatimElements(trimmed.stripPostgrestListWrapper()).filter { it.isNotEmpty() }
    if (parts.size == 2) {
        return parts[0] to parts[1]
    }

    return null
}

/**
 * Strips one matching pair of PostgREST value delimiters: the `(...)` wrapper supabase-kt emits for
 * `in` and range operator values, or the `{...}` wrapper it emits for `cs` / `cd` / `ov` array
 * values. The strict capture stores condition values exactly as encoded, so without this the
 * comma-split leaves a stray delimiter on the first and last element (`"(a"`, `"c)"`) and the
 * replayed filter targets corrupted values. Unwrapped values pass through unchanged.
 */
private fun String.stripPostgrestListWrapper(): String =
    when {
        length >= 2 && startsWith('(') && endsWith(')') -> substring(1, length - 1)
        length >= 2 && startsWith('{') && endsWith('}') -> substring(1, length - 1)
        // `[...]` is `encodeOverlapAsRange`'s wrapper, and [parseRange] is the only caller that
        // reaches it — but only because every other path returns before getting here, not
        // because a `[`-shaped value is impossible. [parseArray] returns at its JSON branch for
        // any `[`-prefixed value (`null` when it is not decodable JSON), the array operators
        // route the range form through [replayArrayOrRangeCondition] first, and [rowMatch]'s
        // `in` evaluation parses via [parseArray] rather than stripping wrappers itself — which
        // is what keeps the overlay from matching the bounds of a value the replay path drops.
        length >= 2 && startsWith('[') && endsWith(']') -> substring(1, length - 1)
        else -> this
    }
