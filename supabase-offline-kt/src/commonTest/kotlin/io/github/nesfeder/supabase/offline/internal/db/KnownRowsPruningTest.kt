// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.EngineJson
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
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.queryShapeOf
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.enqueueDelete
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.overlaidPayloadFlow
import io.github.nesfeder.supabase.offline.testdoubles.overlayCachedPayload
import io.github.nesfeder.supabase.offline.testdoubles.overlayPendingOnResponse
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
        val cacheKey: String,
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
            query.cacheKey == "picker" ->
                JsonObject(row.filterKeys { column -> column in keys.columns || column == "title" })
            query.cacheKey.startsWith("emb-") -> {
                val comments = buildJsonArray {
                    repeat(1 + nextInt(3)) { position ->
                        add(buildJsonObject { put("body", "b$position") })
                    }
                }
                val shown =
                    if (query.cacheKey == "emb-all") comments else JsonArray(comments.take(1))
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
        queryCacheDao: FakeQueryCacheDao,
        store: TestStore,
        queries: List<Query>,
    ): Map<String, CachedQueryRow> {
        val base = (0 until ROWS).map { index -> random.row(keys, index) }
        val cached = queries.filter { query -> query.cached }
        val ages = (1..cached.size).shuffled(random)
        queries
            .filter { query -> query.shaped }
            .forEach { query ->
                store.queryCache.queryShapes.remember(
                    TABLE,
                    query.cacheKey,
                    shapeOf(query),
                    persist = false,
                )
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
                queryCacheDao.seedPayload(TABLE, query.cacheKey, payload, updatedAt)
                query.cacheKey to CachedQueryRow(TABLE, query.cacheKey, payload, updatedAt)
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
    private suspend fun queue(store: TestStore, write: Write): Long =
        when (write.operation) {
            MutationOperation.INSERT ->
                store.mutationQueue.enqueueInsert(
                    TABLE,
                    EngineJson.parseToJsonElement(write.body) as JsonArray,
                )
            MutationOperation.UPSERT ->
                store.mutationQueue.enqueueUpsert(
                    TABLE,
                    EngineJson.parseToJsonElement(write.body) as JsonArray,
                    onConflict = write.onConflict,
                    ignoreDuplicates = write.ignoreDuplicates,
                )
            MutationOperation.UPDATE ->
                store.mutationQueue.enqueueUpdate(
                    TABLE,
                    EngineJson.parseToJsonElement(write.body),
                    write.filter,
                )
            else -> store.mutationQueue.enqueueDelete(TABLE, write.filter)
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
        payloads: Map<String, CachedQueryRow>,
        keyColumns: List<String>,
        sameProjection: Boolean,
        own: List<JsonElement>?,
    ): List<JsonObject> {
        val projection = projectionFor(self)
        if (sameProjection && projection == null) return emptyList()
        val byCacheKey = queries.associateBy { query -> query.cacheKey }
        val ownKeys =
            own.orEmpty().mapNotNullTo(HashSet()) { element ->
                (element as? JsonObject)?.let { row -> keyOf(row, keyColumns) }
            }
        val seen = HashSet<List<String>>()
        return payloads.values
            .filter { payload ->
                payload.cacheKey != self.cacheKey &&
                    (projectionFor(byCacheKey.getValue(payload.cacheKey)) == projection) ==
                        sameProjection
            }
            .sortedByDescending { payload -> fetchedAtOf(payload.updatedAt) }
            .flatMap { payload -> payloadRowsOf(EngineJson, payload.payload).orEmpty() }
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
                json = EngineJson,
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
                val queryCacheDao = FakeQueryCacheDao()
                val mutationDao = FakeMutationDao()
                val store =
                    newTestStore(
                        queryCacheDao = queryCacheDao,
                        mutationDao = mutationDao,
                        nowMillis = { NOW },
                        primaryKeys = PrimaryKeys { keys.columns },
                    )
                val payloads = seed(random, keys, queryCacheDao, store, queries)
                val unconfirmed = mutableSetOf<Long>()
                repeat(1 + random.nextInt(7)) {
                    val id = queue(store, random.write(keys))
                    when (random.nextInt(8)) {
                        0 -> store.mutationQueue.recordReplayed(id, TABLE)
                        1 -> if (store.mutationQueue.markSendUnconfirmed(id)) unconfirmed += id
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
                        store.queryOverlay
                            .overlaidPayloadFlow(
                                TABLE,
                                query.cacheKey,
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
                                "$keys seed $seed ${query.cacheKey} reactive, no payload: " +
                                    "$reactive, not $expected"
                        return@forEach
                    }
                    val payload = payloads.getValue(query.cacheKey).payload
                    val expected =
                        everyRowOverlay(payload, all, query, keys.columns, known, mayAlreadyHold)
                    val oneShot =
                        store.queryOverlay.overlayCachedPayload(TABLE, query.cacheKey, payload)
                    val answer =
                        JsonArray(
                                payloadRowsOf(EngineJson, payload).orEmpty().filter {
                                    random.nextInt(3) != 0
                                }
                            )
                            .toString()
                    val expectedResponse =
                        everyRowOverlay(answer, pending, query, keys.columns, known) { mutation ->
                            mutation.id in unconfirmed
                        }
                    val response =
                        store.queryOverlay.overlayPendingOnResponse(TABLE, query.cacheKey, answer)
                    compared += 3
                    if (oneShot != expected)
                        mismatches +=
                            "$keys seed $seed ${query.cacheKey} one-shot: $oneShot, not $expected"
                    if (reactive != expected)
                        mismatches +=
                            "$keys seed $seed ${query.cacheKey} reactive: $reactive, not $expected"
                    if (response != expectedResponse)
                        mismatches +=
                            "$keys seed $seed ${query.cacheKey} response: $response, " +
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
                val queryCacheDao = FakeQueryCacheDao()
                val primaryKeys = PrimaryKeys { keys.columns }
                val marked = mutableSetOf<String>()
                val store =
                    newTestStore(
                        queryCacheDao = queryCacheDao,
                        nowMillis = { NOW },
                        primaryKeys = primaryKeys,
                        liveCacheKeysOf = { setOf(LIVE) },
                        onQueriesMarkedStale = { _, cacheKeys -> marked += cacheKeys },
                    )
                val payloads = seed(random, keys, queryCacheDao, store, queries)

                // When — the server accepted one write, or an update that returned several rows
                val writes = acceptWrite(random, keys, store)

                // Then — each payload, and each query to fetch again, as with every row
                val expectedMarked = mutableSetOf<String>()
                queries.forEach { query ->
                    val before = payloads[query.cacheKey]
                    val rows = before?.let { payloadRowsOf(EngineJson, it.payload) }.orEmpty()
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
                            json = EngineJson,
                            admission = target.admission,
                            primaryKeys = primaryKeys,
                            mayAlreadyHold = { true },
                            placesKeylessUpsertRows = false,
                        )
                    val wrong =
                        patchMayLeaveWrong(writes, target, EngineJson) { mutation ->
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
                                wrong -> null.also { expectedMarked += query.cacheKey }
                                made.isEmpty() -> null
                                else ->
                                    CachedQueryRow(
                                        TABLE,
                                        query.cacheKey,
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
                                if (wrong) expectedMarked += query.cacheKey
                                before.copy(
                                    payload = overlay.encode(patched.visible),
                                    updatedAt =
                                        if (wrong) staleStampOf(before.updatedAt)
                                        else before.updatedAt,
                                )
                            }
                        }
                    val after = queryCacheDao.getRow(TABLE, query.cacheKey)
                    compared++
                    if (
                        after?.payload != expected?.payload ||
                            after?.updatedAt != expected?.updatedAt
                    )
                        mismatches +=
                            "$keys seed $seed ${query.cacheKey}: ${after?.payload} " +
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
        store: TestStore,
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
            store.cachePatcher.patchReturnedRows(
                TABLE,
                body,
                JsonArray(returned).toString(),
                store.freshness.tickNow(),
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
                    filterJson = encodeFilter(FilterDescriptor(conditions = key), EngineJson),
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
                filterJson = write.filter?.let { filter -> encodeFilter(filter, EngineJson) },
                createdAtMs = NOW,
                onConflict = write.onConflict,
                ignoreDuplicates =
                    if (write.operation == MutationOperation.UPSERT) write.ignoreDuplicates
                    else null,
            )
        store.cachePatcher.patchCachedQueries(
            TABLE,
            row.operation,
            row.bodyJson,
            row.filterJson,
            onConflict = row.onConflict,
            ignoreDuplicates = row.ignoreDuplicates,
            liveCacheKeys = setOf(LIVE),
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
                val queryCacheDao = FakeQueryCacheDao()
                val mutationDao = FakeMutationDao()
                val store =
                    newTestStore(
                        queryCacheDao = queryCacheDao,
                        mutationDao = mutationDao,
                        nowMillis = { NOW },
                        primaryKeys = PrimaryKeys { keys.columns },
                        liveCacheKeysOf = { setOf(LIVE) },
                    )
                val before = seed(random, keys, queryCacheDao, store, queries)
                repeat(1 + random.nextInt(5)) { queue(store, random.write(keys)) }
                queries.forEach { query ->
                    before[query.cacheKey]?.let { payload ->
                        store.queryOverlay.overlayCachedPayload(
                            TABLE,
                            query.cacheKey,
                            payload.payload,
                        )
                    }
                    store.queryOverlay.overlaidPayloadFlow(TABLE, query.cacheKey).first()
                }

                // When — accepted writes rewrite the payloads
                repeat(1 + random.nextInt(3)) {
                    val write = random.write(keys)
                    store.cachePatcher.patchCachedQueries(
                        TABLE,
                        write.operation,
                        write.body,
                        write.filter?.let { filter -> encodeFilter(filter, EngineJson) },
                        onConflict = write.onConflict,
                        ignoreDuplicates =
                            if (write.operation == MutationOperation.UPSERT) write.ignoreDuplicates
                            else null,
                        liveCacheKeys = setOf(LIVE),
                    )
                }

                // Then — every read again, as with every row the others now hold
                val payloads =
                    queries
                        .mapNotNull { query ->
                            queryCacheDao.getRow(TABLE, query.cacheKey)?.let { row ->
                                query.cacheKey to row
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
                    val payload = payloads[query.cacheKey]?.payload
                    val overlaid =
                        everyRowOverlay(payload ?: "[]", all, query, keys.columns, known) { false }
                    val expected =
                        if (payload == null) overlaid.takeUnless { text -> text == "[]" }
                        else overlaid
                    val reactive =
                        store.queryOverlay.overlaidPayloadFlow(TABLE, query.cacheKey).first()
                    compared++
                    if (reactive != expected)
                        mismatches +=
                            "$keys seed $seed ${query.cacheKey} reactive: $reactive, not $expected"
                    if (payload != null) {
                        val oneShot =
                            store.queryOverlay.overlayCachedPayload(TABLE, query.cacheKey, payload)
                        compared++
                        if (oneShot != expected)
                            mismatches +=
                                "$keys seed $seed ${query.cacheKey} one-shot: $oneShot, " +
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
    ): Pair<TestStore, FakeQueryCacheDao> {
        val queryCacheDao = FakeQueryCacheDao()
        val store = newTestStore(queryCacheDao = queryCacheDao, nowMillis = { NOW })
        lists.forEach { (cacheKey, payload, updatedAt) ->
            queryCacheDao.seedPayload(TABLE, cacheKey, payload, updatedAt)
            val query = queriesFor(Keys.INT).single { query -> query.cacheKey == cacheKey }
            store.queryCache.queryShapes.remember(TABLE, cacheKey, shapeOf(query), persist = false)
        }
        return store to queryCacheDao
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
        store.mutationQueue.enqueueUpdate(
            TABLE,
            EngineJson.parseToJsonElement("""{"list":"B"}"""),
            byColumn("id", "5"),
        )

        // Then — the newest copy joins
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},{"id":5,"list":"B","status":"done","title":"newest"}]""",
            store.queryOverlay.overlayCachedPayload(TABLE, "list-b", LIST_B),
        )
    }

    @Test
    fun a_row_moved_in_by_key_is_read_from_the_newest_fetch_after_one_lands() = runTest {
        // Given — row 5 in list A, list B, and a queued upsert of some of its columns moving it
        // into list B, read once
        val (store, _) =
            storeOf(
                Triple(
                    "list-a",
                    """[{"id":5,"list":"A","title":"older","code":"c5"}]""",
                    NOW - 3_000,
                ),
                Triple("list-b", LIST_B, NOW - 4_000),
            )
        store.queryCache.queryShapes.remember(
            TABLE,
            "everything",
            shapeOf(queriesFor(Keys.INT).single { query -> query.cacheKey == "everything" }),
            persist = false,
        )
        store.mutationQueue.enqueueUpsert(
            TABLE,
            JsonArray(listOf(EngineJson.parseToJsonElement("""{"id":5,"list":"B"}"""))),
        )
        val first = store.queryOverlay.overlayCachedPayload(TABLE, "list-b", LIST_B)

        // When — a newer fetch of every row lands, holding row 5 as it is now
        store.queryCache.cacheResponse(
            TABLE,
            "everything",
            """[{"id":5,"list":"A","title":"newest","code":"c5"}]""",
        )
        val second = store.queryOverlay.overlayCachedPayload(TABLE, "list-b", LIST_B)

        // Then — each read moved in the newest copy it could know
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},{"id":5,"list":"B","title":"older","code":"c5"}]""",
            first,
        )
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},{"id":5,"list":"B","title":"newest","code":"c5"}]""",
            second,
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
        store.mutationQueue.enqueueUpsert(
            TABLE,
            JsonArray(
                listOf(
                    EngineJson.parseToJsonElement(
                        """{"id":"5","list":"A","title":"renamed","flag":"urgent"}"""
                    )
                )
            ),
        )
        store.mutationQueue.enqueueUpdate(
            TABLE,
            EngineJson.parseToJsonElement("""{"list":"B"}"""),
            byColumn("flag", "urgent"),
        )

        // Then — the whole newest row joins, with the upsert's columns
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},""" +
                """{"id":"5","list":"B","status":"open","title":"renamed","code":"c5","flag":"urgent"}]""",
            store.queryOverlay.overlayCachedPayload(TABLE, "list-b", LIST_B),
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
        store.mutationQueue.enqueueDelete(TABLE, byColumn("id", "5"))
        store.mutationQueue.enqueueInsert(
            TABLE,
            JsonArray(
                listOf(EngineJson.parseToJsonElement("""{"id":5,"list":"B","title":"new"}"""))
            ),
        )
        store.mutationQueue.enqueueUpsert(
            TABLE,
            JsonArray(listOf(EngineJson.parseToJsonElement("""{"id":5,"status":"open"}"""))),
        )

        // Then — gone from list A; in list B once, as inserted and upserted
        assertEquals("[]", store.queryOverlay.overlayCachedPayload(TABLE, "list-a", oldRow))
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},{"id":5,"list":"B","title":"new","status":"open"}]""",
            store.queryOverlay.overlayCachedPayload(TABLE, "list-b", LIST_B),
        )
    }

    @Test
    fun a_row_two_lists_filters_match_moves_between_them_once() = runTest {
        // Given — row 5 in list A and in the urgent list
        val row = """{"id":5,"list":"A","flag":"urgent","title":"old"}"""
        val (store, queryCacheDao) =
            storeOf(
                Triple("list-a", "[$row]", NOW - 3_000),
                Triple("urgent", "[$row]", NOW - 2_000),
                Triple("list-b", LIST_B, NOW - 4_000),
            )

        // When — online, it is renamed, then moved into list B
        store.cachePatcher.patchCachedQueries(
            TABLE,
            MutationOperation.UPDATE,
            """{"title":"new"}""",
            encodeFilter(byColumn("id", "5"), EngineJson),
            liveCacheKeys = emptySet(),
        )
        store.cachePatcher.patchCachedQueries(
            TABLE,
            MutationOperation.UPDATE,
            """{"list":"B"}""",
            encodeFilter(byColumn("id", "5"), EngineJson),
            liveCacheKeys = emptySet(),
        )

        // Then — it left list A, stays in the urgent list, and joined list B once
        assertEquals("[]", queryCacheDao.getRow(TABLE, "list-a")?.payload)
        assertEquals(
            """[{"id":5,"list":"B","flag":"urgent","title":"new"}]""",
            queryCacheDao.getRow(TABLE, "urgent")?.payload,
        )
        assertEquals(
            """[{"id":1,"list":"B","title":"b"},{"id":5,"list":"B","flag":"urgent","title":"new"}]""",
            queryCacheDao.getRow(TABLE, "list-b")?.payload,
        )
    }

    @Test
    fun a_query_an_update_leaves_fresh_shows_what_the_server_then_holds() = runTest {
        // Given / When / Then — per scenario: a table the server holds whole, every query's
        // payload its answer, and an update the server applies; each query the patch neither
        // marked stale nor stamped stale shows the rows the server now returns for it
        var fresh = 0
        val mismatches = mutableListOf<String>()
        repeat(SCENARIOS * 4) { seed ->
            val random = Random(seed)
            val server =
                List(ROWS) { index ->
                    buildJsonObject {
                        put("id", index)
                        put("list", LISTS[random.nextInt(LISTS.size)])
                        put("status", STATUSES[random.nextInt(STATUSES.size)])
                        put("position", random.nextInt(ROWS))
                        put("title", "t$index")
                    }
                }
            val queries = TRUTH_QUERIES
            val queryCacheDao = FakeQueryCacheDao()
            val marked = mutableSetOf<String>()
            val store =
                newTestStore(
                    queryCacheDao = queryCacheDao,
                    nowMillis = { NOW },
                    liveCacheKeysOf = { emptySet() },
                    onQueriesMarkedStale = { _, cacheKeys -> marked += cacheKeys },
                )
            queries.entries.forEachIndexed { position, (cacheKey, conditions) ->
                val shape = QueryShape("*", conditions, hasOtherFilters = false)
                store.queryCache.queryShapes.remember(TABLE, cacheKey, shape, persist = false)
                val answer = server.filter { row -> conditions.all { truth(row, it) } }
                queryCacheDao.seedPayload(
                    TABLE,
                    cacheKey,
                    JsonArray(answer).toString(),
                    NOW - position,
                )
            }
            val patch =
                JsonObject(
                    buildMap {
                        if (random.nextBoolean())
                            put("list", JsonPrimitive(LISTS[random.nextInt(3)]))
                        if (isEmpty() || random.nextBoolean())
                            put("status", JsonPrimitive(STATUSES[random.nextInt(2)]))
                        if (random.nextBoolean()) put("title", JsonPrimitive("u$seed"))
                    }
                )
            val filter = List(1 + random.nextInt(2)) { random.truthCondition() }

            store.cachePatcher.patchCachedQueries(
                TABLE,
                MutationOperation.UPDATE,
                patch.toString(),
                encodeFilter(FilterDescriptor(conditions = filter), EngineJson),
                liveCacheKeys = emptySet(),
            )

            val after = server.map { row ->
                if (filter.all { truth(row, it) }) JsonObject(row + patch) else row
            }
            queries.forEach { (cacheKey, conditions) ->
                val stored = checkNotNull(queryCacheDao.getRow(TABLE, cacheKey))
                if (cacheKey in marked || isStaleStamp(stored.updatedAt)) return@forEach
                fresh++
                val shown =
                    payloadRowsOf(EngineJson, stored.payload).orEmpty().sortedBy { row ->
                        (row as JsonObject).getValue("id").toString().toInt()
                    }
                val truth = after.filter { row -> conditions.all { truth(row, it) } }
                if (shown != truth) mismatches += "seed $seed $cacheKey: $shown, not $truth"
            }
        }
        assertEquals(emptyList(), mismatches.take(3))
        assertTrue(fresh > SCENARIOS, "too few queries left fresh: $fresh")
    }

    /** A condition the engine evaluates or not, as the server would apply it. */
    private fun Random.truthCondition(): FilterDescriptor.Condition =
        when (nextInt(5)) {
            0 -> FilterDescriptor.Condition("list", "eq", LISTS[nextInt(LISTS.size)])
            1 -> FilterDescriptor.Condition("status", "neq", "done")
            2 -> FilterDescriptor.Condition("id", "eq", nextInt(ROWS).toString())
            3 -> FilterDescriptor.Condition("position", "gt", nextInt(ROWS).toString())
            else -> FilterDescriptor.Condition("position", "eq", nextInt(ROWS).toString())
        }

    /** Whether the server would find [row] meets [condition]: `eq`, `neq` and `gt`. */
    private fun truth(row: JsonObject, condition: FilterDescriptor.Condition): Boolean {
        val value = (row.getValue(condition.column) as JsonPrimitive).content
        return when (condition.operatorId) {
            "eq" -> value == condition.value
            "neq" -> value != condition.value
            else -> value.toInt() > condition.value.toInt()
        }
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
        /** The queries of the ground-truth scenarios: every row is held by one of them. */
        val TRUTH_QUERIES: Map<String, List<FilterDescriptor.Condition>> =
            linkedMapOf(
                "list-a" to listOf(FilterDescriptor.Condition("list", "eq", "A")),
                "list-b" to listOf(FilterDescriptor.Condition("list", "eq", "B")),
                "done" to listOf(FilterDescriptor.Condition("status", "eq", "done")),
                "b-open" to
                    listOf(
                        FilterDescriptor.Condition("list", "eq", "B"),
                        FilterDescriptor.Condition("status", "eq", "open"),
                    ),
                "everything" to emptyList(),
            )
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
