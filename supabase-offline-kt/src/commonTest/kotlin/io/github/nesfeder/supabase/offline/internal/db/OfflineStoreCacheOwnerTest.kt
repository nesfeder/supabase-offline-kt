// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryStorageDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.recordQueryShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
class OfflineStoreCacheOwnerTest {

    private val objectDao = FakeObjectDao()
    private val mutationDao = FakeMutationDao()
    private val storageDao = InMemoryStorageDao()
    private val store =
        newTestOfflineStore(
            objectDao = objectDao,
            storageDao = storageDao,
            mutationDao = mutationDao,
        )

    /** Whose session is current, as the client wires it. */
    private var identity: String? = USER_A

    /** How many times the store asked the client to look at whose the cache is. */
    private var notServed = 0

    init {
        store.cacheOwnedPerIdentity = true
        store.identityProvider = { identity }
        store.onCacheNotServed = { notServed++ }
    }

    private fun recordedOwner(): String? =
        objectDao.getRow(RESERVED_TABLE_NAME, CACHE_OWNER_SIGNATURE)?.payload

    private fun ownerRecordOf(owner: String): String = """{"version":1,"owner":"$owner"}"""

    @Test
    fun a_drop_empties_the_cache_and_keeps_the_mutation_queue() = runTest {
        // Given — a cached query with its shape, a Storage row, and a write of A's queued
        objectDao.seedPayload(TABLE, SIGNATURE, ROWS)
        store.recordQueryShape(TABLE, SIGNATURE, persist = true) {}
        store.addStorage("avatars", "me.png", "/data/storage/avatars/me.png".toPath(), 3, null)
        store.enqueueOwnedBy(USER_A) {
            enqueueInsert(table = TABLE, body = Json.parseToJsonElement(ROWS) as JsonArray)
        }
        val queueRowsBefore =
            objectDao.rowsOf(RESERVED_TABLE_NAME).filter { row ->
                !row.signature.startsWith(QUERY_SHAPE_SIGNATURE_PREFIX)
            }

        // When
        store.dropCacheFor(USER_B)

        // Then — the cache is B's and empty; the queue and its records are as they were
        assertEquals(emptyList(), objectDao.rowsOf(TABLE))
        assertEquals(emptyList(), storageDao.all())
        assertNull(store.queryShapes.shapeOf(TABLE, SIGNATURE))
        assertEquals(1, mutationDao.inserted.size)
        assertEquals(
            queueRowsBefore.map { row -> row.signature } + CACHE_OWNER_SIGNATURE,
            objectDao.rowsOf(RESERVED_TABLE_NAME).map { row -> row.signature },
        )
        assertEquals(ownerRecordOf(USER_B), recordedOwner())
    }

    @Test
    fun a_response_or_write_that_began_before_a_drop_is_kept_out_of_the_cache_not_the_queue() =
        runTest {
            // Given — work that started for A before the cache passed to B
            val startedAtTick = store.monotonicTickNow()
            store.dropCacheFor(USER_B)
            identity = USER_B

            // When
            val outcome =
                store.addPayload(TABLE, SIGNATURE, ROWS, requestStartedAtTick = startedAtTick)
            val enqueued =
                store.enqueueOwnedBy(USER_A, startedAtTick) {
                    enqueueInsert(table = TABLE, body = Json.parseToJsonElement(ROWS) as JsonArray)
                }

            // Then — the response is refused; the write still queues, for A
            assertEquals(AddPayloadOutcome.DROPPED_OTHER_OWNER, outcome)
            assertTrue(store.wasClearedSince(startedAtTick))
            assertTrue(enqueued is EnqueueOutcome.Queued, "$enqueued")
            assertEquals(emptyList(), objectDao.rowsOf(TABLE))
        }

    @Test
    fun a_cache_another_identity_owns_is_neither_read_nor_written() = runTest {
        // Given — A's cache, and B's session
        store.dropCacheFor(USER_A)
        objectDao.seedPayload(TABLE, SIGNATURE, ROWS)
        identity = USER_B

        // When
        val read = store.getPayload(TABLE, SIGNATURE)
        val written = store.addPayload(TABLE, SIGNATURE, OTHER_ROWS)

        // Then — neither, and the client was told
        assertNull(read)
        assertEquals(AddPayloadOutcome.DROPPED_OTHER_OWNER, written)
        assertEquals(ROWS, objectDao.getRow(TABLE, SIGNATURE)?.payload)
        assertTrue(notServed >= 2, "$notServed")
    }

    @Test
    fun a_user_reads_a_cache_nobody_owns_and_claims_it_with_the_first_write() = runTest {
        // Given — a cache an earlier build left without an owner
        objectDao.seedPayload(TABLE, SIGNATURE, ROWS)

        // When — A reads it
        val read = store.getPayload(TABLE, SIGNATURE)

        // Then — served, but not claimed by a read
        assertEquals(ROWS, read)
        assertNull(recordedOwner())

        // When — A writes into it
        store.addPayload(TABLE, SIGNATURE, OTHER_ROWS)

        // Then — claimed before the write
        assertEquals(ownerRecordOf(USER_A), recordedOwner())
        assertEquals(OTHER_ROWS, store.getPayload(TABLE, SIGNATURE))
    }

    @Test
    fun nobody_signed_in_neither_reads_nor_claims_a_cache_nobody_owns() = runTest {
        // Given — a cache an earlier build left without an owner, and nobody signed in
        objectDao.seedPayload(TABLE, SIGNATURE, ROWS)
        identity = QueueIdentity.Anonymous.storedValue

        // When
        val read = store.getPayload(TABLE, SIGNATURE)
        val written = store.addPayload(TABLE, SIGNATURE, OTHER_ROWS)

        // Then — it may be a user's; the settled session drops it first
        assertNull(read)
        assertEquals(AddPayloadOutcome.DROPPED_OTHER_OWNER, written)
        assertNull(recordedOwner())
    }

    @Test
    fun nobody_signed_in_claims_a_cache_that_holds_nothing() = runTest {
        // Given — nothing cached, and nobody signed in
        identity = QueueIdentity.Anonymous.storedValue

        // When
        val written = store.addPayload(TABLE, SIGNATURE, ROWS)

        // Then — claimed, and written: no earlier user's rows are there to protect
        assertEquals(AddPayloadOutcome.WRITTEN, written)
        assertEquals(ownerRecordOf(QueueIdentity.Anonymous.storedValue), recordedOwner())
        assertEquals(0, notServed)
    }

    @Test
    fun an_owner_record_this_build_cannot_read_belongs_to_nobody() = runTest {
        // Given
        objectDao.seedPayload(RESERVED_TABLE_NAME, CACHE_OWNER_SIGNATURE, """{"version":9}""")
        objectDao.seedPayload(TABLE, SIGNATURE, ROWS)

        // When / Then — not A's, not nobody's
        assertNull(store.getPayload(TABLE, SIGNATURE))
        identity = QueueIdentity.Anonymous.storedValue
        assertNull(store.getPayload(TABLE, SIGNATURE))
    }

    /**
     * Seeds rows an earlier build left, with nobody signed in, runs [empty] and stops it once its
     * transaction has committed, and asks whose the cache is meanwhile. Whoever is told the cache
     * is theirs must also see the emptying counted, or a reader serves a payload it received
     * before.
     */
    private suspend fun TestScope.assertTheOwnerComesWithTheEmptying(empty: suspend () -> Unit) {
        // Given
        objectDao.seedPayload(TABLE, SIGNATURE, ROWS)
        identity = ANONYMOUS
        val committed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        mutationDao.afterTransaction = {
            mutationDao.afterTransaction = null
            committed.complete(Unit)
            resume.await()
        }
        val countBefore = store.cacheOwner.emptiedCount()
        val emptying = launch { empty() }
        committed.await()

        // When — a read asks whose the cache is before the emptying has recorded it
        val check = async { store.cacheServesCurrentIdentity() to store.cacheOwner.emptiedCount() }
        runCurrent()
        resume.complete(Unit)
        emptying.join()
        val (served, countSeen) = check.await()

        // Then
        assertTrue(!served || countSeen > countBefore, "served=$served at count $countSeen")
    }

    @Test
    fun a_read_during_a_drop_is_told_the_new_owner_only_with_the_drop_counted() = runTest {
        assertTheOwnerComesWithTheEmptying { store.dropCacheFor(ANONYMOUS) }
    }

    @Test
    fun a_read_during_a_clear_is_told_the_new_owner_only_with_the_clear_counted() = runTest {
        assertTheOwnerComesWithTheEmptying { store.clearAll(owner = ANONYMOUS) }
    }

    @Test
    fun a_clear_records_the_owner_it_is_given_and_none_otherwise() = runTest {
        // When / Then
        store.clearAll(owner = QueueIdentity.Anonymous.storedValue)
        assertEquals(ownerRecordOf(QueueIdentity.Anonymous.storedValue), recordedOwner())
        store.clearAll()
        assertNull(recordedOwner())
    }

    @Test
    fun without_per_identity_ownership_every_read_is_served_and_a_write_records_the_one_identity() =
        runTest {
            // Given — a client without the Auth plugin, and a cache from an earlier build
            store.cacheOwnedPerIdentity = false
            identity = QueueIdentity.Anonymous.storedValue
            objectDao.seedPayload(RESERVED_TABLE_NAME, CACHE_OWNER_SIGNATURE, ownerRecordOf(USER_A))
            objectDao.seedPayload(TABLE, SIGNATURE, ROWS)

            // When / Then — served whoever owns it, never dropped
            assertEquals(ROWS, store.getPayload(TABLE, SIGNATURE))
            assertEquals(AddPayloadOutcome.WRITTEN, store.addPayload(TABLE, SIGNATURE, OTHER_ROWS))
            assertNotNull(objectDao.getRow(TABLE, SIGNATURE))
            assertEquals(0, notServed)
        }

    private companion object {
        val ANONYMOUS = QueueIdentity.Anonymous.storedValue
        const val TABLE = "notes"
        const val SIGNATURE = "notes"
        const val ROWS = """[{"id":"n1"}]"""
        const val OTHER_ROWS = """[{"id":"n2"}]"""
        val USER_A = QueueIdentity.User("a").storedValue
        val USER_B = QueueIdentity.User("b").storedValue
    }
}
