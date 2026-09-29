package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.cache.MutationOverlay
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One pending mutation the overlay cannot apply is skipped on its own. Before, the whole batch ran
 * inside one `runCatching`, so a single throwing mutation — an UPDATE filtered on an array column,
 * say — made every pending write of the table vanish from every screen until the next sync.
 */
class OverlayMutationIsolationTest {

    @Test
    fun an_update_the_overlay_cannot_evaluate_leaves_the_other_pending_writes_visible() {
        // Given — a pending insert, then an update filtered with `cs` on an array column. Reading
        // that column as a primitive throws inside the filter evaluation.
        val pending = listOf(
            mutation(id = 1, operation = MutationOperation.INSERT, body = """[{"id":2,"tags":["b"]}]"""),
            mutation(
                id = 2,
                operation = MutationOperation.UPDATE,
                body = """{"done":true}""",
                filter = FilterDescriptor(conditions = listOf(FilterDescriptor.Condition("tags", "cs", "{a}"))),
            ),
            mutation(id = 3, operation = MutationOperation.INSERT, body = """[{"id":3,"tags":[]}]"""),
        )

        // When
        val overlaid = MutationOverlay.overlay(
            json = OfflineJson,
            serverJson = """[{"id":1,"tags":["a"]}]""",
            pending = pending,
        )

        // Then — both inserts show; only the update that could not be applied is skipped
        assertEquals(
            """[{"id":1,"tags":["a"]},{"id":2,"tags":["b"]},{"id":3,"tags":[]}]""",
            overlaid,
        )
    }

    @Test
    fun a_pending_write_whose_stored_body_no_longer_parses_is_skipped_alone() {
        // Given
        val pending = listOf(
            mutation(id = 1, operation = MutationOperation.INSERT, body = "not json"),
            mutation(id = 2, operation = MutationOperation.INSERT, body = """[{"id":2}]"""),
        )

        // When
        val overlaid = MutationOverlay.overlay(
            json = OfflineJson,
            serverJson = """[{"id":1}]""",
            pending = pending,
        )

        // Then
        assertEquals("""[{"id":1},{"id":2}]""", overlaid)
    }

    @Test
    fun a_skipped_mutation_is_reported_to_the_failure_callback() {
        // Given
        val skipped = mutableListOf<Long>()
        val pending = listOf(mutation(id = 7, operation = MutationOperation.INSERT, body = "not json"))

        // When
        MutationOverlay.overlay(
            json = OfflineJson,
            serverJson = "[]",
            pending = pending,
            onMutationFailure = { mutation, _ -> skipped += mutation.id },
        )

        // Then
        assertEquals(listOf(7L), skipped)
    }

    @Test
    fun the_store_logs_a_skipped_mutation_without_its_payload() = runTest {
        // Given — a store whose overlay meets a pending write it cannot apply
        val logger = RecordingOfflineLogger()
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(mutationDao = mutationDao)
        store.sdkLogger = logger.asSdkLogger()
        mutationDao.insert(mutation(id = 0, operation = MutationOperation.INSERT, body = "not json $PRIVATE_VALUE"))

        // When
        store.overlayCachedPayload("todos", "[]")

        // Then
        val warning = logger.entriesAt(OfflineLogLevel.WARNING).single().message
        assertTrue(warning.contains("todos"), warning)
        assertTrue(logger.entries.none { it.message.contains(PRIVATE_VALUE) })
    }

    @Test
    fun a_mutation_that_keeps_failing_is_logged_once_not_on_every_emission() = runTest {
        // Given
        val logger = RecordingOfflineLogger()
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(mutationDao = mutationDao)
        store.sdkLogger = logger.asSdkLogger()
        mutationDao.insert(mutation(id = 0, operation = MutationOperation.INSERT, body = "not json"))

        // When — the overlay is recomputed three times
        repeat(3) { store.overlayCachedPayload("todos", "[]") }

        // Then
        assertEquals(1, logger.entriesAt(OfflineLogLevel.WARNING).size)
    }

    private fun mutation(id: Long, operation: String, body: String, filter: FilterDescriptor? = null): MutationRow =
        MutationRow(
            id = id,
            table = "todos",
            operation = operation,
            bodyJson = body,
            filterJson = filter?.let { FilterEngine.encode(it, OfflineJson) },
            createdAtMs = id,
        )

    private companion object {
        const val PRIVATE_VALUE = "private-row-value"
    }
}
