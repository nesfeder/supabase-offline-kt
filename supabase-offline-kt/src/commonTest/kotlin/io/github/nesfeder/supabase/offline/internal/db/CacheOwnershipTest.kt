// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.CacheWriteOutcome
import io.github.nesfeder.supabase.offline.internal.cache.LastSyncedAt
import io.github.nesfeder.supabase.offline.internal.mutationqueue.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.getPayload
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.recordQueryShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okio.Path.Companion.toPath

/**
 * The store's side of a cache that belongs to one identity at a time: what a drop for a new owner
 * empties and what it keeps, and which reads and writes the recorded owner lets through.
 */
class CacheOwnershipTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val cachedFileDao = InMemoryCachedFileDao()
    /** Whose session is current, as the client wires it. */
    private var identity: String? = USER_A

    /** How many times the store asked the client to look at whose the cache is. */
    private var notServed = 0

    /** Whether the cache is one identity's at a time, as with the Auth plugin; read once. */
    private var cacheOwnedPerIdentity = true

    private val store by lazy {
        newTestStore(
            queryCacheDao = queryCacheDao,
            cachedFileDao = cachedFileDao,
            mutationDao = mutationDao,
            isCacheOwnedPerIdentity = cacheOwnedPerIdentity,
            identityProvider = { identity },
            onCacheNotServed = { notServed++ },
        )
    }

    private fun recordedOwner(): String? =
        queryCacheDao.getRow(RESERVED_TABLE_NAME, CACHE_OWNER_CACHE_KEY)?.payload

    private fun ownerRecordOf(owner: String): String = """{"version":1,"owner":"$owner"}"""

    @Test
    fun a_drop_empties_the_cache_and_keeps_the_mutation_queue() = runTest {
        // Given — a cached query with its shape, a Storage row, and a write of A's queued
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ROWS)
        store.recordQueryShape(TABLE, CACHE_KEY, persist = true) {}
        store.addStorage("avatars", "me.png", "/data/storage/avatars/me.png".toPath(), 3, null)
        store.mutationQueue.enqueueOwnedBy(USER_A) {
            enqueueInsert(table = TABLE, body = Json.parseToJsonElement(ROWS) as JsonArray)
        }
        val queueRowsBefore =
            queryCacheDao.rowsOf(RESERVED_TABLE_NAME).filter { row ->
                !row.cacheKey.startsWith(QUERY_SHAPE_CACHE_KEY_PREFIX)
            }

        // When
        store.localData.dropCacheFor(USER_B)

        // Then — the cache is B's and empty; the queue and its records are as they were
        assertEquals(emptyList(), queryCacheDao.rowsOf(TABLE))
        assertEquals(emptyList(), cachedFileDao.all())
        assertNull(store.queryCache.queryShapes.shapeOf(TABLE, CACHE_KEY))
        assertEquals(1, mutationDao.inserted.size)
        assertEquals(
            queueRowsBefore.map { row -> row.cacheKey } + CACHE_OWNER_CACHE_KEY,
            queryCacheDao.rowsOf(RESERVED_TABLE_NAME).map { row -> row.cacheKey },
        )
        assertEquals(ownerRecordOf(USER_B), recordedOwner())
    }

    @Test
    fun a_drop_takes_the_last_synced_record_of_the_identity_before_and_keeps_the_new_owners() =
        runTest {
            // Given — when A's queue was last emptied, kept
            store.lastSyncedAt.save(LastSyncedAt(USER_A, Instant.fromEpochMilliseconds(1_000)))

            // When — the cache passes to B
            store.localData.dropCacheFor(USER_B)

            // Then — A's record went with A's cache
            assertNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY))

            // When — B's queue is emptied, then the cache is made B's again, as a read that found
            // it another's just before the switch asks
            store.lastSyncedAt.save(LastSyncedAt(USER_B, Instant.fromEpochMilliseconds(2_000)))
            store.localData.dropCacheFor(USER_B)

            // Then — B's record stays
            assertEquals(
                """{"version":1,"owner":"$USER_B","syncedAtMs":2000}""",
                queryCacheDao.getRow(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY)?.payload,
            )
        }

    @Test
    fun a_response_or_write_that_began_before_a_drop_is_kept_out_of_the_cache_not_the_queue() =
        runTest {
            // Given — work that started for A before the cache passed to B
            val startedAtTick = store.freshness.tickNow()
            store.localData.dropCacheFor(USER_B)
            identity = USER_B

            // When
            val outcome =
                store.queryCache.cacheResponse(
                    TABLE,
                    CACHE_KEY,
                    ROWS,
                    requestStartedAtTick = startedAtTick,
                )
            val enqueued =
                store.mutationQueue.enqueueOwnedBy(USER_A, startedAtTick) {
                    enqueueInsert(table = TABLE, body = Json.parseToJsonElement(ROWS) as JsonArray)
                }

            // Then — the response is refused; the write still queues, for A
            assertEquals(CacheWriteOutcome.REFUSED_OTHER_OWNER, outcome)
            assertTrue(store.freshness.wasClearedSince(startedAtTick))
            assertTrue(enqueued is EnqueueOutcome.Queued, "$enqueued")
            assertEquals(emptyList(), queryCacheDao.rowsOf(TABLE))
        }

    @Test
    fun a_cache_another_identity_owns_is_neither_read_nor_written() = runTest {
        // Given — A's cache, and B's session
        store.localData.dropCacheFor(USER_A)
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ROWS)
        identity = USER_B

        // When
        val read = store.queryCache.getPayload(TABLE, CACHE_KEY)
        val written = store.queryCache.cacheResponse(TABLE, CACHE_KEY, OTHER_ROWS)

        // Then — neither, and the client was told
        assertNull(read)
        assertEquals(CacheWriteOutcome.REFUSED_OTHER_OWNER, written)
        assertEquals(ROWS, queryCacheDao.getRow(TABLE, CACHE_KEY)?.payload)
        assertTrue(notServed >= 2, "$notServed")
    }

    @Test
    fun a_user_reads_a_cache_nobody_owns_and_claims_it_with_the_first_write() = runTest {
        // Given — a cache an earlier build left without an owner
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ROWS)

        // When — A reads it
        val read = store.queryCache.getPayload(TABLE, CACHE_KEY)

        // Then — served, but not claimed by a read
        assertEquals(ROWS, read)
        assertNull(recordedOwner())

        // When — A writes into it
        store.queryCache.cacheResponse(TABLE, CACHE_KEY, OTHER_ROWS)

        // Then — claimed before the write
        assertEquals(ownerRecordOf(USER_A), recordedOwner())
        assertEquals(OTHER_ROWS, store.queryCache.getPayload(TABLE, CACHE_KEY))
    }

    @Test
    fun nobody_signed_in_neither_reads_nor_claims_a_cache_nobody_owns() = runTest {
        // Given — a cache an earlier build left without an owner, and nobody signed in
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ROWS)
        identity = QueueIdentity.Anonymous.storedValue

        // When
        val read = store.queryCache.getPayload(TABLE, CACHE_KEY)
        val written = store.queryCache.cacheResponse(TABLE, CACHE_KEY, OTHER_ROWS)

        // Then — it may be a user's; the settled session drops it first
        assertNull(read)
        assertEquals(CacheWriteOutcome.REFUSED_OTHER_OWNER, written)
        assertNull(recordedOwner())
    }

    @Test
    fun nobody_signed_in_claims_a_cache_that_holds_nothing() = runTest {
        // Given — nothing cached, and nobody signed in
        identity = QueueIdentity.Anonymous.storedValue

        // When
        val written = store.queryCache.cacheResponse(TABLE, CACHE_KEY, ROWS)

        // Then — claimed, and written: no earlier user's rows are there to protect
        assertEquals(CacheWriteOutcome.WRITTEN, written)
        assertEquals(ownerRecordOf(QueueIdentity.Anonymous.storedValue), recordedOwner())
        assertEquals(0, notServed)
    }

    @Test
    fun an_owner_record_this_build_cannot_read_belongs_to_nobody() = runTest {
        // Given
        queryCacheDao.seedPayload(RESERVED_TABLE_NAME, CACHE_OWNER_CACHE_KEY, """{"version":9}""")
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ROWS)

        // When / Then — not A's, not nobody's
        assertNull(store.queryCache.getPayload(TABLE, CACHE_KEY))
        identity = QueueIdentity.Anonymous.storedValue
        assertNull(store.queryCache.getPayload(TABLE, CACHE_KEY))
    }

    /**
     * Seeds rows an earlier build left, with nobody signed in, runs [empty] and stops it once its
     * transaction has committed, and asks whose the cache is meanwhile, then reads the payload as a
     * reader does after it. Whoever is told the cache is theirs reads it emptied, never the rows
     * the emptying removed.
     */
    private suspend fun TestScope.assertTheOwnerComesWithTheEmptying(empty: suspend () -> Unit) {
        // Given
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ROWS)
        identity = ANONYMOUS
        val committed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        mutationDao.afterTransaction = {
            mutationDao.afterTransaction = null
            committed.complete(Unit)
            resume.await()
        }
        val emptying = launch { empty() }
        committed.await()

        // When — a read asks whose the cache is before the emptying has recorded it
        val check = async {
            store.cacheOwnership.cacheServesCurrentIdentity() to
                queryCacheDao.get(TABLE, CACHE_KEY)?.payload
        }
        runCurrent()
        resume.complete(Unit)
        emptying.join()
        val (served, payloadSeen) = check.await()

        // Then
        assertTrue(!served || payloadSeen == null, "served=$served the rows $payloadSeen")
    }

    @Test
    fun a_read_during_a_drop_told_the_new_owner_reads_the_emptied_cache() = runTest {
        assertTheOwnerComesWithTheEmptying { store.localData.dropCacheFor(ANONYMOUS) }
    }

    @Test
    fun a_read_during_a_clear_told_the_new_owner_reads_the_emptied_cache() = runTest {
        assertTheOwnerComesWithTheEmptying { store.localData.clearLocalData(owner = ANONYMOUS) }
    }

    @Test
    fun a_clear_records_the_owner_it_is_given_and_none_otherwise() = runTest {
        // When / Then
        store.localData.clearLocalData(owner = QueueIdentity.Anonymous.storedValue)
        assertEquals(ownerRecordOf(QueueIdentity.Anonymous.storedValue), recordedOwner())
        store.localData.clearLocalData()
        assertNull(recordedOwner())
    }

    @Test
    fun without_per_identity_ownership_every_read_is_served_and_a_write_records_the_one_identity() =
        runTest {
            // Given — a client without the Auth plugin, and a cache from an earlier build
            cacheOwnedPerIdentity = false
            identity = QueueIdentity.Anonymous.storedValue
            queryCacheDao.seedPayload(
                RESERVED_TABLE_NAME,
                CACHE_OWNER_CACHE_KEY,
                ownerRecordOf(USER_A),
            )
            queryCacheDao.seedPayload(TABLE, CACHE_KEY, ROWS)

            // When / Then — served whoever owns it, never dropped
            assertEquals(ROWS, store.queryCache.getPayload(TABLE, CACHE_KEY))
            assertEquals(
                CacheWriteOutcome.WRITTEN,
                store.queryCache.cacheResponse(TABLE, CACHE_KEY, OTHER_ROWS),
            )
            assertNotNull(queryCacheDao.getRow(TABLE, CACHE_KEY))
            assertEquals(0, notServed)
        }

    private companion object {
        val ANONYMOUS = QueueIdentity.Anonymous.storedValue
        const val TABLE = "notes"
        const val CACHE_KEY = "notes"
        const val ROWS = """[{"id":"n1"}]"""
        const val OTHER_ROWS = """[{"id":"n2"}]"""
        val USER_A = QueueIdentity.User("a").storedValue
        val USER_B = QueueIdentity.User("b").storedValue
    }
}
