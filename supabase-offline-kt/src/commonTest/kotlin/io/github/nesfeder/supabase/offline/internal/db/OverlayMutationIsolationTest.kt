package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import kotlin.test.Test
import kotlin.test.assertEquals

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
        val overlaid = OfflineMutationOverlay.applyPendingMutations(
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
        val overlaid = OfflineMutationOverlay.applyPendingMutations(
            json = OfflineJson,
            serverJson = """[{"id":1}]""",
            pending = pending,
        )

        // Then
        assertEquals("""[{"id":1},{"id":2}]""", overlaid)
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
}
