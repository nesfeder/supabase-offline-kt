// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.nesfeder.offlinetasks.data.TaskFilter
import io.github.nesfeder.offlinetasks.data.TaskQuery
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The column every task and photo is ordered by, which the app stamps on insert. */
internal const val CREATED_AT = "created_at"

/** The key column of both tables. */
internal const val ID = "id"

/** The owner column of both tables. */
internal const val USER_ID = "user_id"

/** The column that ties a photo to its task. */
internal const val TASK_ID = "task_id"

/** The photo row's column naming its file in the bucket. */
internal const val OBJECT_PATH = "object_path"

/** A task's title. */
internal const val TITLE = "title"

/** Whether a task is done. */
internal const val DONE = "done"

/** How many days the Recent filter reaches back, today included. */
private const val RECENT_DAYS = 7

/** A day, in seconds. */
private const val SECONDS_PER_DAY = 86_400L

/**
 * The Recent filter's window: whole days in UTC, from six days ago to the end of today. Its bounds
 * change once a day, so the query, and its place in the cache, does too.
 */
internal data class RecentWindow(val from: Instant, val until: Instant)

/** The Recent window [now] falls in. Read before a verb: its request must not read the clock. */
internal fun recentWindow(now: Instant): RecentWindow {
    val startOfToday =
        Instant.fromEpochSeconds(now.epochSeconds - now.epochSeconds.mod(SECONDS_PER_DAY))
    return RecentWindow(from = startOfToday - (RECENT_DAYS - 1).days, until = startOfToday + 1.days)
}

/**
 * The one order every list is read in, newest first. Each query is cached apart, so every read of
 * a table that should share one cached list asks the same query.
 */
internal fun PostgrestRequestBuilder.newestFirst() {
    order(CREATED_AT, Order.DESCENDING)
}

/** The task list's [query] as a request: the chip and the search as filters, then the order. */
internal fun PostgrestRequestBuilder.tasksMatching(query: TaskQuery, recent: RecentWindow) {
    filter {
        chip(query.filter, recent)
        val search = query.search.trim()
        if (search.isNotEmpty()) titleContains(search)
    }
    newestFirst()
}

// --8<-- [start:filters]
/** The chips as filters the server applies; the overlay evaluates `eq` and `is` offline too. */
private fun PostgrestFilterBuilder.chip(filter: TaskFilter, recent: RecentWindow) {
    when (filter) {
        TaskFilter.All -> Unit
        TaskFilter.Open -> eq(DONE, false) // done=eq.false
        TaskFilter.Done -> exact(DONE, true) // done=is.true
        TaskFilter.Recent -> madeIn(recent)
    }
}
// --8<-- [end:filters]

// --8<-- [start:same-column]
/**
 * Made within [window]: two conditions on one column, in one `and` group. Written as two top-level
 * filters, supabase-kt would send only the first, so the library refuses them.
 */
private fun PostgrestFilterBuilder.madeIn(window: RecentWindow) {
    and {
        gte(CREATED_AT, window.from.toString())
        lt(CREATED_AT, window.until.toString())
    }
}
// --8<-- [end:same-column]

// --8<-- [start:search]
/** Titles containing [text], ignoring case; a `%`, `_` or `\` in it matches only itself. */
private fun PostgrestFilterBuilder.titleContains(text: String) {
    val literal = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    ilike(TITLE, "%$literal%")
}
// --8<-- [end:search]
