// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.cache.MutationOverlay
import io.github.nesfeder.supabase.offline.internal.cache.PatchTarget
import io.github.nesfeder.supabase.offline.internal.cache.conflictColumnsOf
import io.github.nesfeder.supabase.offline.internal.cache.keyOf
import io.github.nesfeder.supabase.offline.internal.cache.patchMayLeaveWrong
import io.github.nesfeder.supabase.offline.internal.cache.payloadRowsOf
import io.github.nesfeder.supabase.offline.internal.cache.projectionOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.queryShapeOf
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The store reads only the rows other cached queries hold that a write may bring in, yet shows and
 * marks stale exactly what it would with every one of them: over seeded random tables and writes, a
 * cached read's overlay and an accepted write's patch are compared with the same overlay given
 * every row the table's other queries hold.
 */
class KnownRowsPruningTest {

    /** One cached query of the scenario: its cache key, request and select list. */
    private class Query(
        val signature: String,
        val columns: String,
        val request: SelectRequestBuilder.() -> Unit,
    )

    private val queries =
        listOf(
            Query("list-a", "*") { filter { eq("list", "A") } },
            Query("list-b", "*") { filter { eq("list", "B") } },
            Query("done", "*") { filter { eq("status", "done") } },
            Query("urgent-or-a", "*") {
                filter {
                    eq("flag", "urgent")
                    or { eq("list", "A") }
                }
            },
            Query("everything", "*") {},
            Query("picker", "id,title") {},
        )

    private val shapes: Map<String, QueryShape> = queries.associate { query ->
        query.signature to
            queryShapeOf(
                Columns.raw(query.columns),
                SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply(query.request),
            )
    }

    @Test
    fun a_cached_read_shows_what_every_known_row_would_show() = runTest {
        repeat(SCENARIOS) { seed ->
            // Given — a table cached by six queries and some pending writes
            val random = Random(seed)
            val objectDao = FakeObjectDao()
            val store = newTestOfflineStore(objectDao = objectDao, nowMillis = { NOW })
            val payloads = seedPayloads(random, objectDao, store)
            repeat(random.nextInt(1, 7)) { queueRandomWrite(random, store) }
            val pending = store.pendingMutationsForTable(TABLE)

            queries.forEach { query ->
                // When — the query is read from the cache
                val payload = payloads.getValue(query.signature)
                val shown = store.overlayCachedPayload(TABLE, query.signature, payload.payload)

                // Then — as with every row the others hold
                val expected =
                    MutationOverlay(
                            json = OfflineJson,
                            admission = shapes.getValue(query.signature).admission,
                            primaryKeys = DefaultPrimaryKeys,
                            mayAlreadyHold = { false },
                        )
                        .overlay(
                            payload.payload,
                            pending,
                            knownRows(query.signature, payloads, sameSelect = true, own = null),
                        )
                assertEquals(expected, shown, "seed $seed, ${query.signature}, $pending")
            }
        }
    }

    @Test
    fun an_accepted_write_patches_and_marks_what_every_known_row_would() = runTest {
        repeat(SCENARIOS) { seed ->
            // Given — a table cached by six queries
            val random = Random(seed)
            val objectDao = FakeObjectDao()
            val store = newTestOfflineStore(objectDao = objectDao, nowMillis = { NOW })
            val markedStale = mutableSetOf<String>()
            store.onQueriesMarkedStale = { _, signatures -> markedStale += signatures }
            val payloads = seedPayloads(random, objectDao, store)
            val write = randomWrite(random)

            // When — the server accepted the write
            store.patchCachedQueries(
                table = TABLE,
                operation = write.operation,
                bodyJson = write.bodyJson,
                filterJson = write.filterJson,
                onConflict = write.onConflict,
                ignoreDuplicates = write.ignoreDuplicates,
                liveSignatures = emptySet(),
            )

            // Then — each payload, and each query to fetch again, as with every row the others hold
            val expectedStale = mutableSetOf<String>()
            queries.forEach { query ->
                val before = payloads.getValue(query.signature)
                val expected = expectedPatch(query.signature, before, payloads, write)
                if (expected.updatedAt < 0) expectedStale += query.signature
                val after = objectDao.getRow(TABLE, query.signature)
                assertEquals(
                    expected.payload to expected.updatedAt,
                    after?.payload to after?.updatedAt,
                    "seed $seed, ${query.signature}, $write",
                )
            }
            assertEquals(expectedStale, markedStale, "seed $seed, $write")
        }
    }

    /** [before] patched with [write] as the store would, given every row the others hold. */
    private fun expectedPatch(
        signature: String,
        before: ObjectRow,
        payloads: Map<String, ObjectRow>,
        write: MutationRow,
    ): ObjectRow {
        val rows = payloadRowsOf(OfflineJson, before.payload) ?: return before
        val shape = shapes.getValue(signature)
        val same = knownRows(signature, payloads, sameSelect = true, own = rows)
        val sameKeys = same.mapTo(HashSet()) { row -> keyOf(row, KEY) }
        val other =
            knownRows(signature, payloads, sameSelect = false, own = rows).filter { row ->
                keyOf(row, KEY) !in sameKeys
            }
        val overlay =
            MutationOverlay(
                json = OfflineJson,
                admission = shape.admission,
                primaryKeys = DefaultPrimaryKeys,
                mayAlreadyHold = { true },
                placesKeylessUpsertRows = false,
            )
        val patched = overlay.overlayRows(rows, listOf(write), same)
        val wrong =
            patchMayLeaveWrong(
                listOf(write),
                PatchTarget(rows, shape, same, other, KEY.toSet()),
                OfflineJson,
            ) { mutation ->
                conflictColumnsOf(mutation, DefaultPrimaryKeys)
            }
        if (patched.visible == rows && !wrong) return before
        return before.copy(
            payload = overlay.encode(patched.visible),
            updatedAt = if (wrong) staleStampOf(before.updatedAt) else before.updatedAt,
        )
    }

    /**
     * The keyed rows the queries other than [signature] hold, one per key from the most recently
     * fetched, read with its select list ([sameSelect]) or another; leaving out keys [own] holds.
     */
    private fun knownRows(
        signature: String,
        payloads: Map<String, ObjectRow>,
        sameSelect: Boolean,
        own: List<JsonElement>?,
    ): List<JsonObject> {
        val projection = projectionOf(shapes.getValue(signature))
        val ownKeys =
            own.orEmpty().mapNotNullTo(HashSet()) { element ->
                (element as? JsonObject)?.let { row -> keyOf(row, KEY) }
            }
        val seen = HashSet<List<String>>()
        return payloads.values
            .filter { row -> row.signature != signature }
            .filter { row ->
                (projectionOf(shapes.getValue(row.signature)) == projection) == sameSelect
            }
            .sortedByDescending { row -> row.updatedAt }
            .flatMap { row -> payloadRowsOf(OfflineJson, row.payload).orEmpty() }
            .mapNotNull { element -> element as? JsonObject }
            .filter { row ->
                val key = keyOf(row, KEY)
                key != null && key !in ownKeys && seen.add(key)
            }
    }

    /**
     * Caches each query's answer: rows of one table, each query holding those it admits as its own
     * fetch saw them, so the same row can differ between payloads.
     */
    private suspend fun seedPayloads(
        random: Random,
        objectDao: FakeObjectDao,
        store: OfflineStore,
    ): Map<String, ObjectRow> {
        val base = (1..ROWS).map { id -> randomRow(random, id) }
        return queries
            .mapIndexed { index, query ->
                val shape = shapes.getValue(query.signature)
                val seen = base.map { row ->
                    if (random.nextInt(5) == 0) randomRow(random, id(row)) else row
                }
                val held =
                    seen
                        .filter { row -> shape.admission.admits(row) && random.nextInt(4) != 0 }
                        .map { row ->
                            if (query.columns == "*") row
                            else JsonObject(row.filterKeys { column -> column in PICKED })
                        }
                val payload = JsonArray(held).toString()
                val updatedAt = NOW - 1_000L * (index + 1) - random.nextLong(500)
                objectDao.seedPayload(TABLE, query.signature, payload, updatedAt)
                store.queryShapes.remember(TABLE, query.signature, shape, persist = false)
                query.signature to ObjectRow(TABLE, query.signature, payload, updatedAt)
            }
            .toMap()
    }

    /** Queues a random write, as an offline client does. */
    private suspend fun queueRandomWrite(random: Random, store: OfflineStore) {
        val write = randomWrite(random)
        val filter = write.filterJson?.let { text -> FilterEngine.decodeOrNull(text, OfflineJson) }
        when (write.operation) {
            MutationOperation.INSERT ->
                store.enqueueInsert(
                    TABLE,
                    OfflineJson.parseToJsonElement(write.bodyJson) as JsonArray,
                )
            MutationOperation.UPSERT ->
                store.enqueueUpsert(
                    TABLE,
                    OfflineJson.parseToJsonElement(write.bodyJson) as JsonArray,
                    ignoreDuplicates = write.ignoreDuplicates == true,
                )
            MutationOperation.UPDATE ->
                store.enqueueUpdate(TABLE, OfflineJson.parseToJsonElement(write.bodyJson), filter)
            MutationOperation.DELETE -> store.enqueueDelete(TABLE, filter)
        }
    }

    /**
     * A random write to the table: its columns partial, its filter one the engine may not judge.
     */
    private fun randomWrite(random: Random): MutationRow {
        val operation =
            listOf(
                    MutationOperation.UPSERT,
                    MutationOperation.UPSERT,
                    MutationOperation.UPDATE,
                    MutationOperation.UPDATE,
                    MutationOperation.DELETE,
                    MutationOperation.INSERT,
                )
                .random(random)
        val body =
            when (operation) {
                MutationOperation.UPDATE -> randomColumns(random, keyed = false).toString()
                MutationOperation.DELETE -> "{}"
                else ->
                    JsonArray(List(random.nextInt(1, 3)) { randomColumns(random, keyed = true) })
                        .toString()
            }
        val filter =
            if (operation == MutationOperation.UPDATE || operation == MutationOperation.DELETE) {
                FilterEngine.encode(randomFilter(random), OfflineJson)
            } else {
                null
            }
        return MutationRow(
            id = -1,
            table = TABLE,
            operation = operation,
            bodyJson = body,
            filterJson = filter,
            createdAtMs = NOW,
            ignoreDuplicates =
                if (operation == MutationOperation.UPSERT) random.nextInt(5) == 0 else null,
        )
    }

    /**
     * Some of a row's columns, with its key when [keyed] (and now and then without). A keyed body
     * seldom names its list, so an upsert often changes a row a later update moves.
     */
    private fun randomColumns(random: Random, keyed: Boolean): JsonObject {
        val row = randomRow(random, random.nextInt(1, ROWS + 3))
        val kept = row.filterKeys { column ->
            when (column) {
                "id" -> keyed && random.nextInt(8) != 0
                "list" -> random.nextInt(if (keyed) 4 else 2) == 0
                else -> random.nextBoolean()
            }
        }
        return JsonObject(kept.ifEmpty { mapOf("title" to JsonPrimitive("t")) })
    }

    /** A filter of one or two conditions, each evaluated by the engine or not. */
    private fun randomFilter(random: Random): FilterDescriptor =
        FilterDescriptor(
            mode =
                if (random.nextInt(5) == 0) FilterDescriptor.Mode.OR else FilterDescriptor.Mode.AND,
            conditions = List(random.nextInt(1, 3)) { randomCondition(random) },
        )

    /** A condition, most often on a column the random writes set. */
    private fun randomCondition(random: Random): FilterDescriptor.Condition =
        when (random.nextInt(10)) {
            0,
            1 -> FilterDescriptor.Condition("id", "eq", random.nextInt(1, ROWS + 3).toString())
            2,
            3 -> FilterDescriptor.Condition("status", "eq", STATUSES.random(random))
            4,
            5 -> FilterDescriptor.Condition("flag", "eq", FLAGS.random(random))
            6 -> FilterDescriptor.Condition("list", "in", "(A,C)")
            7 -> FilterDescriptor.Condition("flag", "is", "null")
            8 -> FilterDescriptor.Condition("status", "neq", "done")
            else -> FilterDescriptor.Condition("id", "gt", random.nextInt(1, ROWS).toString())
        }

    /** Row [id] with random values, a column now and then absent. */
    private fun randomRow(random: Random, id: Int): JsonObject {
        val columns = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id))
        columns["list"] = JsonPrimitive(LISTS.random(random))
        if (random.nextInt(4) != 0) columns["status"] = JsonPrimitive(STATUSES.random(random))
        if (random.nextInt(3) != 0) columns["flag"] = JsonPrimitive(FLAGS.random(random))
        columns["title"] = JsonPrimitive("title $id ${random.nextInt(3)}")
        return JsonObject(columns)
    }

    private fun id(row: JsonObject): Int = (row.getValue("id") as JsonPrimitive).content.toInt()

    private companion object {
        const val TABLE = "tasks"
        const val NOW = 1_000_000L
        const val ROWS = 10
        const val SCENARIOS = 400
        val KEY = listOf("id")
        val LISTS = listOf("A", "B", "C")
        val STATUSES = listOf("open", "done")
        val FLAGS = listOf("normal", "urgent")
        val PICKED = setOf("id", "title")
    }
}
