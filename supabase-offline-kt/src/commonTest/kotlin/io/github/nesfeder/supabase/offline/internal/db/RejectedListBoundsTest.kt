package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rejected list is bounded in bytes as well as in entries, so a few large refused writes cannot
 * make every later rejection read, decode and rewrite megabytes — or run the app out of memory — and
 * nothing leaves the list without a trace.
 */
class RejectedListBoundsTest {

    private val objectDao = FakeObjectDao()
    private val logger = RecordingOfflineLogger()
    private val store = newTestOfflineStore(objectDao = objectDao).also { store -> store.sdkLogger = logger.asSdkLogger() }

    @Test
    fun large_bodies_do_not_grow_the_list_without_bound() = runTest {
        // Given / When — ten refused writes of a megabyte each
        val body = "[{\"id\":\"x\",\"blob\":\"" + "a".repeat(1024 * 1024) + "\"}]"
        repeat(10) { index -> store.outboxState.addRejection(rejection(id = index + 1L, bodyJson = body)) }

        // Then — every rejection is still listed, and the row stays small
        assertEquals((1L..10L).toList(), store.outboxState.rejections().map { it.id })
        val rowLength = objectDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE)?.payload?.length ?: 0
        assertTrue(rowLength < 1_200_000, "the rejected list row holds $rowLength characters")
    }

    @Test
    fun an_entry_that_leaves_the_full_list_is_logged() = runTest {
        // Given / When — one more than the list keeps
        repeat(MAX_REJECTED_MUTATIONS + 1) { index -> store.outboxState.addRejection(rejection(id = index + 1L)) }

        // Then — the oldest went, and a WARNING names it
        assertEquals(2L, store.outboxState.rejections().first().id)
        val warnings = logger.entriesAt(OfflineLogLevel.WARNING).map { it.message }
        assertTrue(warnings.any { it.contains("1") && it.contains("notes") && it.contains("INSERT") }, "got $warnings")
    }

    private fun rejection(id: Long, bodyJson: String = "[]"): StoredRejection = StoredRejection(
        id = id,
        table = "notes",
        operation = MutationOperation.INSERT,
        bodyJson = bodyJson,
        createdAtMs = 0,
        rejectedAtMs = 0,
        message = "refused",
    )
}
