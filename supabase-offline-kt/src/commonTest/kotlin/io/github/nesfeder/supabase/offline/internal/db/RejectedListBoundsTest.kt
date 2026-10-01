// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_BODY_CHARS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_LIST_CHARS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_MUTATIONS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MAX_REJECTED_TEXT_CHARS
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredServerError
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toRejectedMutation
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The rejected list is bounded in bytes as well as in entries, so a few large refused writes cannot
 * make every later rejection read, decode and rewrite megabytes — or run the app out of memory —
 * and nothing leaves the list without a trace.
 */
class RejectedListBoundsTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val logger = RecordingOfflineLogger()
    private val store =
        newTestOfflineStore(queryCacheDao = queryCacheDao).also { store ->
            store.engineLogger = logger.asEngineLogger()
        }

    @Test
    fun large_bodies_do_not_grow_the_list_without_bound() = runTest {
        // Given / When — ten refused writes of a megabyte each
        val body = "[{\"id\":\"x\",\"blob\":\"" + "a".repeat(1024 * 1024) + "\"}]"
        repeat(10) { index ->
            store.mutationQueueState.addRejection(rejection(id = index + 1L, bodyJson = body))
        }

        // Then — every rejection is still listed, and the row stays small
        assertEquals((1L..10L).toList(), store.mutationQueueState.rejections().map { it.id })
        val rowLength =
            queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY)?.payload?.length
                ?: 0
        assertTrue(rowLength < 1_200_000, "the rejected list row holds $rowLength characters")
    }

    @Test
    fun an_entry_that_leaves_the_full_list_is_logged() = runTest {
        // Given / When — one more than the list keeps
        repeat(MAX_REJECTED_MUTATIONS + 1) { index ->
            store.mutationQueueState.addRejection(rejection(id = index + 1L))
        }

        // Then — the oldest went, and a WARNING names it
        assertEquals(2L, store.mutationQueueState.rejections().first().id)
        val warnings = logger.entriesAt(OfflineLogLevel.WARNING).map { it.message }
        assertTrue(
            warnings.any { it.contains("1") && it.contains("notes") && it.contains("INSERT") },
            "got $warnings",
        )
    }

    @Test
    fun a_body_too_long_to_keep_is_dropped_with_its_size_and_cannot_be_retried() = runTest {
        // Given / When
        val body = "[{\"blob\":\"" + "a".repeat(MAX_REJECTED_BODY_CHARS) + "\"}]"
        store.mutationQueueState.addRejection(rejection(id = 1L, bodyJson = body))

        // Then — the entry stays, without its body, saying how long it was; a retry declines it
        val entry = store.mutationQueueState.rejections().single()
        assertEquals(body.length, entry.bodyOmittedChars)
        assertEquals(
            body.length,
            entry.toRejectedMutation(store.json, DefaultPrimaryKeys).bodyOmittedChars,
        )
        assertNull(store.retryRejectedMutation(1L, identity = null))
        assertEquals(1, store.mutationQueueState.rejections().size)
    }

    @Test
    fun the_list_keeps_within_its_size_budget_and_counts_what_it_drops() = runTest {
        // Given / When — fifty rejections whose bodies are just under the per-body limit
        val body = "[{\"blob\":\"" + "a".repeat(MAX_REJECTED_BODY_CHARS - 20) + "\"}]"
        repeat(50) { index ->
            store.mutationQueueState.addRejection(rejection(id = index + 1L, bodyJson = body))
        }

        // Then — the newest are kept within the budget, and the count of dropped entries is
        // persisted
        val row =
            assertNotNull(queryCacheDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_CACHE_KEY))
        assertTrue(
            row.payload.length <= MAX_REJECTED_LIST_CHARS + 1_000,
            "the row holds ${row.payload.length} characters",
        )
        val kept = store.mutationQueueState.rejections()
        assertEquals(50L, kept.last().id)
        val reloaded = newTestOfflineStore(queryCacheDao = queryCacheDao)
        assertEquals(50L - kept.size, reloaded.mutationQueueState.droppedCount())
    }

    @Test
    fun one_large_entry_drops_as_many_of_the_oldest_as_the_budget_needs() = runTest {
        // Given — a list close to its size budget
        val body = "[{\"blob\":\"" + "a".repeat(MAX_REJECTED_BODY_CHARS - 20) + "\"}]"
        val entries = (1L..31L).map { id -> rejection(id = id, bodyJson = body) }
        entries.forEach { entry -> store.mutationQueueState.addRejection(entry) }
        val droppedBefore = store.mutationQueueState.droppedCount()

        // When — one entry with the longest body and server texts the list keeps
        val text = "t".repeat(MAX_REJECTED_TEXT_CHARS)
        val large =
            rejection(id = 32L, bodyJson = body)
                .copy(
                    message = text,
                    serverError = StoredServerError(400, "x", text, details = text, hint = text),
                )
        store.mutationQueueState.addRejection(large)

        // Then — exactly the oldest entries that take the list over its budget went, and no more
        val all = store.mutationQueueState.rejections()
        val kept = (entries + large).takeLastWhileWithin(MAX_REJECTED_LIST_CHARS)
        assertEquals(kept.map { it.id }, all.map { it.id })
        assertEquals(
            droppedBefore + (entries.size + 1 - kept.size),
            store.mutationQueueState.droppedCount(),
        )
    }

    /** The newest entries whose encoded lengths sum to at most [budget], oldest first. */
    private fun List<StoredRejection>.takeLastWhileWithin(budget: Int): List<StoredRejection> {
        var total = 0
        return asReversed()
            .takeWhile { entry ->
                total += store.json.encodeToString(StoredRejection.serializer(), entry).length
                total <= budget
            }
            .asReversed()
    }

    @Test
    fun the_count_of_dropped_entries_can_be_reset() = runTest {
        // Given
        repeat(MAX_REJECTED_MUTATIONS + 3) { index ->
            store.mutationQueueState.addRejection(rejection(id = index + 1L))
        }
        assertEquals(3L, store.mutationQueueState.droppedCount())

        // When
        store.mutationQueueState.resetDroppedCount()

        // Then
        assertEquals(
            0L,
            newTestOfflineStore(queryCacheDao = queryCacheDao).mutationQueueState.droppedCount(),
        )
    }

    private fun rejection(id: Long, bodyJson: String = "[]"): StoredRejection =
        StoredRejection(
            id = id,
            table = "notes",
            operation = MutationOperation.INSERT,
            bodyJson = bodyJson,
            createdAtMs = 0,
            rejectedAtMs = 0,
            message = "refused",
        )
}
