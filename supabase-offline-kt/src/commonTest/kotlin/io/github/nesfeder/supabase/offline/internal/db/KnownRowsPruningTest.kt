// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.cache.MutationOverlay
import io.github.nesfeder.supabase.offline.internal.cache.PatchTarget
import io.github.nesfeder.supabase.offline.internal.cache.Projection
import io.github.nesfeder.supabase.offline.internal.cache.conflictColumnsOf
import io.github.nesfeder.supabase.offline.internal.cache.keyOf
import io.github.nesfeder.supabase.offline.internal.cache.patchMayLeaveWrong
import io.github.nesfeder.supabase.offline.internal.cache.payloadRowsOf
import io.github.nesfeder.supabase.offline.internal.cache.projectionOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.queryShapeOf
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The store looks only at the rows other cached queries hold that a write may bring in, yet shows,
 * patches and marks stale exactly what it would with every one of them. Over seeded tables — keys
 * as integers, as UUIDs in any case or braces, and composite; upserts on another conflict column or
 * ignoring duplicates; replayed and unconfirmed mutations; stale payloads, one with no recorded
 * shape, and a live query with none; compound, unevaluated and embedded queries — every read
 * overlay, patch and read after a patch is compared with the same overlay given every row the
 * table's other queries hold: one per key, from the most recently fetched payload with the same
 * projection.
 */
class KnownRowsPruningTest {

    /** How the table is keyed. */
    private enum class Keys(val columns: List<String>) {
        INT(listOf("id")),
        UUID(listOf("id")),
        COMPOSITE(listOf("week", "user_id")),
    }

    /**
     * One cached query of the scenario.
     *
     * @property shaped `false` for a payload an earlier build cached, with no recorded shape.
     * @property cached `false` for a live query with no payload.
     */
    private class Query(
        val signature: String,
        val columns: String,
        val shaped: Boolean = true,
        val cached: Boolean = true,
        val request: SelectRequestBuilder.() -> Unit,
    )

    private fun queriesFor(keys: Keys): List<Query> {
        val picker = keys.columns.joinToString(",") + ",title"
        return listOf(
            Query("list-a", "*") { filter { eq("list", "A") } },
            Query("list-b", "*") { filter { eq("list", "B") } },
            Query("done", "*") { filter { eq("status", "done") } },
            Query("urgent", "*") { filter { eq("flag", "urgent") } },
            Query("b-done", "*") {
                filter {
                    eq("list", "B")
                    eq("status", "done")
                }
            },
            Query("a-like", "*") {
                filter {
                    eq("list", "A")
                    like("title", "t1%")
                }
            },
            Query("everything", "*") {},
            Query("picker", picker) {},
            Query("emb-a-one", "*,comments(body)") {
                filter { eq("list", "A") }
                limit(1, referencedTable = "comments")
            },
            Query("emb-b-one", "*,comments(body)") {
                filter { eq("list", "B") }
                limit(1, referencedTable = "comments")
            },
            Query("emb-all", "*,comments(body)") {},
            Query("legacy", "*", shaped = false) {},
            Query("list-c", "*", cached = false) { filter { eq("list", "C") } },
        )
    }

    private fun shapeOf(query: Query): QueryShape =
        queryShapeOf(
            Columns.raw(query.columns),
            SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply(query.request),
        )

    private val uuids =
        List(KEYS) { index ->
            "3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e${(16 + index).toString(16).padStart(2, '0')}"
        }

    /** The key columns of row [index], as the server writes them. */
    private fun keyColumnsOf(keys: Keys, index: Int): Map<String, JsonElement> =
        when (keys) {
            Keys.INT -> mapOf("id" to JsonPrimitive(index))
            Keys.UUID -> mapOf("id" to JsonPrimitive(uuids[index]))
            Keys.COMPOSITE ->
                mapOf(
                    "week" to JsonPrimitive(index / 2),
                    "user_id" to JsonPrimitive("u${index % 2}"),
                )
        }

    /** The key columns of row [index] as a client may write them: another type, case or braces. */
    private fun Random.writtenKeyOf(keys: Keys, index: Int): Map<String, JsonElement> =
        when (keys) {
            Keys.INT ->
                mapOf(
                    "id" to
                        if (nextBoolean()) JsonPrimitive(index) else JsonPrimitive(index.toString())
                )
            Keys.UUID ->
                mapOf(
                    "id" to
                        JsonPrimitive(
                            when (nextInt(3)) {
                                0 -> uuids[index]
                                1 -> uuids[index].uppercase()
                                else -> "{${uuids[index].uppercase()}}"
                            }
                        )
                )
            Keys.COMPOSITE ->
                mapOf(
                    "week" to
                        if (nextBoolean()) JsonPrimitive(index / 2)
                        else JsonPrimitive((index / 2).toString()),
                    "user_id" to JsonPrimitive("u${index % 2}"),
                )
        }

    /** Row [index] with random values, a column now and then absent. */
    private fun Random.row(keys: Keys, index: Int): JsonObject {
        val columns = LinkedHashMap<String, JsonElement>(keyColumnsOf(keys, index))
        columns["list"] = JsonPrimitive(LISTS[nextInt(LISTS.size)])
        if (nextInt(4) != 0) columns["status"] = JsonPrimitive(STATUSES[nextInt(STATUSES.size)])
        if (nextInt(3) != 0) columns["flag"] = JsonPrimitive(FLAGS[nextInt(FLAGS.size)])
        columns["title"] = JsonPrimitive("t$index-${nextInt(4)}")
        columns["code"] = JsonPrimitive("c$index")
        return JsonObject(columns)
    }

    /**
     * [row] as [query] reads it: projected for the picker, with comments for the embedding queries.
     */
    private fun Random.asRead(query: Query, row: JsonObject, keys: Keys): JsonObject =
        when {
            query.signature == "picker" ->
                JsonObject(row.filterKeys { column -> column in keys.columns || column == "title" })
            query.signature.startsWith("emb-") -> {
                val comments = buildJsonArray {
                    repeat(1 + nextInt(3)) { position ->
                        add(buildJsonObject { put("body", "b$position") })
                    }
                }
                val shown =
                    if (query.signature == "emb-all") comments else JsonArray(comments.take(1))
                JsonObject(row + ("comments" to shown))
            }
            else -> row
        }

    private fun indexOf(keys: Keys, row: JsonObject): Int =
        when (keys) {
            Keys.INT -> (row.getValue("id") as JsonPrimitive).content.toInt()
            Keys.UUID -> uuids.indexOf((row.getValue("id") as JsonPrimitive).content)
            Keys.COMPOSITE ->
                (row.getValue("week") as JsonPrimitive).content.toInt() * 2 +
                    (row.getValue("user_id") as JsonPrimitive).content.removePrefix("u").toInt()
        }

    /**
     * Caches each query's answer as its own fetch saw the rows, so one row can differ between
     * payloads; a quarter of the payloads stale, and fetched at shuffled times.
     */
    private suspend fun seed(
        random: Random,
        keys: Keys,
        objectDao: FakeObjectDao,
        store: OfflineStore,
        queries: List<Query>,
    ): Map<String, ObjectRow> {
        val base = (0 until ROWS).map { index -> random.row(keys, index) }
        val cached = queries.filter { query -> query.cached }
        val ages = (1..cached.size).shuffled(random)
        queries
            .filter { query -> query.shaped }
            .forEach { query ->
                store.queryShapes.remember(TABLE, query.signature, shapeOf(query), persist = false)
            }
        return cached
            .mapIndexed { position, query ->
                val admission = admissionFor(query)
                val seen = base.map { row ->
                    if (random.nextInt(4) == 0) random.row(keys, indexOf(keys, row)) else row
                }
                val held =
                    seen
                        .filter { row -> admission.admits(row) && random.nextInt(4) != 0 }
                        .map { row -> random.asRead(query, row, keys) }
                val payload = JsonArray(held).toString()
                val fetchedAt = NOW - 1_000L * ages[position] - random.nextLong(500)
                val updatedAt = if (random.nextInt(4) == 0) staleStampOf(fetchedAt) else fetchedAt
                objectDao.seedPayload(TABLE, query.signature, payload, updatedAt)
                query.signature to ObjectRow(TABLE, query.signature, payload, updatedAt)
            }
            .toMap()
    }

    /** A condition, evaluated by the engine or not, on a key, a filtered column or another. */
    private fun Random.condition(keys: Keys): FilterDescriptor.Condition =
        when (nextInt(11)) {
            0,
            1,
            2 -> {
                val (column, value) = writtenKeyOf(keys, nextInt(KEYS)).entries.first()
                FilterDescriptor.Condition(column, "eq", (value as JsonPrimitive).content)
            }
            3 -> FilterDescriptor.Condition("list", "eq", LISTS[nextInt(LISTS.size)])
            4 -> FilterDescriptor.Condition("status", "eq", "done")
            5 -> FilterDescriptor.Condition("flag", "eq", "urgent")
            6 -> FilterDescriptor.Condition("list", "in", "(A,C)")
            7 -> FilterDescriptor.Condition("flag", "is", "null")
            8 -> FilterDescriptor.Condition("status", "neq", "done")
            9 -> FilterDescriptor.Condition("code", "eq", "c${nextInt(KEYS)}")
            else -> FilterDescriptor.Condition("title", "like", "t*")
        }

    private fun Random.filter(keys: Keys): FilterDescriptor =
        FilterDescriptor(
            mode = if (nextInt(6) == 0) FilterDescriptor.Mode.OR else FilterDescriptor.Mode.AND,
            conditions = List(1 + nextInt(2)) { condition(keys) },
        )

    /**
     * Some of a row's columns, with its key as a client may write it when [keyed] (now and then
     * without); a keyed body seldom names its list.
     */
    private fun Random.partial(keys: Keys, keyed: Boolean): JsonObject {
        val index = nextInt(KEYS)
        val full = row(keys, index)
        val kept = full.filterKeys { column ->
            when {
                column in keys.columns -> false
                column == "code" -> keyed && nextInt(3) == 0
                column == "list" -> nextInt(3) == 0
                else -> nextBoolean()
            }
        }
        val body = if (keyed && nextInt(8) != 0) writtenKeyOf(keys, index) + kept else kept
        return JsonObject(body.ifEmpty { mapOf("title" to JsonPrimitive("x")) })
    }

    /** A write to the table, as queued or as the server accepted it. */
    private class Write(
        val operation: String,
        val body: String,
        val filter: FilterDescriptor?,
        val onConflict: String?,
        val ignoreDuplicates: Boolean,
    )

    private fun Random.write(keys: Keys): Write =
        when (WRITES[nextInt(WRITES.size)]) {
            MutationOperation.INSERT ->
                Write(
                    MutationOperation.INSERT,
                    JsonArray(List(1 + nextInt(2)) { partial(keys, keyed = true) }).toString(),
                    filter = null,
                    onConflict = null,
                    ignoreDuplicates = false,
                )
            MutationOperation.UPSERT -> {
                val onCode = nextInt(5) == 0
                val rows =
                    List(1 + nextInt(2)) {
                        val body = partial(keys, keyed = true)
                        if (onCode)
                            JsonObject(body + ("code" to JsonPrimitive("c${nextInt(KEYS)}")))
                        else body
                    }
                Write(
                    MutationOperation.UPSERT,
                    JsonArray(rows).toString(),
                    filter = null,
                    onConflict = if (onCode) "code" else null,
                    ignoreDuplicates = nextInt(6) == 0,
                )
            }
            MutationOperation.UPDATE ->
                Write(
                    MutationOperation.UPDATE,
                    partial(keys, keyed = false).toString(),
                    filter(keys),
                    onConflict = null,
                    ignoreDuplicates = false,
                )
            else ->
                Write(
                    MutationOperation.DELETE,
                    "{}",
                    filter(keys),
                    onConflict = null,
                    ignoreDuplicates = false,
                )
        }

    /** Queues [write], as an offline client does; its mutation's id. */
    private suspend fun queue(store: OfflineStore, write: Write): Long =
        when (write.operation) {
            MutationOperation.INSERT ->
                store.enqueueInsert(TABLE, OfflineJson.parseToJsonElement(write.body) as JsonArray)
            MutationOperation.UPSERT ->
                store.enqueueUpsert(
                    TABLE,
                    OfflineJson.parseToJsonElement(write.body) as JsonArray,
                    onConflict = write.onConflict,
                    ignoreDuplicates = write.ignoreDuplicates,
                )
            MutationOperation.UPDATE ->
                store.enqueueUpdate(TABLE, OfflineJson.parseToJsonElement(write.body), write.filter)
            else -> store.enqueueDelete(TABLE, write.filter)
        }

    private fun projectionFor(query: Query): Projection? =
        if (query.shaped) projectionOf(shapeOf(query)) else null

    private fun admissionFor(query: Query): QueryAdmission =
        if (query.shaped) shapeOf(query).admission else QueryAdmission.EVERY_ROW

    /**
     * The keyed rows the queries other than [self] hold, one per key from the most recently fetched
     * payload, read with [self]'s projection ([sameProjection]) or another, or by a query of
     * unknown shape; leaving out keys [own] holds. A query of unknown shape shares no projection.
     */
    private fun everyRow(
        self: Query,
        queries: List<Query>,
        payloads: Map<String, ObjectRow>,
        keyColumns: List<String>,
        sameProjection: Boolean,
        own: List<JsonElement>?,
    ): List<JsonObject> {
        val projection = projectionFor(self)
        if (sameProjection && projection == null) return emptyList()
        val bySignature = queries.associateBy { query -> query.signature }
        val ownKeys =
            own.orEmpty().mapNotNullTo(HashSet()) { element ->
                (element as? JsonObject)?.let { row -> keyOf(row, keyColumns) }
            }
        val seen = HashSet<List<String>>()
        return payloads.values
            .filter { payload ->
                payload.signature != self.signature &&
                    (projectionFor(bySignature.getValue(payload.signature)) == projection) ==
                        sameProjection
            }
            .sortedByDescending { payload -> fetchedAtOf(payload.updatedAt) }
            .flatMap { payload -> payloadRowsOf(OfflineJson, payload.payload).orEmpty() }
            .mapNotNull { element -> element as? JsonObject }
            .filter { row ->
                val key = keyOf(row, keyColumns)
                key != null && key !in ownKeys && seen.add(key)
            }
    }

    /** [mutations] overlaid onto [payload] for [query], given [known] rows. */
    private fun everyRowOverlay(
        payload: String,
        mutations: List<MutationRow>,
        query: Query,
        keyColumns: List<String>,
        known: List<JsonObject>,
        mayAlreadyHold: (MutationRow) -> Boolean,
    ): String =
        MutationOverlay(
                json = OfflineJson,
                admission = admissionFor(query),
                primaryKeys = PrimaryKeys { keyColumns },
                mayAlreadyHold = mayAlreadyHold,
            )
            .overlay(payload, mutations, known)

    @Test
    fun every_read_overlay_shows_what_every_known_row_would() = runTest {
        var compared = 0
        val mismatches = mutableListOf<String>()
        for (keys in Keys.entries) {
            repeat(SCENARIOS) { seed ->
                // Given — a table cached by twelve queries, one of them live with no payload, and
                // pending writes, some replayed and some whose send may have reached the server
                val random = Random(seed * 31 + keys.ordinal)
                val queries = queriesFor(keys)
                val objectDao = FakeObjectDao()
                val mutationDao = FakeMutationDao()
                val store =
                    newTestOfflineStore(
                        objectDao = objectDao,
                        mutationDao = mutationDao,
                        nowMillis = { NOW },
                    )
                store.primaryKeys = PrimaryKeys { keys.columns }
                val payloads = seed(random, keys, objectDao, store, queries)
                val unconfirmed = mutableSetOf<Long>()
                repeat(1 + random.nextInt(7)) {
                    val id = queue(store, random.write(keys))
                    when (random.nextInt(8)) {
                        0 -> store.markMutationSynced(id, TABLE)
                        1 -> if (store.markSendUnconfirmed(id)) unconfirmed += id
                    }
                }
                val all = mutationDao.allForTable(TABLE)
                val pending = all.filter { mutation -> mutation.syncedAtMs == null }
                val mayAlreadyHold: (MutationRow) -> Boolean = { mutation ->
                    mutation.syncedAtMs != null || mutation.id in unconfirmed
                }

                queries.forEach { query ->
                    // When — the query is read reactively, and, when it has a payload, one-shot
                    // and with a server answer holding some of its rows
                    val known =
                        everyRow(
                            query,
                            queries,
                            payloads,
                            keys.columns,
                            sameProjection = true,
                            null,
                        )
                    val passShape = query.shaped && seed % 2 == 0
                    val reactive =
                        store
                            .overlaidPayloadFlow(
                                TABLE,
                                query.signature,
                                if (passShape) shapeOf(query) else null,
                            )
                            .first()

                    // Then — each as with every row the others hold
                    if (!query.cached) {
                        val expected =
                            everyRowOverlay("[]", all, query, keys.columns, known, mayAlreadyHold)
                                .takeUnless { overlaid -> overlaid == "[]" }
                        compared++
                        if (reactive != expected)
                            mismatches +=
                                "$keys seed $seed ${query.signature} reactive, no payload: " +
                                    "$reactive, not $expected"
                        return@forEach
                    }
                    val payload = payloads.getValue(query.signature).payload
                    val expected =
                        everyRowOverlay(payload, all, query, keys.columns, known, mayAlreadyHold)
                    val oneShot = store.overlayCachedPayload(TABLE, query.signature, payload)
                    val answer =
                        JsonArray(
                                payloadRowsOf(OfflineJson, payload).orEmpty().filter {
                                    random.nextInt(3) != 0
                                }
                            )
                            .toString()
                    val expectedResponse =
                        everyRowOverlay(answer, pending, query, keys.columns, known) { mutation ->
                            mutation.id in unconfirmed
                        }
                    val response = store.overlayPendingOnResponse(TABLE, query.signature, answer)
                    compared += 3
                    if (oneShot != expected)
                        mismatches +=
                            "$keys seed $seed ${query.signature} one-shot: $oneShot, not $expected"
                    if (reactive != expected)
                        mismatches +=
                            "$keys seed $seed ${query.signature} reactive: $reactive, not $expected"
                    if (response != expectedResponse)
                        mismatches +=
                            "$keys seed $seed ${query.signature} response: $response, " +
                                "not $expectedResponse"
                }
            }
        }
        assertEquals(0, mismatches.size, "$compared compared: ${mismatches.take(2)}")
    }

    @Test
    fun every_patch_writes_and_marks_what_every_known_row_would() = runTest {
        var compared = 0
        val mismatches = mutableListOf<String>()
        for (keys in Keys.entries) {
            repeat(SCENARIOS) { seed ->
                // Given — a table cached by twelve queries, and one of them live with no payload
                val random = Random(seed * 17 + keys.ordinal)
                val queries = queriesFor(keys)
                val objectDao = FakeObjectDao()
                val store = newTestOfflineStore(objectDao = objectDao, nowMillis = { NOW })
                val primaryKeys = PrimaryKeys { keys.columns }
                store.primaryKeys = primaryKeys
                store.liveSignatureProvider = { setOf(LIVE) }
                val marked = mutableSetOf<String>()
                store.onQueriesMarkedStale = { _, signatures -> marked += signatures }
                val payloads = seed(random, keys, objectDao, store, queries)

                // When — the server accepted one write, or an update that returned several rows
                val writes = acceptWrite(random, keys, store)

                // Then — each payload, and each query to fetch again, as with every row
                val expectedMarked = mutableSetOf<String>()
                queries.forEach { query ->
                    val before = payloads[query.signature]
                    val rows = before?.let { payloadRowsOf(OfflineJson, it.payload) }.orEmpty()
                    val same =
                        everyRow(
                            query,
                            queries,
                            payloads,
                            keys.columns,
                            sameProjection = true,
                            rows,
                        )
                    val sameKeys = same.mapTo(HashSet()) { row -> keyOf(row, keys.columns) }
                    val other =
                        everyRow(
                                query,
                                queries,
                                payloads,
                                keys.columns,
                                sameProjection = false,
                                rows,
                            )
                            .filter { row -> keyOf(row, keys.columns) !in sameKeys }
                    val target =
                        PatchTarget(
                            rows,
                            if (query.shaped) shapeOf(query) else null,
                            same,
                            other,
                            keys.columns.toSet(),
                        )
                    val overlay =
                        MutationOverlay(
                            json = OfflineJson,
                            admission = target.admission,
                            primaryKeys = primaryKeys,
                            mayAlreadyHold = { true },
                            placesKeylessUpsertRows = false,
                        )
                    val wrong =
                        patchMayLeaveWrong(writes, target, OfflineJson) { mutation ->
                            conflictColumnsOf(mutation, primaryKeys)
                        }
                    val expected =
                        if (before == null) {
                            // A live query with no payload: made of the rows written, or fetched
                            // again
                            val made =
                                overlay
                                    .overlayRows(emptyList(), writes, same)
                                    .written
                                    .filter(target.admission::admits)
                            when {
                                wrong -> null.also { expectedMarked += query.signature }
                                made.isEmpty() -> null
                                else ->
                                    ObjectRow(
                                        TABLE,
                                        query.signature,
                                        overlay.encode(made),
                                        staleStampOf(NOW),
                                    )
                            }
                        } else {
                            val patched = overlay.overlayRows(rows, writes, same)
                            if (
                                patched.visible == rows &&
                                    (!wrong || isStaleStamp(before.updatedAt))
                            ) {
                                before
                            } else {
                                if (wrong) expectedMarked += query.signature
                                before.copy(
                                    payload = overlay.encode(patched.visible),
                                    updatedAt =
                                        if (wrong) staleStampOf(before.updatedAt)
                                        else before.updatedAt,
                                )
                            }
                        }
                    val after = objectDao.getRow(TABLE, query.signature)
                    compared++
                    if (
                        after?.payload != expected?.payload ||
                            after?.updatedAt != expected?.updatedAt
                    )
                        mismatches +=
                            "$keys seed $seed ${query.signature}: ${after?.payload} " +
                                "@${after?.updatedAt}, not ${expected?.payload} " +
                                "@${expected?.updatedAt}"
                }
                if (marked != expectedMarked)
                    mismatches += "$keys seed $seed marked $marked, not $expectedMarked"
            }
        }
        assertEquals(0, mismatches.size, "$compared compared: ${mismatches.take(2)}")
    }

    /**
     * Patches into [store] one write the server accepted, or, a third of the time, the rows an
     * update returned, each patched as an update by its key; the writes the patch applied.
     */
    private suspend fun acceptWrite(
        random: Random,
        keys: Keys,
        store: OfflineStore,
    ): List<MutationRow> {
        if (random.nextInt(3) == 0) {
            val body = random.partial(keys, keyed = false)
            val returned =
                List(1 + random.nextInt(3)) {
                    JsonObject(
                        keyColumnsOf(keys, random.nextInt(KEYS)) +
                            random.partial(keys, keyed = false)
                    )
                }
            store.patchReturnedRows(
                TABLE,
                body,
                JsonArray(returned).toString(),
                store.monotonicTickNow(),
            )
            return returned.map { row ->
                val key =
                    keys.columns.map { column ->
                        FilterDescriptor.Condition(
                            column,
                            "eq",
                            (row.getValue(column) as JsonPrimitive).content,
                        )
                    }
                MutationRow(
                    table = TABLE,
                    operation = MutationOperation.UPDATE,
                    bodyJson = JsonObject(body + row).toString(),
                    filterJson =
                        FilterEngine.encode(FilterDescriptor(conditions = key), OfflineJson),
                    createdAtMs = NOW,
                )
            }
        }
        val write = random.write(keys)
        val row =
            MutationRow(
                table = TABLE,
                operation = write.operation,
                bodyJson = write.body,
                filterJson =
                    write.filter?.let { filter -> FilterEngine.encode(filter, OfflineJson) },
                createdAtMs = NOW,
                onConflict = write.onConflict,
                ignoreDuplicates =
                    if (write.operation == MutationOperation.UPSERT) write.ignoreDuplicates
                    else null,
            )
        store.patchCachedQueries(
            TABLE,
            row.operation,
            row.bodyJson,
            row.filterJson,
            onConflict = row.onConflict,
            ignoreDuplicates = row.ignoreDuplicates,
            liveSignatures = setOf(LIVE),
        )
        return listOf(row)
    }

    @Test
    fun reads_after_a_patch_show_what_every_known_row_would() = runTest {
        var compared = 0
        val mismatches = mutableListOf<String>()
        for (keys in Keys.entries) {
            repeat(SCENARIOS / 4) { seed ->
                // Given — pending writes, and every read once, so the store keeps what it parsed
                val random = Random(seed * 13 + keys.ordinal)
                val queries = queriesFor(keys)
                val objectDao = FakeObjectDao()
                val mutationDao = FakeMutationDao()
                val store =
                    newTestOfflineStore(
                        objectDao = objectDao,
                        mutationDao = mutationDao,
                        nowMillis = { NOW },
                    )
                store.primaryKeys = PrimaryKeys { keys.columns }
                store.liveSignatureProvider = { setOf(LIVE) }
                val before = seed(random, keys, objectDao, store, queries)
                repeat(1 + random.nextInt(5)) { queue(store, random.write(keys)) }
                queries.forEach { query ->
                    before[query.signature]?.let { payload ->
                        store.overlayCachedPayload(TABLE, query.signature, payload.payload)
                    }
                    store.overlaidPayloadFlow(TABLE, query.signature).first()
                }

                // When — accepted writes rewrite the payloads
                repeat(1 + random.nextInt(3)) {
                    val write = random.write(keys)
                    store.patchCachedQueries(
                        TABLE,
                        write.operation,
                        write.body,
                        write.filter?.let { filter -> FilterEngine.encode(filter, OfflineJson) },
                        onConflict = write.onConflict,
                        ignoreDuplicates =
                            if (write.operation == MutationOperation.UPSERT) write.ignoreDuplicates
                            else null,
                        liveSignatures = setOf(LIVE),
                    )
                }

                // Then — every read again, as with every row the others now hold
                val payloads =
                    queries
                        .mapNotNull { query ->
                            objectDao.getRow(TABLE, query.signature)?.let { row ->
                                query.signature to row
                            }
                        }
                        .toMap()
                val all = mutationDao.allForTable(TABLE)
                queries.forEach { query ->
                    val known =
                        everyRow(
                            query,
                            queries,
                            payloads,
                            keys.columns,
                            sameProjection = true,
                            null,
                        )
                    val payload = payloads[query.signature]?.payload
                    val overlaid =
                        everyRowOverlay(payload ?: "[]", all, query, keys.columns, known) { false }
                    val expected =
                        if (payload == null) overlaid.takeUnless { text -> text == "[]" }
                        else overlaid
                    val reactive = store.overlaidPayloadFlow(TABLE, query.signature).first()
                    compared++
                    if (reactive != expected)
                        mismatches +=
                            "$keys seed $seed ${query.signature} reactive: $reactive, not $expected"
                    if (payload != null) {
                        val oneShot = store.overlayCachedPayload(TABLE, query.signature, payload)
                        compared++
                        if (oneShot != expected)
                            mismatches +=
                                "$keys seed $seed ${query.signature} one-shot: $oneShot, " +
                                    "not $expected"
                    }
                }
            }
        }
        assertEquals(0, mismatches.size, "$compared compared: ${mismatches.take(2)}")
    }

    /**
     * A store over the payloads [lists] give, as (cache key, payload, stamp), with their shapes.
     */
    private suspend fun storeOf(
        vararg lists: Triple<String, String, Long>
    ): Pair<OfflineStore, FakeObjectDao> {
        val objectDao = FakeObjectDao()
        val store = newTestOfflineStore(objectDao = objectDao, nowMillis = { NOW })
        lists.forEach { (signature, payload, updatedAt) ->
            objectDao.seedPayload(TABLE, signature, payload, updatedAt)
            val query = queriesFor(Keys.INT).single { query -> query.signature == signature }
            store.queryShapes.remember(TABLE, signature, shapeOf(query), persist = false)
        }
        return store to objectDao
    }

    private fun byColumn(column: String, value: String): FilterDescriptor =
        FilterDescriptor(conditions = listOf(FilterDescriptor.Condition(column, "eq", value)))

    @Test
    fun of_several_copies_of_a_row_the_newest_fetch_moves_in() = runTest {
        // Given — row 5 held by three lists, fetched at three times; the newest is stale
        val (store, _) =
            storeOf(
                Triple(
                    "list-a",
                    """[{"id":5,"list":"A","status":"done","title":"oldest"}]""",
                    NOW - 3_000,
                ),
                Triple(
                    "everything",
                    """[{"id":5,"list":"A","status":"done","title":"newest"}]""",
                    staleStampOf(NOW - 1_000),
                ),
                Triple(
                    "done",
                    """[{"id":5,"list":"A","status":"done","title":"middle"}]""",
                    NOW - 2_000,
                ),
                Triple("list-b", LIST_B, NOW - 4_000),
            )

        // When — a queued update moves row 5 into list B
        store.enqueueUpdate(
            TABLE,
            OfflineJson.parseToJsonElement("""{"list":"B"}"""),
            byColumn("id", "5"),
        )

        // Then — the newest copy joins
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},{"id":5,"list":"B","status":"done","title":"newest"}]""",
            store.overlayCachedPayload(TABLE, "list-b", LIST_B),
        )
    }

    @Test
    fun a_queued_partial_upsert_then_a_moving_update_brings_the_whole_newest_row() = runTest {
        // Given — row 5 held by two lists, and list B
        val (store, _) =
            storeOf(
                Triple(
                    "everything",
                    """[{"id":5,"list":"A","status":"open","title":"full","code":"c5"}]""",
                    NOW - 1_000,
                ),
                Triple(
                    "list-a",
                    """[{"id":5,"list":"A","status":"open","title":"older","code":"c5"}]""",
                    NOW - 2_000,
                ),
                Triple("list-b", LIST_B, NOW - 4_000),
            )

        // When — an upsert of some of its columns, naming a list B excludes, flags it, and an
        // update moves every urgent row into list B
        store.enqueueUpsert(
            TABLE,
            JsonArray(
                listOf(
                    OfflineJson.parseToJsonElement(
                        """{"id":"5","list":"A","title":"renamed","flag":"urgent"}"""
                    )
                )
            ),
        )
        store.enqueueUpdate(
            TABLE,
            OfflineJson.parseToJsonElement("""{"list":"B"}"""),
            byColumn("flag", "urgent"),
        )

        // Then — the whole newest row joins, with the upsert's columns
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},""" +
                """{"id":"5","list":"B","status":"open","title":"renamed","code":"c5","flag":"urgent"}]""",
            store.overlayCachedPayload(TABLE, "list-b", LIST_B),
        )
    }

    @Test
    fun a_delete_then_an_insert_of_one_key_shows_the_new_row_alone() = runTest {
        // Given — row 5 in list A and in everything
        val oldRow = """[{"id":5,"list":"A","title":"old","code":"c5"}]"""
        val (store, _) =
            storeOf(
                Triple("list-a", oldRow, NOW - 2_000),
                Triple("everything", oldRow, NOW - 1_000),
                Triple("list-b", LIST_B, NOW - 4_000),
            )

        // When — it is deleted, inserted again in list B, and upserted
        store.enqueueDelete(TABLE, byColumn("id", "5"))
        store.enqueueInsert(
            TABLE,
            JsonArray(
                listOf(OfflineJson.parseToJsonElement("""{"id":5,"list":"B","title":"new"}"""))
            ),
        )
        store.enqueueUpsert(
            TABLE,
            JsonArray(listOf(OfflineJson.parseToJsonElement("""{"id":5,"status":"open"}"""))),
        )

        // Then — gone from list A; in list B once, as inserted and upserted
        assertEquals("[]", store.overlayCachedPayload(TABLE, "list-a", oldRow))
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},{"id":5,"list":"B","title":"new","status":"open"}]""",
            store.overlayCachedPayload(TABLE, "list-b", LIST_B),
        )
    }

    @Test
    fun a_row_two_lists_filters_match_moves_between_them_once() = runTest {
        // Given — row 5 in list A and in the urgent list
        val row = """{"id":5,"list":"A","flag":"urgent","title":"old"}"""
        val (store, objectDao) =
            storeOf(
                Triple("list-a", "[$row]", NOW - 3_000),
                Triple("urgent", "[$row]", NOW - 2_000),
                Triple("list-b", LIST_B, NOW - 4_000),
            )

        // When — online, it is renamed, then moved into list B
        store.patchCachedQueries(
            TABLE,
            MutationOperation.UPDATE,
            """{"title":"new"}""",
            FilterEngine.encode(byColumn("id", "5"), OfflineJson),
            liveSignatures = emptySet(),
        )
        store.patchCachedQueries(
            TABLE,
            MutationOperation.UPDATE,
            """{"list":"B"}""",
            FilterEngine.encode(byColumn("id", "5"), OfflineJson),
            liveSignatures = emptySet(),
        )

        // Then — it left list A, stays in the urgent list, and joined list B once
        assertEquals("[]", objectDao.getRow(TABLE, "list-a")?.payload)
        assertEquals(
            """[{"id":5,"list":"B","flag":"urgent","title":"new"}]""",
            objectDao.getRow(TABLE, "urgent")?.payload,
        )
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},{"id":5,"list":"B","flag":"urgent","title":"new"}]""",
            objectDao.getRow(TABLE, "list-b")?.payload,
        )
    }

    private companion object {
        const val TABLE = "tasks"
        const val NOW = 1_000_000L
        const val SCENARIOS = 250
        const val ROWS = 10

        /** Keys the writes use: the cached rows' and two more. */
        const val KEYS = 12

        /** The live query with no payload. */
        const val LIVE = "list-c"
        const val LIST_B = """[{"id":1,"list":"B","title":"b"}]"""
        val LISTS = listOf("A", "B", "C")
        val STATUSES = listOf("open", "done")
        val FLAGS = listOf("normal", "urgent")
        val WRITES =
            listOf(
                MutationOperation.INSERT,
                MutationOperation.UPSERT,
                MutationOperation.UPSERT,
                MutationOperation.UPDATE,
                MutationOperation.UPDATE,
                MutationOperation.UPDATE,
                MutationOperation.DELETE,
            )
    }
}
