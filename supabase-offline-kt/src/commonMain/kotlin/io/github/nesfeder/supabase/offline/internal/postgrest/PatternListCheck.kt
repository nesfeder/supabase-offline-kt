// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder

/**
 * Refuses a pattern list the server would read as another list, at the top level or in a group.
 * That is an empty list, or a pattern that is empty, `NULL`, edged with white space or holding a
 * brace: supabase-kt quotes a pattern only when it holds one of `,.:()`, and `LIKE ALL` over no
 * pattern matches every row.
 *
 * @throws IllegalArgumentException naming the column and never a pattern.
 */
internal fun requireReadablePatternLists(builder: PostgrestRequestBuilder) {
    builder.params.forEach { (key, values) ->
        val column = values.firstNotNullOfOrNull { value -> misreadPatternList(key, value) }
        if (column != null) {
            throw IllegalArgumentException(
                "a pattern list on `$column` is empty, or holds a pattern that is empty, NULL, " +
                    "edged with white space or holds a brace, which goes out unquoted, so the " +
                    "server would read another list; use single `like` or `ilike` filters"
            )
        }
    }
}

/** The pattern-list operators, as supabase-kt writes them before the list. */
private val PATTERN_LIST_OPERATORS =
    listOf("like(all).{", "like(any).{", "ilike(all).{", "ilike(any).{")

/**
 * The column of the first misread pattern list in parameter [key]'s [value], or `null`. A top-level
 * filter's value is sent as written, so only a group's value is read for lists inside it.
 */
private fun misreadPatternList(key: String, value: String): String? {
    if (!value.startsWith('(')) {
        val operator = PATTERN_LIST_OPERATORS.firstOrNull { value.startsWith(it) } ?: return null
        return key.takeIf {
            endOfReadablePatternList(value, operator.length, isGroup = false) == null
        }
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
            else ->
                index =
                    endOfReadablePatternList(value, index + operator.length, isGroup = true)
                        ?: return value.substring(conditionStart, index - 1)
        }
    }
    return null
}

/** Where the list whose patterns start at [start] ends, or `null` when it would be misread. */
private fun endOfReadablePatternList(value: String, start: Int, isGroup: Boolean): Int? {
    var index = start
    while (index < value.length) {
        if (value[index] == '"') {
            index = endOfQuoted(value, index)
        } else {
            val patternStart = index
            while (index < value.length && value[index] != ',' && value[index] != '}') {
                if (value[index] == '{') return null
                index += if (value[index] == '\\') 2 else 1
            }
            val pattern = value.substring(patternStart, minOf(index, value.length))
            val isMisread = pattern.isEmpty() || pattern.equals("NULL", ignoreCase = true)
            if (isMisread || pattern.first().isWhitespace() || pattern.last().isWhitespace()) {
                return null
            }
        }
        if (index >= value.length || value[index] != ',') break
        index++
    }
    // The list must close here and end its condition: anything else means a pattern held a `}`
    if (index >= value.length || value[index] != '}') return null
    val next = index + 1
    val closesCondition =
        next == value.length ||
            (isGroup && value[next] == ')') ||
            (isGroup && value[next] == ',' && startsCondition(value, next + 1))
    return if (closesCondition) next else null
}

/** Whether a group condition (`column.` or `or(`) starts at [start], not a pattern's remnant. */
private fun startsCondition(value: String, start: Int): Boolean {
    val stop = value.indexOfAny(charArrayOf('.', '(', ',', ')', '{', '}', '"', '\\'), start)
    return stop > start && (value[stop] == '.' || value[stop] == '(')
}

/** The index just past the double-quoted text opening at [start], skipping escaped characters. */
internal fun endOfQuoted(value: String, start: Int): Int {
    var index = start + 1
    while (index < value.length && value[index] != '"') index += if (value[index] == '\\') 2 else 1
    return index + 1
}
