// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.RecordedRequest
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore

/** A server for [replayShippedQueue]: answers every write with `[]`. */
internal fun shippedQueueServer(): RecordingPostgrestServer =
    RecordingPostgrestServer(answer = { okJson("[]") })

/**
 * Replays every queued write in this database — opened from [ShippedDatabaseFixture] — against
 * [server], through a real `OfflineStore` over the database's own DAOs and the real syncer.
 *
 * @return the number of writes the syncer reports as synced.
 */
internal suspend fun OfflineDatabase.replayShippedQueue(server: RecordingPostgrestServer): Int {
    val store =
        newTestOfflineStore(
            queryCacheDao = queryCacheDao(),
            cachedFileDao = cachedFileDao(),
            mutationDao = mutationDao(),
        )
    return MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()
}

/**
 * Exactly what replaying the shipped queue must put on the wire, in order.
 *
 * Writes replay in the order they were queued (their ids 1 to 4), whatever their table. The INSERT
 * carries its key and its send is recorded as unconfirmed (`send_unconfirmed`), so it replays as an
 * upsert that takes a row already there as its own. The UPDATE replays its body exactly as it was
 * queued.
 */
internal val SHIPPED_QUEUE_REPLAY: List<RecordedRequest> =
    listOf(
        RecordedRequest(
            method = "POST",
            table = "todos",
            query = mapOf("on_conflict" to "id", "columns" to "id,title"),
            prefer = "count=exact,resolution=ignore-duplicates,return=minimal",
            contentProfile = "public",
            acceptProfile = null,
            body = """[{"id":3,"title":"Water the plants"}]""",
        ),
        RecordedRequest(
            method = "POST",
            table = "list_members",
            query = mapOf("on_conflict" to "list_id,user_id", "columns" to "list_id,user_id,role"),
            prefer = "resolution=ignore-duplicates,missing=default,return=minimal",
            contentProfile = "public",
            acceptProfile = null,
            body = """[{"list_id":"l-1","user_id":"u-2","role":"editor"}]""",
        ),
        RecordedRequest(
            method = "PATCH",
            table = "todos",
            query = mapOf("id" to "eq.2", "user_id" to "eq.u-1"),
            prefer = "return=minimal",
            contentProfile = "public",
            acceptProfile = null,
            body = """{"title":"Walk the dog twice","done":true}""",
        ),
        RecordedRequest(
            method = "DELETE",
            table = "todos",
            query = mapOf("or" to "(id.in.(7,8),status.eq.archived)"),
            prefer = "return=minimal",
            contentProfile = "public",
            acceptProfile = null,
            body = "",
        ),
    )
