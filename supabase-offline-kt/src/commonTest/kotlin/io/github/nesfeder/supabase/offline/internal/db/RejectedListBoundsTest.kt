// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_BODY_CHARS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_LIST_CHARS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_MUTATIONS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_TEXT_CHARS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.RejectedMutationList
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredServerError
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toRejectedMutation
import io.github.nesfeder.supabase.offline.testdoubles.CountingSerializer
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The rejected list is bounded in bytes as well as in entries, so a few large refused writes cannot
 * make every later rejection read, decode and rewrite megabytes — or run the app out of memory —
 * and nothing leaves the list without a trace. The bounds are checked from each entry's size kept
 * beside it, so a rejection encodes its own entry once, not every entry again.
 */
class RejectedListBoundsTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val logger = RecordingLogSink()
    private val store =
        newTestStore(queryCacheDao = queryCacheDao, engineLogger = logger.asEngineLogger())

    @Test
    fun large_bodies_do_not_grow_the_list_without_bound() = runTest {
        // Given / When — ten refused writes of a megabyte each
        val body = "[{\"id\":\"x\",\"blob\":\"" + "a".repeat(1024 * 1024) + "\"}]"
        repeat(10) { index ->
            store.addRejection(rejection(id = index + 1L, bodyJson = body))
        }

        // Then — every rejection is still listed, and the row stays small
        assertEquals(
            (1L..10L).toList(),
            store.mutationQueue.rejectedList.rejections().map { it.id },
        )
        val rowLength =
            queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY)?.payload?.length
                ?: 0
        assertTrue(rowLength < 1_200_000, "the rejected list row holds $rowLength characters")
    }

    @Test
    fun a_base_row_too_long_to_keep_is_dropped_and_the_base_version_kept() = runTest {
        // Given — a guarded write refused with a base row past the bound
        val entry =
            rejection(1)
                .copy(
                    baseVersion = "v1",
                    baseJson = "x".repeat(MAX_REJECTED_BODY_CHARS + 1),
                )

        // When
        store.addRejection(entry)

        // Then — the row is not kept, the version is, so a retry still replays guarded
        val stored = store.mutationQueue.rejectedList.rejections().single()
        assertNull(stored.baseJson)
        assertEquals("v1", stored.baseVersion)
        assertEquals(
            "v1" to null,
            stored.toRequeuedRow(0, null)?.let { it.baseVersion to it.baseJson },
        )
    }

    @Test
    fun an_entry_that_leaves_the_full_list_is_logged() = runTest {
        // Given / When — one more than the list keeps
        repeat(MAX_REJECTED_MUTATIONS + 1) { index ->
            store.addRejection(rejection(id = index + 1L))
        }

        // Then — the oldest went, and a WARNING names it
        assertEquals(2L, store.mutationQueue.rejectedList.rejections().first().id)
        val warnings = logger.entriesAt(EngineLogLevel.WARNING).map { it.message }
        assertTrue(
            warnings.any { it.contains("1") && it.contains("notes") && it.contains("INSERT") },
            "got $warnings",
        )
    }

    @Test
    fun a_body_too_long_to_keep_is_dropped_with_its_size_and_cannot_be_retried() = runTest {
        // Given / When
        val body = "[{\"blob\":\"" + "a".repeat(MAX_REJECTED_BODY_CHARS) + "\"}]"
        store.addRejection(rejection(id = 1L, bodyJson = body))

        // Then — the entry stays, without its body, saying how long it was; a retry declines it
        val entry = store.mutationQueue.rejectedList.rejections().single()
        assertEquals(body.length, entry.bodyOmittedChars)
        assertEquals(
            body.length,
            entry.toRejectedMutation(EngineJson, DefaultPrimaryKeys).bodyOmittedChars,
        )
        assertNull(store.mutationQueue.retryRejectedMutation(1L, identity = null))
        assertEquals(1, store.mutationQueue.rejectedList.rejections().size)
    }

    @Test
    fun the_list_keeps_within_its_size_budget_and_counts_what_it_drops() = runTest {
        // Given / When — fifty rejections whose bodies are just under the per-body limit
        val body = "[{\"blob\":\"" + "a".repeat(MAX_REJECTED_BODY_CHARS - 20) + "\"}]"
        repeat(50) { index ->
            store.addRejection(rejection(id = index + 1L, bodyJson = body))
        }

        // Then — the newest are kept within the budget, and the count of dropped entries is
        // persisted
        val row =
            assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
        assertTrue(
            row.payload.length <= MAX_REJECTED_LIST_CHARS + 1_000,
            "the row holds ${row.payload.length} characters",
        )
        val kept = store.mutationQueue.rejectedList.rejections()
        assertEquals(50L, kept.last().id)
        val reloaded = newTestStore(queryCacheDao = queryCacheDao)
        assertEquals(50L - kept.size, reloaded.mutationQueue.rejectedList.droppedCount())
    }

    @Test
    fun one_large_entry_drops_as_many_of_the_oldest_as_the_budget_needs() = runTest {
        // Given — a list close to its size budget
        val body = "[{\"blob\":\"" + "a".repeat(MAX_REJECTED_BODY_CHARS - 20) + "\"}]"
        val entries = (1L..31L).map { id -> rejection(id = id, bodyJson = body) }
        entries.forEach { entry -> store.addRejection(entry) }
        val droppedBefore = store.mutationQueue.rejectedList.droppedCount()

        // When — one entry with the longest body and server texts the list keeps
        val text = "t".repeat(MAX_REJECTED_TEXT_CHARS)
        val large =
            rejection(id = 32L, bodyJson = body)
                .copy(
                    message = text,
                    serverError = StoredServerError(400, "x", text, details = text, hint = text),
                )
        store.addRejection(large)

        // Then — exactly the oldest entries that take the list over its budget went, and no more
        val all = store.mutationQueue.rejectedList.rejections()
        val kept = (entries + large).takeLastWhileWithin(MAX_REJECTED_LIST_CHARS)
        assertEquals(kept.map { it.id }, all.map { it.id })
        assertEquals(
            droppedBefore + (entries.size + 1 - kept.size),
            store.mutationQueue.rejectedList.droppedCount(),
        )
    }

    /** The newest entries whose encoded lengths sum to at most [budget], oldest first. */
    private fun List<StoredRejection>.takeLastWhileWithin(budget: Int): List<StoredRejection> {
        var total = 0
        return asReversed()
            .takeWhile { entry ->
                total += EngineJson.encodeToString(StoredRejection.serializer(), entry).length
                total <= budget
            }
            .asReversed()
    }

    @Test
    fun the_count_of_dropped_entries_can_be_reset() = runTest {
        // Given
        repeat(MAX_REJECTED_MUTATIONS + 3) { index ->
            store.addRejection(rejection(id = index + 1L))
        }
        assertEquals(3L, store.mutationQueue.rejectedList.droppedCount())

        // When
        store.mutationQueue.rejectedList.resetDroppedCount()

        // Then
        assertEquals(
            0L,
            newTestStore(queryCacheDao = queryCacheDao).mutationQueue.rejectedList.droppedCount(),
        )
    }

    @Test
    fun a_rejection_encodes_its_own_entry_once_and_no_other() = runTest {
        // Given — a list that counts how often it encodes an entry to measure it
        val encodings = CountingSerializer(StoredRejection.serializer())
        val list =
            RejectedMutationList(
                queryCacheDao = queryCacheDao,
                json = EngineJson,
                nowMillis = { 0L },
                logger = logger.asEngineLogger(),
                entrySerializer = encodings,
            )

        // When — fifty rejections, each written as a refusal writes it
        repeat(50) { index -> list.add(rejection(id = index + 1L)) }

        // Then — fifty encodings, one per rejection; measuring every entry again would take 1,275
        assertEquals(50, encodings.encodings)
        assertEquals((1L..50L).toList(), list.rejections().map { it.id })
    }

    @Test
    fun the_sizes_kept_beside_the_entries_match_the_row_in_memory_and_on_reload() = runTest {
        // Given — a list built up by adds, a removal and a replaced entry, and the same row loaded
        // afresh by another store
        repeat(5) { index -> store.addRejection(rejection(index + 1L, "[{\"n\":$index}]")) }
        store.mutationQueue.rejectedList.removeRejection(3L)
        store.addRejection(rejection(id = 2L, bodyJson = "[]"))
        val inMemory = store.mutationQueue.rejectedList.rejectedRowWith().second
        val reloaded =
            newTestStore(queryCacheDao = queryCacheDao)
                .mutationQueue
                .rejectedList
                .rejectedRowWith()
                .second

        // Then — one size per entry, each the entry's encoded length, in memory as on reload
        val expected =
            inMemory.entries.map { entry ->
                EngineJson.encodeToString(StoredRejection.serializer(), entry).length
            }
        assertEquals(listOf(1L, 4L, 5L, 2L), inMemory.entries.map { it.id })
        assertEquals(expected, inMemory.entrySizes)
        assertEquals(inMemory.entries, reloaded.entries)
        assertEquals(expected, reloaded.entrySizes)
    }

    /** Adds [entry] as the newest rejection and writes the list, as a refusal does. */
    private suspend fun RejectedMutationList.add(entry: StoredRejection) {
        val (row, list) = rejectedRowWith(add = entry)
        queryCacheDao.upsert(row)
        rejectedWritten(list)
    }

    private fun rejection(id: Long, bodyJson: String = "[]"): StoredRejection =
        StoredRejection(
            id = id,
            table = "notes",
            operation = MutationOperation.INSERT.storedName,
            bodyJson = bodyJson,
            createdAtMs = 0,
            rejectedAtMs = 0,
            message = "refused",
        )
}
