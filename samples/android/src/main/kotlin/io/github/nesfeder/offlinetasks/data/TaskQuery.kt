// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

/** Which tasks the list asks the server for: each is a filter the server applies. */
enum class TaskFilter {
    All,
    Open,
    Done,

    /** Made in the last seven days. */
    Recent,
}

/**
 * The task list's query: a filter and the search text, both sent to the server. Each query is
 * cached on its own, so offline a query never asked online has nothing to show.
 */
data class TaskQuery(val filter: TaskFilter = TaskFilter.All, val search: String = "")
