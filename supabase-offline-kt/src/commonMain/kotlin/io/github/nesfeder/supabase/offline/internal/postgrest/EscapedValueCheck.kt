// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder

/**
 * Refuses an `in` element, or a value inside an `or { }` or `and { }` group, that supabase-kt
 * escapes but does not quote, so the server reads the escapes as the value. supabase-kt 3.6 puts a
 * backslash before each `"` and `\` of such a value and quotes it only when it holds one of
 * `,.:()`; PostgREST reads an unquoted element or group value as written, so `isIn("title",
 * listOf("Say \"hi\""))` goes out as `title=in.(Say \"hi\")` and matches nothing, while the same
 * `eq` at the top level, sent as written, matches. A pattern list is read by the server as an array
 * literal, whose backslashes are escapes, and a text search query goes out as written, so neither
 * is read.
 *
 * @throws IllegalArgumentException naming the column and the operator, never a value.
 */
internal fun requireReadableEscapedValues(builder: PostgrestRequestBuilder) {
    builder.params.forEach { (key, values) ->
        val misread = values.firstNotNullOfOrNull { value -> misreadEscapedValue(key, value) }
        if (misread != null) {
            throw IllegalArgumentException(
                "a value of `${misread.operator}` on `${misread.column}` holds a quote or a " +
                    "backslash and none of `,.:()`, which supabase-kt escapes but does not quote, " +
                    "so the server would read the escapes as the value; only a single top-level " +
                    "filter sends such a value as written"
            )
        }
    }
}

/** Where a misread value stands: its column and its operator, as supabase-kt writes them. */
private class MisreadValue(val column: String, val operator: String)

/**
 * The operators whose value supabase-kt escapes inside a group, by their request names, and `in`,
 * whose elements it escapes everywhere. A text search's query is left out: `textSearch` sends it as
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

/**
 * What ends a group condition whose value opens a literal supabase-kt sends as written: an array or
 * a range of the array operators, or the patterns of a pattern list.
 */
private val LITERAL_OPENERS = listOf(".cs.", ".cd.", ".ov.", "(all).", "(any).")

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
 * `null`. Each condition is `column.operator.value`, with `not.` before a negated operator; a
 * backslash outside quotes and literals is supabase-kt's escape in a value it did not quote. A
 * quoted value is read by the server with its escapes, a literal in braces or brackets as written;
 * an `in` list's elements are read as a top-level list's are.
 */
private fun misreadInGroup(text: String): MisreadValue? {
    var index = 1
    var conditionStart = 1
    while (index < text.length) {
        val condition = text.substring(conditionStart, index)
        when (text[index]) {
            '"' -> index = endOfQuoted(text, index)
            '{' -> index = if (opensLiteral(condition)) endOfBraced(text, index) else index + 1
            '[' -> index = if (opensLiteral(condition)) endOfBracketed(text, index) else index + 1
            '(' -> {
                if (condition.endsWith(".in.") && hasMisreadElement(text, index + 1)) {
                    return MisreadValue(columnOf(condition.dropLast(1)), "in")
                }
                conditionStart = ++index
            }
            ',',
            ')' -> conditionStart = ++index
            '\\' -> {
                val operator = escapedOperatorOf(condition)
                if (operator != null) {
                    return MisreadValue(columnOf(condition.substringBeforeLast('.')), operator)
                }
                index += 2
            }
            else -> index++
        }
    }
    return null
}

/** Whether [condition], read up to a brace or bracket, opens a literal sent as written. */
private fun opensLiteral(condition: String): Boolean = LITERAL_OPENERS.any { opener ->
    condition.endsWith(opener)
}

/**
 * The operator of [condition], the text of a group condition up to a backslash in its value, when
 * it is one whose value supabase-kt escapes; `null` otherwise. The value before the backslash holds
 * no `.` (one would have had the value quoted), so the operator is the segment before the last.
 */
private fun escapedOperatorOf(condition: String): String? {
    val segments = condition.split('.')
    if (segments.size < 3) return null
    return segments[segments.size - 2].takeIf { it in ESCAPED_OPERATORS }
}

/** The column of [conditionWithoutValue], `column.operator` or `column.not.operator`. */
private fun columnOf(conditionWithoutValue: String): String =
    conditionWithoutValue.substringBeforeLast('.').removeSuffix(".not")

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

/** The index just past the `]` closing the bracket opening at [start]: a range, sent as written. */
private fun endOfBracketed(text: String, start: Int): Int {
    val end = text.indexOf(']', start)
    return if (end < 0) text.length else end + 1
}
