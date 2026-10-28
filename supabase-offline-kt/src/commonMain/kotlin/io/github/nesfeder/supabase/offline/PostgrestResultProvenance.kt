// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.internal.postgrest.fetchedAtStamped
import io.github.nesfeder.supabase.offline.internal.postgrest.hasPendingMutationsStamped
import io.github.nesfeder.supabase.offline.internal.postgrest.isFromCacheStamped
import io.github.nesfeder.supabase.offline.internal.postgrest.pendingRowKeysStamped
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject

/*
 * Where a one-shot read's rows came from, as QuerySnapshot says it for a reactive read. The library
 * stamps it on the result of every read through OfflinePostgrestQueryBuilder.select, in response
 * headers PostgREST never sends; a result from supabase-kt itself carries none, unless a proxy in
 * front of the server adds headers of those names.
 */

/**
 * Whether these rows are the device's cached copy rather than the server's answer to this read: a
 * [ReadStrategy.CacheFirst] or [ReadStrategy.CacheOnly] read the cache answered, or a
 * [ReadStrategy.NetworkFirst] read that fell back to it. `false` for a result supabase-kt returned
 * itself.
 */
public val PostgrestResult.isFromCache: Boolean
    get() = isFromCacheStamped(this)

/**
 * When these rows were fetched from the server, by the device's clock: the time of the fetch, never
 * of a later local change applied to them; for rows the library made from queued writes alone, when
 * it made them. `null` for a result supabase-kt returned itself.
 */
public val PostgrestResult.fetchedAt: Instant?
    get() = fetchedAtStamped(this)

/**
 * Whether a write that waits in the mutation queue changed one of these rows: a row it added,
 * changed or removed. `false` for a result supabase-kt returned itself.
 */
public val PostgrestResult.hasPendingMutations: Boolean
    get() = hasPendingMutationsStamped(this)

/**
 * Which of these rows a write that waits in the mutation queue added, changed or removed, as
 * [QuerySnapshot.pendingRowKeys] says it for a reactive read: the primary key of each, as an object
 * of the table's key columns with the values the row holds. A row such a write removed keeps its
 * key here; a row without its whole key adds none. Read from the pass that laid the writes over
 * these rows. Empty for a result supabase-kt returned itself.
 */
public val PostgrestResult.pendingRowKeys: Set<JsonObject>
    get() = pendingRowKeysStamped(this)
