// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder

/**
 * Refuses an empty pattern list, at the top level or in a group. supabase-kt writes it as `{}`, and
 * `LIKE ALL` over no pattern matches every row, so `likeAll("title", emptyList())` would read, or
 * queue a write against, the whole table. `likeAny` and `ilikeAny` over no pattern match nothing,
 * so only the `ALL` forms need the refusal; it covers all four on purpose, since a list built at
 * run time that comes out empty is almost certainly not what the app meant, and one rule is easier
 * to state than two.
 *
 * A pattern itself is read as written: supabase-kt quotes one that is empty, `NULL`, edged with
 * white space or holds one of `, { } " \`, which is how Postgres reads an array literal.
 *
 * @throws IllegalArgumentException naming the column.
 */
internal fun requireReadablePatternLists(builder: PostgrestRequestBuilder) {
    builder.params.forEach { (key, values) ->
        val column = values.firstNotNullOfOrNull { value -> emptyPatternList(key, value) }
        if (column != null) {
            throw IllegalArgumentException(
                "a pattern list on `$column` is empty, and `LIKE ALL` over no pattern matches " +
                    "every row; pass at least one pattern, or leave the filter out"
            )
        }
    }
}

/** The pattern-list operators, as supabase-kt writes them before the list. */
private val PATTERN_LIST_OPERATORS =
    listOf("like(all).{", "like(any).{", "ilike(all).{", "ilike(any).{")

/**
 * The column of the first empty pattern list in parameter [key]'s [value], or `null`. A top-level
 * filter's value is the list alone; a group's value is walked for the conditions inside it, past
 * quoted text and the patterns of every list that is not empty.
 */
private fun emptyPatternList(key: String, value: String): String? {
    if (!value.startsWith('(')) {
        return key.takeIf { PATTERN_LIST_OPERATORS.any { operator -> value == "$operator}" } }
    }
    var index = 0
    var conditionStart = 0
    while (index < value.length) {
        val operator = PATTERN_LIST_OPERATORS.firstOrNull { value.startsWith(it, index) }
        when {
            value[index] == '"' -> index = endOfQuoted(value, index)
            value[index] == '\\' -> index += 2
            value[index] == '(' || value[index] == ',' -> conditionStart = ++index
            operator == null || value[index - 1] != '.' -> index++
            else -> {
                val patterns = index + operator.length
                val next = patterns + 1
                val closesEmpty =
                    value.startsWith("}", patterns) &&
                        (next == value.length || value[next] == ')' || value[next] == ',')
                if (closesEmpty) return value.substring(conditionStart, index - 1)
                index = endOfPatterns(value, patterns)
            }
        }
    }
    return null
}

/**
 * The index just past the `}` closing the patterns that start at [start], whose quoted patterns may
 * hold any character and whose backslashes escape the next one.
 */
private fun endOfPatterns(value: String, start: Int): Int {
    var index = start
    while (index < value.length && value[index] != '}') {
        index =
            when (value[index]) {
                '"' -> endOfQuoted(value, index)
                '\\' -> index + 2
                else -> index + 1
            }
    }
    return index + 1
}

/** The index just past the double-quoted text opening at [start], skipping escaped characters. */
internal fun endOfQuoted(value: String, start: Int): Int {
    var index = start + 1
    while (index < value.length && value[index] != '"') index += if (value[index] == '\\') 2 else 1
    return index + 1
}
