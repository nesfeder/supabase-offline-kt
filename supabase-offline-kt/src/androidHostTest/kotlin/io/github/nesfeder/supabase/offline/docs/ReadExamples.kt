// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.Order
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.fetchedAt
import io.github.nesfeder.supabase.offline.hasPendingMutations
import io.github.nesfeder.supabase.offline.isFromCache
import io.github.nesfeder.supabase.offline.offline
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.jsonPrimitive

// The documentation site's Behaviors pages on reading include the sections of this file between
// the `--8<--` markers, so every Kotlin sample there compiles. Nothing here runs.

/** One row of a list, with whether a write that changed it still waits to be sent. */
internal data class ShownTodo(val todo: Todo, val isPending: Boolean)

/** The read-strategies page's four strategies. */
internal suspend fun readStrategies(supabase: SupabaseClient): List<Todo> {
    val todos = supabase.offline.from("todos")
    // --8<-- [start:strategies]
    // The server; the cached rows when it cannot be reached (the default)
    todos.select()

    // The same, but cached rows older than a day count as none
    todos.select(readStrategy = ReadStrategy.NetworkFirst(maxAge = 1.days))

    // The cached rows if they are under five minutes old; the server otherwise
    todos.select(readStrategy = ReadStrategy.CacheFirst(maxAge = 5.minutes))

    // The server only, as supabase-kt reads; its answer is still cached
    todos.select(readStrategy = ReadStrategy.NetworkOnly)
    // --8<-- [end:strategies]
    return todos.select().decodeList<Todo>()
}

/** A read that may use only the cache, and what it throws when the cache cannot answer. */
internal suspend fun readCacheOnly(supabase: SupabaseClient): List<Todo>? {
    // --8<-- [start:cache-only]
    val cached =
        try {
            supabase.offline
                .from("todos")
                .select(readStrategy = ReadStrategy.CacheOnly()) {
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<Todo>()
        } catch (miss: CacheMissException) {
            null // never read online, or a shape the cache does not hold; nothing was sent
        }
    // --8<-- [end:cache-only]
    return cached
}

/** Where a one-shot read's rows came from. */
internal suspend fun readProvenance(supabase: SupabaseClient): String {
    // --8<-- [start:provenance]
    val result = supabase.offline.from("todos").select()
    val source =
        when {
            !result.isFromCache -> "From the server"
            result.hasPendingMutations -> "Saved on this device, with changes not sent yet"
            else -> "Saved on this device, fetched ${result.fetchedAt}"
        }
    // --8<-- [end:provenance]
    return source
}

/** One row offline: read the list with the row's key, and take its first row. */
internal suspend fun readOneRow(supabase: SupabaseClient, id: String): Todo? {
    // --8<-- [start:one-row]
    // single() is never cached; a filtered list is
    val todo =
        supabase.offline
            .from("todos")
            .select { filter { eq("id", id) } }
            .decodeList<Todo>()
            .firstOrNull()
    // --8<-- [end:one-row]
    return todo
}

/** Two conditions on one column: one `and` group, never two top-level filters. */
internal suspend fun readWindow(supabase: SupabaseClient, start: String, end: String): List<Todo> {
    // --8<-- [start:same-column]
    // Refused: supabase-kt would send only the first of the two conditions
    // filter { gte("created_at", start); lt("created_at", end) }

    // Sent as and=(created_at.gte.<start>,created_at.lt.<end>)
    val thisWeek =
        supabase.offline
            .from("todos")
            .select {
                filter {
                    and {
                        gte("created_at", start)
                        lt("created_at", end)
                    }
                }
            }
            .decodeList<Todo>()
    // --8<-- [end:same-column]
    return thisWeek
}

/** The reactive page's three strategies. */
internal fun reactiveStrategies(supabase: SupabaseClient): List<Flow<QuerySnapshot>> {
    val todos = supabase.offline.from("todos")
    // --8<-- [start:reactive-strategies]
    // The cached rows at once, refreshed from the server in parallel (the default)
    todos.selectAsFlow()

    // The server first; the cached rows when it cannot be reached
    todos.selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache)

    // The cached rows only; never a request
    todos.selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly)
    // --8<-- [end:reactive-strategies]
    return listOf(todos.selectAsFlow())
}

/** What a screen says next to rows it may already show, for each refresh state. */
internal fun refreshLabel(snapshot: QuerySnapshot): String? {
    // --8<-- [start:refresh-state]
    val label: String? =
        when (snapshot.refresh) {
            RefreshState.Idle -> null
            RefreshState.Refreshing -> "Updating…"
            RefreshState.Paused -> "Offline: showing what is saved on this device"
            is RefreshState.Stalled -> "Can't reach the server: showing what is saved here"
        }
    // --8<-- [end:refresh-state]
    return label
}

/** A list with each row marked when a queued write changed it. */
internal fun readPendingMarks(supabase: SupabaseClient): Flow<List<ShownTodo>> {
    // --8<-- [start:pending-marks]
    val pendingIds: Flow<Set<String>> =
        supabase.offline.pendingMutations("todos").map { mutations ->
            mutations
                .flatMap { it.rowKeys }
                .mapNotNull { key -> key["id"]?.jsonPrimitive?.content }
                .toSet()
        }
    val shown: Flow<List<ShownTodo>> =
        combine(supabase.offline.from("todos").selectAsFlow(), pendingIds) { snapshot, pending ->
            val todos = snapshot.result?.decodeList<Todo>().orEmpty()
            todos.map { todo -> ShownTodo(todo, isPending = todo.id in pending) }
        }
    // --8<-- [end:pending-marks]
    return shown
}
