// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredServerError
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * The engine's own state lives in reserved `object_cache` rows so that the Room schema does not
 * change. Those rows are engine state, not cache: never evicted, never counted towards the cache
 * size, never returned by a read, never touched by an overlay — and wiped with everything else when
 * local data is cleared.
 */
class ReservedRowsTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()

    @Test
    fun the_reserved_name_cannot_be_a_postgres_table() {
        // Postgres truncates identifiers to NAMEDATALEN - 1 = 63 bytes, so no table has this name.
        assertTrue(RESERVED_TABLE_NAME.encodeToByteArray().size > 63)
    }

    @Test
    fun reserved_rows_are_never_evicted_and_never_counted() = runTest {
        // Given — a cache over its limit, holding a rejected list as its oldest row
        val store =
            newTestOfflineStore(
                queryCacheDao = queryCacheDao,
                queryCacheLimits =
                    QueryCacheLimits(
                        maxPayloadBytes = 100,
                        evictionBatchSize = 1,
                        evictionHeadroomBytes = 0,
                    ),
            )
        store.queueOwnedWrite("user:user-a")
        store.rejectedList.addRejection(rejection(id = 1))
        queryCacheDao.seedPayload(
            "todos",
            "todos",
            "[" + "\"x\",".repeat(60) + "\"x\"]",
            updatedAt = 10L,
        )
        val reservedBytes =
            assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
                .payload
                .length

        // When
        store.queryCache.evictOverBudget()

        // Then — the cache row went; the reserved rows stayed and never counted towards the size
        assertNull(queryCacheDao.getRow("todos", "todos"))
        assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
        assertTrue(reservedBytes > 0)
        assertEquals(0L, queryCacheDao.totalPayloadBytes())
    }

    @Test
    fun reserved_rows_are_never_returned_by_a_read() = runTest {
        // Given
        val store = newTestOfflineStore(queryCacheDao = queryCacheDao)
        store.rejectedList.addRejection(rejection(id = 1))

        // When / Then — no read path serves them, even asked for them by name
        assertNull(store.getPayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
        assertNull(
            store.queryCache.getPayloadWithTimestamp(
                RESERVED_TABLE_NAME,
                REJECTED_MUTATIONS_CACHE_KEY,
            )
        )
        assertNull(store.observePayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY).first())
        assertNull(
            store.overlaidPayloadFlow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY).first()
        )
    }

    @Test
    fun reserved_rows_are_never_touched_by_a_write_or_an_overlay() = runTest {
        // Given
        val store = newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)
        store.rejectedList.addRejection(rejection(id = 1))
        val before = queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY)

        // When / Then — every write path refuses the reserved table
        assertFailsWith<IllegalArgumentException> {
            store.patchCachedQueries(
                RESERVED_TABLE_NAME,
                MutationOperation.DELETE,
                "{}",
                filterJson = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.enqueueInsert(RESERVED_TABLE_NAME, Json.parseToJsonElement("[{}]") as JsonArray)
        }
        assertFailsWith<IllegalArgumentException> { store.clearTable(RESERVED_TABLE_NAME) }
        assertFailsWith<IllegalArgumentException> {
            store.queryCache.cacheResponse(
                RESERVED_TABLE_NAME,
                REJECTED_MUTATIONS_CACHE_KEY,
                "[]",
                authoritativeEmpty = true,
            )
        }
        assertEquals(
            before,
            queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY),
        )
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun the_client_refuses_the_reserved_name_as_a_table() {
        // Given
        val client = newTestOfflineClient()

        // When / Then
        assertFailsWith<IllegalArgumentException> { client.from(RESERVED_TABLE_NAME) }
    }

    @Test
    fun clearing_local_data_removes_the_reserved_rows() = runTest {
        // Given
        val store = newTestOfflineStore(queryCacheDao = queryCacheDao)
        store.queueOwnedWrite("user:user-a")
        store.rejectedList.addRejection(rejection(id = 1))

        // When
        store.localData.clearLocalData()

        // Then
        assertNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
        assertEquals(emptyList(), store.rejectedList.rejections())
        assertEquals(emptySet(), store.unconfirmedSends())
    }

    @Test
    fun reserved_rows_record_their_format_version() = runTest {
        // Given
        val store = newTestOfflineStore(queryCacheDao = queryCacheDao)

        // When
        store.rejectedList.addRejection(rejection(id = 1))
        store.queueOwnedWrite("user:user-a")

        // Then — a later build can tell which format it is reading
        val rejected =
            assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
                .payload
        assertTrue(rejected.contains("\"version\":1"), rejected)
    }

    /** Queues a keyed write owned by [owner] and starts its send, which records it in its row. */
    private suspend fun OfflineStore.queueOwnedWrite(owner: String) {
        val queued =
            enqueueOwnedBy(owner) {
                enqueueInsert("todos", Json.parseToJsonElement("""[{"id":"x"}]""") as JsonArray)
            }
        markSendUnconfirmed((queued as EnqueueOutcome.Queued).id)
    }

    private fun rejection(id: Long): StoredRejection =
        StoredRejection(
            id = id,
            table = "todos",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"title":"x"}]""",
            createdAtMs = 0L,
            rejectedAtMs = 1L,
            message = "refused",
            serverError = StoredServerError(status = 400, code = "22P02", message = "refused"),
        )
}
