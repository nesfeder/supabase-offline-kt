// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.CONFLICTED_MUTATIONS_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.db.storedName
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.testdoubles.CountingSerializer
import io.github.nesfeder.supabase.offline.testdoubles.FIRST_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.GUARDED_TABLE
import io.github.nesfeder.supabase.offline.testdoubles.MOVED_VERSION
import io.github.nesfeder.supabase.offline.testdoubles.newGuardedStore
import io.github.nesfeder.supabase.offline.testdoubles.taskRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The conflicted list: one reserved row that survives a reload, bounded as the rejected list is
 * from each entry's size kept beside it, shown per user, re-queued preconditioned on the server
 * version it recorded, and cleared with local data.
 */
class ConflictedListTest {

    private var identity: String? = USER_A
    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store =
        newGuardedStore(
            nowMillis = { NOW },
            identityProvider = { identity },
            mutationDao = mutationDao,
            queryCacheDao = queryCacheDao,
        )

    @Test
    fun a_parked_write_round_trips_through_its_reserved_row() = runTest {
        // Given
        val parked = park(queuedUpdate(owner = USER_A))

        // When — read back by a store that loads the row afresh
        val reloaded =
            newGuardedStore(mutationDao = mutationDao, queryCacheDao = queryCacheDao)
                .mutationQueue
                .conflictedList
                .conflicts()

        // Then — every field the row held, its bases included, and what the server had
        assertEquals(listOf(parked), reloaded)
        val entry = reloaded.single()
        assertEquals(FIRST_VERSION, entry.baseVersion)
        assertEquals(MOVED_VERSION, entry.serverVersion)
        assertEquals(listOf("title"), entry.conflictingColumns)
        assertEquals(NOW, entry.conflictedAtMs)
        assertEquals(0, store.mutationQueue.allPendingMutations().size)
        assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, CONFLICTED_MUTATIONS_CACHE_KEY))
    }

    @Test
    fun the_list_keeps_the_newest_entries_within_the_rejected_lists_bounds() = runTest {
        // Given / When — one more than the list keeps, and one body too long to keep
        repeat(MAX_REJECTED_MUTATIONS + 1) { park(queuedUpdate(owner = USER_A)) }
        val oversized =
            park(queuedUpdate(owner = USER_A, title = "x".repeat(MAX_REJECTED_BODY_CHARS)))

        // Then
        val entries = store.mutationQueue.conflictedList.conflicts()
        assertEquals(MAX_REJECTED_MUTATIONS, entries.size)
        assertEquals(oversized.id, entries.last().id)
        assertNotNull(oversized.bodyOmittedChars)
        assertEquals(
            ConflictRequeue.Refused,
            store.mutationQueue.requeueConflict(oversized.id, USER_A, ConflictDecision.KeepLocal),
        )
    }

    @Test
    fun the_list_stays_within_its_total_size_and_counts_what_it_dropped() = runTest {
        // Given / When — forty entries of nearly the longest body kept, over the list's bound
        repeat(40) {
            park(queuedUpdate(owner = USER_A, title = "x".repeat(MAX_REJECTED_BODY_CHARS - 100)))
        }

        // Then — the oldest left, each counted, and what is kept fits the bound
        val payload =
            assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, CONFLICTED_MUTATIONS_CACHE_KEY))
                .payload
        val list = EngineJson.decodeFromString(StoredConflictList.serializer(), payload)
        assertTrue(list.entries.size < 40, "kept ${list.entries.size}")
        assertEquals(40L - list.entries.size, list.droppedCount)
        assertTrue(payload.length <= MAX_REJECTED_LIST_CHARS + 256, "${payload.length} characters")
        assertEquals(40L, list.entries.last().id)
    }

    @Test
    fun a_gone_row_cannot_be_requeued_with_the_local_change() = runTest {
        // Given — a write whose row was gone when it was parked
        val row = queuedUpdate(owner = USER_A)
        val parked =
            store.mutationQueue.parkConflictedMutation(
                row,
                ParkedConflict(null, null, emptySet(), emptySet(), "gone"),
            )

        // When
        val outcome =
            store.mutationQueue.requeueConflict(parked.id, USER_A, ConflictDecision.KeepLocal)

        // Then — it would replay unguarded and change nothing: refused, kept on the list
        assertEquals(ConflictRequeue.Refused, outcome)
        assertEquals(listOf(parked), store.mutationQueue.conflictedList.conflicts())
    }

    @Test
    fun each_user_is_shown_and_may_settle_only_their_own_entries() = runTest {
        // Given
        val mine = park(queuedUpdate(owner = USER_A))
        val theirs = park(queuedUpdate(owner = USER_B))

        // When
        val shown = store.mutationQueue.shownConflictsFlow().first()
        val counts = store.mutationQueue.shownQueueCounts()
        val dismissedTheirs = store.mutationQueue.dismissConflictOf(theirs.id, USER_A)
        val requeuedTheirs =
            store.mutationQueue.requeueConflict(theirs.id, USER_A, ConflictDecision.KeepLocal)

        // Then
        assertEquals(listOf(mine), shown)
        assertEquals(1, counts.conflicted)
        assertFalse(dismissedTheirs)
        assertEquals(ConflictRequeue.NotFound, requeuedTheirs)
        assertTrue(store.mutationQueue.dismissConflictOf(mine.id, USER_A))
        assertEquals(listOf(theirs), store.mutationQueue.conflictedList.conflicts())
    }

    @Test
    fun keeping_the_local_change_requeues_it_at_the_tail_against_the_recorded_server_version() =
        runTest {
            // Given
            val parked = park(queuedUpdate(owner = USER_A))

            // When
            val requeued =
                store.mutationQueue.requeueConflict(parked.id, USER_A, ConflictDecision.KeepLocal)

            // Then — a new id, the same write, preconditioned on what the app saw on the server
            val row =
                assertNotNull(mutationDao.byId(assertIs<ConflictRequeue.Requeued>(requeued).id))
            assertTrue(row.id > parked.id)
            assertEquals(parked.bodyJson, row.bodyJson)
            assertEquals(parked.filterJson, row.filterJson)
            assertEquals(USER_A, row.owner)
            assertEquals(MOVED_VERSION, row.baseVersion)
            assertEquals("""{"title":"elsewhere","updated_at":"$MOVED_VERSION"}""", row.baseJson)
            assertEquals(emptyList(), store.mutationQueue.conflictedList.conflicts())
        }

    @Test
    fun applying_columns_requeues_them_as_the_body_and_keeping_the_server_only_removes_it() =
        runTest {
            // Given
            val applied = park(queuedUpdate(owner = USER_A))
            val dropped = park(queuedUpdate(owner = USER_A))
            val held = park(queuedUpdate(owner = USER_A))

            // When
            val merged = JsonObject(mapOf("title" to JsonPrimitive("merged")))
            val appliedId =
                store.mutationQueue.requeueConflict(
                    applied.id,
                    USER_A,
                    ConflictDecision.Apply(merged),
                )
            val droppedId =
                store.mutationQueue.requeueConflict(dropped.id, USER_A, ConflictDecision.KeepServer)
            val heldId = store.mutationQueue.requeueConflict(held.id, USER_A, ConflictDecision.Hold)

            // Then
            val appliedRow = assertIs<ConflictRequeue.Requeued>(appliedId)
            assertEquals(merged.toString(), mutationDao.byId(appliedRow.id)?.bodyJson)
            assertEquals(ConflictRequeue.Dropped, droppedId)
            assertEquals(ConflictRequeue.Refused, heldId)
            assertEquals(listOf(held), store.mutationQueue.conflictedList.conflicts())
            assertEquals(1, store.mutationQueue.allPendingMutations().size)
        }

    @Test
    fun clearing_local_data_clears_the_list() = runTest {
        // Given
        park(queuedUpdate(owner = USER_A))

        // When
        store.localData.clearLocalData()

        // Then
        assertEquals(emptyList(), store.mutationQueue.conflictedList.conflicts())
        assertNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, CONFLICTED_MUTATIONS_CACHE_KEY))
        assertEquals(0, store.mutationQueue.shownQueueCounts().conflicted)
    }

    @Test
    fun a_park_encodes_its_own_entry_once_and_no_other() = runTest {
        // Given — a list that counts how often it encodes an entry to measure it
        val encodings = CountingSerializer(StoredConflict.serializer())
        val list =
            ConflictedMutationList(
                queryCacheDao = queryCacheDao,
                json = EngineJson,
                nowMillis = { NOW },
                logger = EngineLogger(null, EngineLogLevel.INFO),
                entrySerializer = encodings,
            )

        // When — fifty parks, each written as a replay writes it
        repeat(50) { index -> list.add(conflict(id = index + 1L)) }

        // Then — fifty encodings, one per park; measuring every entry again would take 1,275
        assertEquals(50, encodings.encodings)
        assertEquals((1L..50L).toList(), list.conflicts().map { it.id })
    }

    @Test
    fun the_sizes_kept_beside_the_entries_match_the_row_in_memory_and_on_reload() = runTest {
        // Given — a list built up by parks and a dismissal, and the same row loaded afresh
        val parked = (1..5).map { index -> park(queuedUpdate(owner = USER_A, title = "t$index")) }
        store.mutationQueue.dismissConflictOf(parked[2].id, USER_A)
        val inMemory = store.mutationQueue.conflictedList.conflictedRowWith().second
        val reloaded =
            newGuardedStore(mutationDao = mutationDao, queryCacheDao = queryCacheDao)
                .mutationQueue
                .conflictedList
                .conflictedRowWith()
                .second

        // Then — one size per entry, each the entry's encoded length, in memory as on reload
        val expected =
            inMemory.entries.map { entry ->
                EngineJson.encodeToString(StoredConflict.serializer(), entry).length
            }
        assertEquals(parked.map { it.id } - parked[2].id, inMemory.entries.map { it.id })
        assertEquals(expected, inMemory.entrySizes)
        assertEquals(inMemory.entries, reloaded.entries)
        assertEquals(expected, reloaded.entrySizes)
    }

    /** Adds [entry] as the newest conflict and writes the list, as a park does. */
    private suspend fun ConflictedMutationList.add(entry: StoredConflict) {
        val (row, list) = conflictedRowWith(add = entry)
        queryCacheDao.upsert(row)
        conflictWritten(list)
    }

    /** A parked update of task 1 with [id], as stored. */
    private fun conflict(id: Long): StoredConflict =
        StoredConflict(
            id = id,
            table = GUARDED_TABLE,
            operation = MutationOperation.UPDATE.storedName,
            bodyJson = """{"title":"mine"}""",
            createdAtMs = NOW,
            owner = USER_A,
            baseVersion = FIRST_VERSION,
            serverVersion = MOVED_VERSION,
            message = "The row changed on the server since this write was made",
            conflictedAtMs = NOW,
        )

    /** Queues an update of task 1 owned by [owner], with a base, and returns its row. */
    private suspend fun queuedUpdate(owner: String, title: String = "mine"): MutationRow {
        val row =
            MutationRow(
                table = GUARDED_TABLE,
                operation = MutationOperation.UPDATE.storedName,
                bodyJson = JsonObject(mapOf("title" to JsonPrimitive(title))).toString(),
                filterJson = """{"conditions":[{"column":"id","operatorId":"eq","value":"1"}]}""",
                createdAtMs = NOW,
                owner = owner,
                baseVersion = FIRST_VERSION,
                baseJson = """{"title":"theirs","updated_at":"$FIRST_VERSION"}""",
            )
        return row.copy(id = mutationDao.insert(row))
    }

    /** Parks [row] as a replay that found task 1 renamed elsewhere would. */
    private suspend fun park(row: MutationRow): StoredConflict =
        store.mutationQueue.parkConflictedMutation(
            row,
            ParkedConflict(
                serverRow = taskRow(1, "elsewhere", version = MOVED_VERSION),
                serverVersion = MOVED_VERSION,
                changedOnServer = setOf("title"),
                conflictingColumns = setOf("title"),
                message = "The row changed on the server since this write was made",
            ),
        )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val USER_A = "user:a"
        const val USER_B = "user:b"
    }
}
