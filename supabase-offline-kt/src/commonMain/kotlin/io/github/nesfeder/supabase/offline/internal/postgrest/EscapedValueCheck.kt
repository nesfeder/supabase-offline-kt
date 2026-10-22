// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder

/**
 * Refuses an `in` element, or a comparison value inside an `or { }` or `and { }` group, that
 * supabase-kt escapes but does not quote, so the server reads the escapes as the value. supabase-kt
 * 3.6 puts a backslash before each `"` and `\` of such a value and quotes it only when it holds one
 * of `,.:()`; PostgREST reads an unquoted element or group value as written, so `isIn("title",
 * listOf("Say \"hi\""))` goes out as `title=in.(Say \"hi\")` and matches nothing, while the same
 * `eq` at the top level, sent as written, matches. Not read: a pattern list and an array literal of
 * `cs`, `cd` or `ov`, which the server reads as array literals whose backslashes are escapes; a
 * range; and a text search query, which `textSearch` sends as written and the request cannot tell
 * from a `filter` with the same operator.
 *
 * @throws IllegalArgumentException naming the column and the operator, never a value.
 */
internal fun requireReadableEscapedValues(builder: PostgrestRequestBuilder) {
    val misread = misreadEscapedValueOf(builder) ?: return
    throw IllegalArgumentException(
        "a value of `${misread.operator}` on `${misread.column}` holds a quote or a backslash " +
            "and none of `,.:()`, which supabase-kt escapes but does not quote, so the server " +
            "would read the escapes as the value; only a single top-level filter sends such a " +
            "value as written"
    )
}

/**
 * The first value of [builder]'s request the server would read with its escapes, as
 * [requireReadableEscapedValues] refuses it, or `null` when every value is read as written.
 */
internal fun misreadEscapedValueOf(builder: PostgrestRequestBuilder): MisreadValue? =
    builder.params.firstNotNullOfOrNull { (key, values) ->
        values.firstNotNullOfOrNull { value -> misreadEscapedValue(key, value) }
    }

/** Where a misread value stands: its column and its operator, as supabase-kt writes them. */
internal class MisreadValue(val column: String, val operator: String)

/**
 * The operators whose value supabase-kt escapes inside a group, by their request names, and `in`,
 * whose elements it escapes everywhere. `isdistinct` is supabase-kt 3.8's, escaped as `eq` is on an
 * app that forces that minor. The text search operators are left out: `textSearch` sends a query as
 * written, and the request cannot tell that call from a `filter` with the same operator.
 */
private val ESCAPED_OPERATORS =
    setOf(
        "eq",
        "neq",
        "gt",
        "gte",
        "lt",
        "lte",
        "like",
        "ilike",
        "match",
        "imatch",
        "is",
        "isdistinct",
        "in",
        "cs",
        "cd",
        "ov",
        "sl",
        "sr",
        "nxl",
        "nxr",
        "adj",
    )

/** Every operator name supabase-kt writes before a value, the text searches included. */
private val OPERATOR_NAMES = ESCAPED_OPERATORS + setOf("fts", "plfts", "phfts", "wfts")

/** The operators whose list is an array literal, `{a,b}`, sent as written. */
private val ARRAY_OPERATORS = setOf("cs", "cd", "ov")

/** The operators a pattern list is written with an argument of, `like(all).{a,b}`. */
private val PATTERN_LIST_OPERATORS = setOf("like", "ilike")

/**
 * The first misread value in parameter [key]'s [value], or `null`. A top-level filter's value is
 * sent as written, so at the top level only an `in` list's elements are read; a group's value is
 * walked for its conditions.
 */
private fun misreadEscapedValue(key: String, value: String): MisreadValue? {
    if (!value.startsWith('(')) {
        val list = value.removePrefix("not.")
        if (!list.startsWith("in.(")) return null
        return MisreadValue(key, "in").takeIf { hasMisreadElement(list, start = "in.(".length) }
    }
    return misreadInGroup(value)
}

/**
 * Whether an element of the list whose elements start at [start] goes out unquoted with a
 * backslash, which only supabase-kt's escape of a `"` or `\` puts there. A quoted element is read
 * by the server with its escapes, as written.
 */
private fun hasMisreadElement(text: String, start: Int): Boolean {
    var index = start
    while (index < text.length) {
        if (text[index] == '"') {
            index = endOfQuoted(text, index)
        } else {
            while (index < text.length && text[index] != ',' && text[index] != ')') {
                if (text[index] == '\\') return true
                index++
            }
        }
        if (index >= text.length || text[index] == ')') return false
        index++
    }
    return false
}

/**
 * The first misread value among the conditions of the group [text], `(a.eq.1,or(b.eq.2,…))`, or
 * `null`. Each condition is `column.operator.value`, with `not.` before a negated operator and an
 * argument after some (`like(all)`, `wfts(english)`); a backslash outside quotes and literals is
 * supabase-kt's escape in a value it did not quote. A quoted value is read by the server with its
 * escapes; a pattern list or an array literal in braces, and a range in brackets or parentheses, as
 * written; an `in` list's elements as a top-level list's are.
 */
private fun misreadInGroup(text: String): MisreadValue? {
    var index = 1
    var conditionStart = 1
    while (index < text.length) {
        when (text[index]) {
            '"' -> index = endOfQuoted(text, index)
            '{' ->
                index =
                    if (opensLiteral(text, conditionStart, index)) endOfBraced(text, index)
                    else index + 1
            '[' ->
                index =
                    if (opensLiteral(text, conditionStart, index)) endOfClosed(text, index, ']')
                    else index + 1
            '(' -> {
                val condition = text.substring(conditionStart, index)
                if (condition.endsWith('.')) {
                    // An `in` list or a range opens: only a list's elements are escaped
                    val parsed = parseCondition(condition.dropLast(1))
                    if (parsed?.operator == "in" && hasMisreadElement(text, index + 1)) {
                        return MisreadValue(parsed.column, "in")
                    }
                    conditionStart = ++index
                } else if (condition.substringAfterLast('.') in OPERATOR_NAMES) {
                    // An operator's argument, `like(all)` or `wfts(english)`: the condition goes on
                    index = endOfClosed(text, index, ')')
                } else {
                    // A nested group
                    conditionStart = ++index
                }
            }
            ',',
            ')' -> conditionStart = ++index
            '\\' -> {
                val parsed = parseCondition(text.substring(conditionStart, index))
                if (parsed != null && !parsed.hasArgument && parsed.operator in ESCAPED_OPERATORS) {
                    return MisreadValue(parsed.column, parsed.operator)
                }
                index += 2
            }
            else -> index++
        }
    }
    return null
}

/** A group condition's column and operator, read up to its value. */
private class ParsedCondition(
    val column: String,
    val operator: String,
    /** Whether the operator carries an argument, as `like(all)` and `wfts(english)` do. */
    val hasArgument: Boolean,
)

/**
 * The column and operator of [condition], a group condition's text up to or into its value, or
 * `null` when no operator is found (a nested group's `or`). The column may hold dots, an embedded
 * table's does, so the operator is the first segment after the first that names one, a `not` before
 * it skipped; whatever follows the operator is the value, which is never read.
 */
private fun parseCondition(condition: String): ParsedCondition? {
    val segments = condition.split('.')
    for (index in 1 until segments.size) {
        val segment = segments[index]
        if (segment == "not") continue
        val name = segment.substringBefore('(')
        if (name !in OPERATOR_NAMES) continue
        val columnEnd = if (index > 1 && segments[index - 1] == "not") index - 1 else index
        return ParsedCondition(
            column = segments.subList(0, columnEnd).joinToString("."),
            operator = name,
            hasArgument = segment.length > name.length,
        )
    }
    return null
}

/**
 * Whether the brace or bracket at [index] opens a literal sent as written: the array literal of an
 * array operator, or the patterns of a pattern list, whose condition runs from [conditionStart].
 */
private fun opensLiteral(text: String, conditionStart: Int, index: Int): Boolean {
    val condition = text.substring(conditionStart, index)
    if (!condition.endsWith('.')) return false
    val parsed = parseCondition(condition.dropLast(1)) ?: return false
    return parsed.operator in ARRAY_OPERATORS ||
        (parsed.hasArgument && parsed.operator in PATTERN_LIST_OPERATORS)
}

/**
 * The index just past the `}` closing the brace opening at [start]: an array literal or a pattern
 * list, whose elements may be quoted, and whose backslashes escape the next character.
 */
private fun endOfBraced(text: String, start: Int): Int {
    var index = start + 1
    var inQuotes = false
    while (index < text.length) {
        when (text[index]) {
            '\\' -> index++
            '"' -> inQuotes = !inQuotes
            '}' -> if (!inQuotes) return index + 1
        }
        index++
    }
    return text.length
}

/** The index just past the next [closing] from [start]: a range, or an operator's argument. */
private fun endOfClosed(text: String, start: Int, closing: Char): Int {
    val end = text.indexOf(closing, start)
    return if (end < 0) text.length else end + 1
}
