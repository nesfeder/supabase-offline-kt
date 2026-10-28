// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.storedName
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The rows each mutation changed in a query, as the overlay reports them from the pass that built
 * the rows: a row it added or changed as the query finally shows it, a row it removed as the query
 * last showed it, and nothing for a mutation that changed no row shown or was taken back.
 */
class OverlayShownChangesTest {

    private val listA = QueryAdmission(listOf(FilterDescriptor.Condition("list", "eq", "A")))

    private fun overlay(
        rows: List<JsonElement>,
        mutations: List<MutationRow>,
        bodyOf: ((MutationRow) -> JsonElement)? = null,
    ): OverlaidRows {
        val overlay =
            if (bodyOf == null) {
                MutationOverlay(EngineJson, listA, DefaultPrimaryKeys, mayAlreadyHold = { false })
            } else {
                MutationOverlay(
                    EngineJson,
                    listA,
                    DefaultPrimaryKeys,
                    mayAlreadyHold = { false },
                    bodyOf = bodyOf,
                )
            }
        return overlay.overlayRows(rows, mutations)
    }

    @Test
    fun each_mutation_reports_the_rows_it_changed_and_a_removed_row_as_last_shown() {
        // Given — rows 1 to 3 of list A; an insert, an update, a delete, an update that moves row 1
        // out of the list, and an insert into another list
        val rows = listOf(row(1, "A"), row(2, "A"), row(3, "A"))
        val mutations =
            listOf(
                mutation(1, MutationOperation.INSERT, """[{"id":4,"list":"A"}]"""),
                mutation(2, MutationOperation.UPDATE, """{"title":"edited"}""", byId(2)),
                mutation(3, MutationOperation.DELETE, "", byId(3)),
                mutation(4, MutationOperation.UPDATE, """{"list":"B"}""", byId(1)),
                mutation(5, MutationOperation.INSERT, """[{"id":5,"list":"B"}]"""),
            )

        // When
        val overlaid = overlay(rows, mutations)

        // Then — the list shows rows 2 and 4; each mutation names the rows it changed there
        assertEquals(listOf(2, 4), overlaid.visible.map(::idOf))
        assertEquals(
            mapOf(
                1L to listOf("""{"id":4,"list":"A"}"""),
                2L to listOf("""{"id":2,"list":"A","title":"edited"}"""),
                3L to listOf("""{"id":3,"list":"A"}"""),
                4L to listOf("""{"id":1,"list":"A"}"""),
            ),
            overlaid.shownChangedBy.mapValues { (_, changed) -> changed.map { it.toString() } },
        )
    }

    @Test
    fun a_row_a_later_mutation_changes_is_reported_as_the_query_finally_shows_it() {
        // Given — an insert, then an update of the inserted row, then one that changes its key
        val mutations =
            listOf(
                mutation(1, MutationOperation.INSERT, """[{"id":4,"list":"A"}]"""),
                mutation(2, MutationOperation.UPDATE, """{"title":"edited"}""", byId(4)),
                mutation(3, MutationOperation.UPDATE, """{"id":40}""", byId(4)),
            )

        // When
        val overlaid = overlay(emptyList(), mutations)

        // Then — every mutation that touched the row reports it as it now stands
        val now = """{"id":40,"list":"A","title":"edited"}"""
        assertEquals(
            mapOf(1L to listOf(now), 2L to listOf(now), 3L to listOf(now)),
            overlaid.shownChangedBy.mapValues { (_, changed) -> changed.map { it.toString() } },
        )
    }

    @Test
    fun a_mutation_taken_back_part_way_reports_nothing() {
        // Given — an insert whose second row fails to read after its first is shown, then an
        // update of row 1
        val failing =
            JsonArray(
                object : AbstractList<JsonElement>() {
                    override val size = 2

                    override fun get(index: Int): JsonElement =
                        if (index == 0) row(9, "A") else error("the second row cannot be read")
                }
            )
        val insert = mutation(1, MutationOperation.INSERT, "[]")
        val update = mutation(2, MutationOperation.UPDATE, """{"title":"edited"}""", byId(1))

        // When
        val overlaid =
            overlay(listOf(row(1, "A")), listOf(insert, update)) { mutation ->
                if (mutation == insert) failing
                else EngineJson.parseToJsonElement(mutation.bodyJson)
            }

        // Then — the insert left no row and reports none; the update still applies
        assertEquals(listOf(1), overlaid.visible.map(::idOf))
        assertEquals(setOf(2L), overlaid.shownChangedBy.keys)
    }

    private fun idOf(element: JsonElement): Int =
        ((element as JsonObject)["id"] as JsonPrimitive).content.toInt()

    private fun row(id: Int, list: String) =
        JsonObject(mapOf("id" to JsonPrimitive(id), "list" to JsonPrimitive(list)))

    private fun byId(id: Int): String =
        encodeFilter(
            FilterDescriptor(conditions = listOf(FilterDescriptor.Condition("id", "eq", "$id"))),
            EngineJson,
        )

    private fun mutation(
        id: Long,
        operation: MutationOperation,
        body: String,
        filter: String? = null,
    ) =
        MutationRow(
            id = id,
            table = TABLE,
            operation = operation.storedName,
            bodyJson = body,
            filterJson = filter,
            createdAtMs = id,
        )

    private companion object {
        const val TABLE = "todos"
    }
}
