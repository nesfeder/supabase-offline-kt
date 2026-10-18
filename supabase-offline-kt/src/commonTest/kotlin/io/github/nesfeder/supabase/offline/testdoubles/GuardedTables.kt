// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.VersionColumns
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * A table with a version column, for the tests of guarded replay: its name, its rows and a store
 * that guards it.
 */

/** The table whose writes are guarded. */
internal const val GUARDED_TABLE: String = "tasks"

/** [GUARDED_TABLE]'s version column. */
internal const val VERSION_COLUMN: String = "updated_at"

/** The cache key a test caches [GUARDED_TABLE]'s rows under. */
internal const val GUARDED_CACHE_KEY: String = "tasks?select=*"

/** A version as PostgREST writes a `timestamptz`, with microseconds and a `+00:00` offset. */
internal const val FIRST_VERSION: String = "2026-10-18T12:00:00.000001+00:00"

/** A later version than [FIRST_VERSION], as another device's write leaves it. */
internal const val MOVED_VERSION: String = "2026-10-18T12:30:00.5+00:00"

/** [VERSION_COLUMN] for [GUARDED_TABLE], and none for any other table. */
internal val GUARDED_VERSION_COLUMNS: VersionColumns = VersionColumns { table ->
    if (table == GUARDED_TABLE) VERSION_COLUMN else null
}

/** A store whose [GUARDED_TABLE] is guarded by [VERSION_COLUMN]; see [newTestStore]. */
internal fun newGuardedStore(
    nowMillis: () -> Long = { 0L },
    primaryKeys: PrimaryKeys = DefaultPrimaryKeys,
    identityProvider: suspend () -> String? = { null },
    identityChanges: Flow<Any?> = flowOf(Unit),
    mutationDao: FakeMutationDao = FakeMutationDao(),
    queryCacheDao: FakeQueryCacheDao = FakeQueryCacheDao(),
): TestStore =
    newTestStore(
        queryCacheDao = queryCacheDao,
        mutationDao = mutationDao,
        nowMillis = nowMillis,
        primaryKeys = primaryKeys,
        identityProvider = identityProvider,
        identityChanges = identityChanges,
        versionColumns = GUARDED_VERSION_COLUMNS,
    )

/** A [GUARDED_TABLE] row. */
internal fun taskRow(
    id: Int,
    title: String,
    done: Boolean = false,
    version: String = FIRST_VERSION,
): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("done", done)
    put(VERSION_COLUMN, version)
}

/** The filter that pins [GUARDED_TABLE]'s row [id]. */
internal fun rowWithId(id: Int): FilterDescriptor =
    FilterDescriptor(conditions = listOf(FilterDescriptor.Condition("id", "eq", id.toString())))

/** Caches [rows] as the answer of [cacheKey], fetched now. */
internal suspend fun TestStore.cacheRows(
    vararg rows: JsonObject,
    cacheKey: String = GUARDED_CACHE_KEY,
    table: String = GUARDED_TABLE,
) {
    queryCache.cacheResponse(
        table,
        cacheKey,
        JsonArray(rows.toList()).toString(),
        requestStartedAtTick = freshness.tickNow(),
    )
}

/** [row]'s [VERSION_COLUMN], as text. */
internal fun versionOf(row: JsonObject): String? = (row[VERSION_COLUMN] as? JsonPrimitive)?.content
