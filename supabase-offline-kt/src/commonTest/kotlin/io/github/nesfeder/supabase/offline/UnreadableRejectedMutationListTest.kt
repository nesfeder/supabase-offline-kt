// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.REJECTED_MUTATIONS_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Two of the mutation queue's records — the rejected list and the unconfirmed sends — live in
 * reserved cache rows. If one cannot be read (corruption, or a later build's format after a
 * downgrade), the engine degrades instead of wedging the queue, and keeps the payload aside once.
 */
class UnreadableRejectedMutationListTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val store = newTestOfflineStore(queryCacheDao = queryCacheDao)
    private val server = RecordingPostgrestServer()

    @Test
    fun an_unreadable_rejected_list_is_moved_aside_so_the_queue_does_not_wedge() = runTest {
        // Given — an unreadable list, and a queued write the server refuses
        queryCacheDao.seedPayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY, "not a list")
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"refused"}]"""))
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"fine"}]"""))
        server.answer = { request ->
            if ("refused" in request.body) postgrestError(HttpStatusCode.BadRequest)
            else okJson("[]")
        }

        // When
        val report = MutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

        // Then — the refusal is recorded on a fresh list, the next write went out, and the old
        // payload was kept aside rather than discarded
        assertEquals(1, report.syncedCount)
        assertEquals(1, store.rejectedList.rejections().size)
        assertTrue(queryCacheDao.rowsOf(RESERVED_TABLE_NAME).any { it.payload == "not a list" })
    }

    private fun keptAsideCopiesOf(cacheKey: String): Int =
        queryCacheDao.rowsOf(RESERVED_TABLE_NAME).count { row ->
            row.cacheKey.startsWith("$cacheKey.unreadable.")
        }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}
