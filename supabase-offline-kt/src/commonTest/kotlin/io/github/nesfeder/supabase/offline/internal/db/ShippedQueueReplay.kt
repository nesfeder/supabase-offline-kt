package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.RecordedRequest
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore

/**
 * The row the server holds for the fixture's delta-declared UPDATE when it replays: `done_count`
 * moved from the queued snapshot 4 to 6, and `minutes_spent` from 30 to 40, while the write sat
 * in the queue.
 */
internal const val SHIPPED_UPDATE_TARGET_ON_SERVER: String = """[{"id":2,"done_count":6.0,"minutes_spent":40.0}]"""

/** A server for [replayShippedQueue]: answers reads with [SHIPPED_UPDATE_TARGET_ON_SERVER], writes with `[]`. */
internal fun shippedQueueServer(): RecordingPostgrestServer = RecordingPostgrestServer(
    answer = { request -> if (request.method == "GET") okJson(SHIPPED_UPDATE_TARGET_ON_SERVER) else okJson("[]") },
)

/**
 * Replays every queued write in this database — opened from [ShippedDatabaseFixture] — against
 * [server], through a real `OfflineStore` over the database's own DAOs and the real syncer.
 *
 * @return the number of writes the syncer reports as synced.
 */
internal suspend fun OfflineDatabase.replayShippedQueue(server: RecordingPostgrestServer): Int {
    val store = newTestOfflineStore(objectDao = objectDao(), storageDao = storageDao(), mutationDao = mutationDao())
    return OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()
}

/**
 * Exactly what replaying the shipped queue must put on the wire, in order.
 *
 * Tables replay in the order their oldest write was queued (`todos`, then `list_members`), and each
 * table's writes replay in queue order. The delta-declared UPDATE first re-reads its row with its
 * own filter, then sends the rebased body: `done_count` 6 + (5 - 4) = 7 and `minutes_spent`
 * 40 + (45 - 30) = 55, while `title` replays as queued.
 */
internal val SHIPPED_QUEUE_REPLAY: List<RecordedRequest> = listOf(
    RecordedRequest(
        method = "POST",
        table = "todos",
        query = mapOf("columns" to "id,title"),
        prefer = "return=minimal",
        contentProfile = "public",
        acceptProfile = null,
        body = """[{"id":3,"title":"Water the plants"}]""",
    ),
    RecordedRequest(
        method = "GET",
        table = "todos",
        query = mapOf("select" to "*", "id" to "eq.2", "user_id" to "eq.u-1"),
        prefer = "",
        contentProfile = null,
        acceptProfile = "public",
        body = "",
    ),
    RecordedRequest(
        method = "PATCH",
        table = "todos",
        query = mapOf("id" to "eq.2", "user_id" to "eq.u-1"),
        prefer = "return=minimal",
        contentProfile = "public",
        acceptProfile = null,
        body = """{"title":"Walk the dog twice","done_count":7.0,"minutes_spent":55.0}""",
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
    RecordedRequest(
        method = "POST",
        table = "list_members",
        query = mapOf("on_conflict" to "list_id,user_id", "columns" to "list_id,user_id,role"),
        prefer = "resolution=ignore-duplicates,missing=default,return=minimal",
        contentProfile = "public",
        acceptProfile = null,
        body = """[{"list_id":"l-1","user_id":"u-2","role":"editor"}]""",
    ),
)
