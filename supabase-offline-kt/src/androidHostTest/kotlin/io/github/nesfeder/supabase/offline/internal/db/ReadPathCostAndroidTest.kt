// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.management.OperatingSystemMXBean
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.internal.openAndroidClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toOkioPath
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Measures the read path on a real database: a cached list read against 0, 100 and 1,000 pending
 * mutations on its table, of each kind and mixed, the recompute 20 live readers do for one queued
 * write, a cold start that restores 50 cached queries, and reads and online writes of a table with
 * 50 cached lists that a write may move rows between, held to ceilings. Prints its figures; runs
 * only when `SUPABASE_OFFLINE_BENCHMARK` is set in the environment of the Gradle run.
 */
@RunWith(AndroidJUnit4::class)
class ReadPathCostAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory =
        File(context.cacheDir, "offline-read-cost-${Random.nextLong().toULong()}")

    /** Whether the server answers; off, every request fails to connect. */
    private val online = MutableStateFlow(true)

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online.value) connectivityFailure()
                val list = request.query["list_id"]?.removePrefix("eq.") ?: "L0"
                // A write that asks for no rows back gets none, as from PostgREST
                okJson(if (request.method == "GET") rowsOf(list) else "[]")
            }
        )

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun a_cached_list_read_against_its_tables_pending_mutations() =
        runTest(timeout = 20.minutes) {
            assumeBenchmark()
            withClient { client ->
                // Given — one cached list of 50 rows, read back ordered, as a list screen does
                val items = client.from(TABLE)
                items.select(readStrategy = ReadStrategy.NetworkOnly, request = listQuery("L0"))
                online.value = false

                var pending = 0
                for (target in listOf(0, 100, 1_000)) {
                    // When — the table gains pending mutations, half of them in the list read
                    while (pending < target) {
                        queueInsert(
                            items,
                            list = if (pending % 2 == 0) "L0" else "L1",
                            position = 1_000 + pending,
                        )
                        pending++
                    }
                    val oneShot = timeMillis {
                        items.select(
                            readStrategy = ReadStrategy.CacheOnly(),
                            request = listQuery("L0"),
                        )
                    }
                    val reactive = timeMillis {
                        items
                            .selectAsFlow(
                                readStrategy = FlowReadStrategy.CacheOnly,
                                request = listQuery("L0"),
                            )
                            .first()
                    }

                    // Then
                    report("cached-read one-shot pending=$pending", oneShot)
                    report("cached-read reactive-first pending=$pending", reactive)
                }
            }
        }

    /**
     * The kinds of pending mutation a cached read overlays: half of each reach the list read, half
     * do not.
     */
    private enum class PendingKind {
        INSERT,
        UPDATE,
        UPSERT,
        DELETE,
        MIXED,
    }

    @Test
    fun a_cached_list_read_against_each_kind_of_pending_mutation() =
        runTest(timeout = 60.minutes) {
            assumeBenchmark()
            for (kind in PendingKind.entries) {
                online.value = true
                withClient(File(directory, kind.name)) { client ->
                    // Given — one cached list of 50 rows, read back ordered, as a list screen does
                    val items = client.from(TABLE)
                    items.select(readStrategy = ReadStrategy.NetworkOnly, request = listQuery("L0"))
                    online.value = false

                    var pending = 0
                    for (target in listOf(0, 100, 1_000)) {
                        // When — the table gains pending mutations of this kind
                        while (pending < target) {
                            queue(kind, items, pending)
                            pending++
                        }
                        val oneShot = timeMillis {
                            items.select(
                                readStrategy = ReadStrategy.CacheOnly(),
                                request = listQuery("L0"),
                            )
                        }
                        val reactive = timeMillis {
                            items
                                .selectAsFlow(
                                    readStrategy = FlowReadStrategy.CacheOnly,
                                    request = listQuery("L0"),
                                )
                                .first()
                        }

                        // Then
                        report("cached-read one-shot kind=$kind pending=$pending", oneShot)
                        report("cached-read reactive-first kind=$kind pending=$pending", reactive)
                    }
                }
            }
        }

    /**
     * Queues the [index]th mutation of [kind]: even ones reach the list read, odd ones do not. An
     * update or delete of the list goes by id, one of the other by a text column no row holds.
     */
    private suspend fun queue(kind: PendingKind, items: OfflinePostgrestQueryBuilder, index: Int) {
        val position = index % 50
        val reaches = index % 2 == 0
        when (if (kind == PendingKind.MIXED) PendingKind.entries[index % 4] else kind) {
            PendingKind.INSERT ->
                queueInsert(items, list = if (reaches) "L0" else "L1", position = 1_000 + index)
            PendingKind.UPDATE ->
                items.update(buildJsonObject { put("title", "edit $index") }) {
                    if (reaches) filter { eq("id", "L0-$position") }
                    else filter { eq("owner", "someone-else") }
                }
            PendingKind.UPSERT ->
                items.upsert(
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", if (reaches) "L0-$position" else "L1-new-$index")
                                put("list_id", if (reaches) "L0" else "L1")
                                put("position", position)
                                put("title", "upserted $index")
                            }
                        )
                    }
                )
            PendingKind.DELETE ->
                items.delete {
                    if (reaches) filter { eq("id", "L0-$position") }
                    else filter { eq("owner", "someone-else") }
                }
            PendingKind.MIXED -> error("a mixed run queues one of the other kinds")
        }
    }

    @Test
    fun twenty_live_readers_recompute_for_one_queued_write() =
        runTest(timeout = 20.minutes) {
            assumeBenchmark()
            twentyLiveReadersRecompute(label = "recompute 20-readers", followsSyncStatus = false)
        }

    @Test
    fun twenty_live_readers_and_a_sync_status_collector_for_one_queued_write() =
        runTest(timeout = 20.minutes) {
            assumeBenchmark()
            twentyLiveReadersRecompute(
                label = "recompute 20-readers+status",
                followsSyncStatus = true,
            )
        }

    /**
     * One queued write at a time against 20 live readers and 100 pending mutations, the write's
     * time to its reader's emission and the process CPU it costs reported under [label]; with
     * [followsSyncStatus], a collector of `syncStatus` runs too, and each write waits for its
     * count.
     */
    private suspend fun twentyLiveReadersRecompute(label: String, followsSyncStatus: Boolean) {
        withClient { client ->
            // Given — 20 cached lists observed by 20 live readers, and 100 mutations already
            // pending
            val items = client.from(TABLE)
            val lists = (0 until READERS).map { index -> "L$index" }
            lists.forEach { list ->
                items.select(readStrategy = ReadStrategy.NetworkOnly, request = listQuery(list))
            }
            online.value = false
            repeat(100) { index ->
                queueInsert(items, list = lists[index % READERS], position = 2_000 + index)
            }
            val emissions = MutableStateFlow(List(READERS) { 0 })
            coroutineScope {
                val readers: List<Job> = lists.mapIndexed { index, list ->
                    launch(Dispatchers.Default) {
                        items
                            .selectAsFlow(
                                readStrategy = FlowReadStrategy.CacheOnly,
                                request = listQuery(list),
                            )
                            .collect {
                                emissions.update { counts ->
                                    counts.toMutableList().also { copy -> copy[index] += 1 }
                                }
                            }
                    }
                }
                emissions.first { counts -> counts.all { count -> count >= 1 } }
                val statuses = MutableStateFlow(0)
                val statusCollector =
                    if (!followsSyncStatus) null
                    else
                        launch(Dispatchers.Default) {
                            client.syncStatus.collect { status ->
                                statuses.value = status.pendingMutationCount
                            }
                        }
                delay(SETTLE)

                // When — one write at a time, each in one of the lists
                val latencies = mutableListOf<Double>()
                val cpu = mutableListOf<Double>()
                repeat(WARMUP + SAMPLES) { round ->
                    val reader = round % READERS
                    val before = emissions.value[reader]
                    val cpuBefore = processCpuNanos()
                    val started = System.nanoTime()
                    queueInsert(items, list = lists[reader], position = 3_000 + round)
                    withContext(Dispatchers.Default) {
                        withTimeout(30.seconds) {
                            emissions.first { counts -> counts[reader] > before }
                            if (followsSyncStatus)
                                statuses.first { count -> count == 100 + round + 1 }
                        }
                    }
                    val seen = System.nanoTime()
                    withContext(Dispatchers.Default) { delay(SETTLE) }
                    if (round >= WARMUP) {
                        latencies += (seen - started) / 1e6
                        cpu += (processCpuNanos() - cpuBefore) / 1e6
                    }
                }
                readers.forEach { reader -> reader.cancelAndJoin() }
                statusCollector?.cancelAndJoin()

                // Then
                report("$label write-to-emission", latencies)
                report("$label process-cpu-per-write", cpu)
            }
        }
    }

    @Test
    fun a_cold_start_restores_fifty_cached_queries() =
        runTest(timeout = 20.minutes) {
            assumeBenchmark()
            // Given — 50 cached lists and 20 pending mutations, left by a client that has closed
            val lists = (0 until COLD_QUERIES).map { index -> "L$index" }
            withClient { client ->
                val items = client.from(TABLE)
                lists.forEach { list ->
                    items.select(readStrategy = ReadStrategy.NetworkOnly, request = listQuery(list))
                }
                online.value = false
                repeat(20) { index ->
                    queueInsert(items, list = lists[index], position = 4_000 + index)
                }
            }

            // When — a new client opens and 50 readers each take their first emission
            val samples = mutableListOf<Double>()
            repeat(COLD_STARTS) {
                val started = System.nanoTime()
                withClient { client ->
                    val items = client.from(TABLE)
                    coroutineScope {
                        lists
                            .map { list ->
                                launch(Dispatchers.Default) {
                                    items
                                        .selectAsFlow(
                                            readStrategy = FlowReadStrategy.CacheOnly,
                                            request = listQuery(list),
                                        )
                                        .first { read -> read.result != null }
                                }
                            }
                            .forEach { reader -> reader.join() }
                    }
                    samples += (System.nanoTime() - started) / 1e6
                }
            }

            // Then
            report("cold-start 50-queries open-to-all-first-emissions", samples)
        }

    @Test
    fun fifty_cached_lists_of_one_table_against_a_pending_upsert_and_an_online_write() =
        runTest(timeout = 30.minutes) {
            assumeBenchmark()
            withClient { client ->
                // Given — 50 lists of 50 rows of one table cached, as a screen of tabs leaves them
                val items = client.from(TABLE)
                (0 until BORROW_LISTS).forEach { index ->
                    items.select(
                        readStrategy = ReadStrategy.NetworkOnly,
                        request = listQuery("L$index"),
                    )
                }

                // When — online, a write reaches one of them
                var round = 0
                val upsert = timeMillis {
                    round++
                    items.upsert(buildJsonArray { add(rowOf("L0", round % 50, "Online $round")) })
                }
                val update = timeMillis {
                    round++
                    items.update(buildJsonObject { put("title", "Online $round") }) {
                        filter { eq("id", "L0-${round % 50}") }
                    }
                }
                // and, offline, one upsert waits in the queue: first of a row the list holds, then
                // of a row another list holds that the upsert moves into it
                online.value = false
                items.upsert(buildJsonArray { add(rowOf("L0", 0, "Queued")) })
                val oneShotHeld = timeMillis {
                    items.select(readStrategy = ReadStrategy.CacheOnly(), request = listQuery("L0"))
                }
                val reactiveHeld = timeMillis { firstEmission(items, "L0") }
                items.upsert(buildJsonArray { add(rowOf("L0", 1, "Moved").withId("L1-1")) })
                val oneShotMoved = timeMillis {
                    items.select(readStrategy = ReadStrategy.CacheOnly(), request = listQuery("L0"))
                }
                val reactiveMoved = timeMillis { firstEmission(items, "L0") }

                // Then — each within a small multiple of what the base measured on this machine
                report("fifty-lists online-upsert", upsert)
                report("fifty-lists online-update", update)
                report("fifty-lists cached-read one-shot upsert-of-a-row-it-holds", oneShotHeld)
                report(
                    "fifty-lists cached-read reactive-first upsert-of-a-row-it-holds",
                    reactiveHeld,
                )
                report("fifty-lists cached-read one-shot upsert-moving-a-row-in", oneShotMoved)
                report(
                    "fifty-lists cached-read reactive-first upsert-moving-a-row-in",
                    reactiveMoved,
                )
                assertMedianAtMost(upsert, ONLINE_WRITE_CEILING_MS, "online upsert")
                assertMedianAtMost(update, ONLINE_WRITE_CEILING_MS, "online update")
                assertMedianAtMost(oneShotHeld, CACHED_READ_CEILING_MS, "cached read, row held")
                assertMedianAtMost(reactiveHeld, REACTIVE_READ_CEILING_MS, "reactive, row held")
                assertMedianAtMost(oneShotMoved, CACHED_READ_CEILING_MS, "cached read, row moved")
                assertMedianAtMost(reactiveMoved, REACTIVE_READ_CEILING_MS, "reactive, row moved")
            }
        }

    @Test
    fun fifty_cached_lists_of_one_table_against_updates_the_engine_cannot_evaluate() =
        runTest(timeout = 30.minutes) {
            assumeBenchmark()
            withClient { client ->
                // Given — 50 lists of 50 rows of one table cached
                val items = client.from(TABLE)
                (0 until BORROW_LISTS).forEach { index ->
                    items.select(
                        readStrategy = ReadStrategy.NetworkOnly,
                        request = listQuery("L$index"),
                    )
                }

                // When — online, an update by a comparison the engine does not evaluate: of a
                // column no list filters on, then of the column every list filters on
                var round = 0
                val retitle = timeMillis {
                    round++
                    items.update(buildJsonObject { put("title", "Online $round") }) {
                        filter { gt("position", 45) }
                    }
                }
                val move = timeMillis {
                    items.update(buildJsonObject { put("list_id", "L0") }) {
                        filter { gt("position", 48) }
                    }
                }
                // and, offline, one such update of the list column waits in the queue
                online.value = false
                items.update(buildJsonObject { put("list_id", "L0") }) {
                    filter { gt("position", 48) }
                }
                val oneShot = timeMillis {
                    items.select(readStrategy = ReadStrategy.CacheOnly(), request = listQuery("L0"))
                }
                val reactive = timeMillis { firstEmission(items, "L0") }

                // Then — each within a small multiple of what the base measured on this machine
                report("fifty-lists online-update unevaluated-filter other-column", retitle)
                report("fifty-lists online-update unevaluated-filter list-column", move)
                report("fifty-lists cached-read one-shot pending-unevaluated-move", oneShot)
                report("fifty-lists cached-read reactive-first pending-unevaluated-move", reactive)
                assertMedianAtMost(retitle, UNEVALUATED_WRITE_CEILING_MS, "online, other column")
                assertMedianAtMost(move, UNEVALUATED_WRITE_CEILING_MS, "online, list column")
                assertMedianAtMost(oneShot, CACHED_READ_CEILING_MS, "cached read, pending move")
                assertMedianAtMost(reactive, REACTIVE_READ_CEILING_MS, "reactive, pending move")
            }
        }

    private fun assumeBenchmark() {
        assumeTrue(
            "set SUPABASE_OFFLINE_BENCHMARK to run",
            System.getenv("SUPABASE_OFFLINE_BENCHMARK") != null,
        )
    }

    private suspend fun withClient(
        storage: File = directory,
        block: suspend (Offline) -> Unit,
    ) {
        val client =
            openAndroidClient(
                context = context,
                supabase = server.supabaseClient(),
                config = Offline.createConfig { dataDirectory = storage.toOkioPath().toString() },
            )
        try {
            block(client)
        } finally {
            client.close()
        }
    }

    private fun listQuery(list: String): SelectRequestBuilder.() -> Unit = {
        filter { eq("list_id", list) }
        order("position", Order.ASCENDING)
    }

    private suspend fun queueInsert(
        items: OfflinePostgrestQueryBuilder,
        list: String,
        position: Int,
    ) {
        items.insert(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", "local-$list-$position")
                        put("list_id", list)
                        put("position", position)
                        put("title", "Queued item $position")
                    }
                )
            }
        )
    }

    private suspend fun timeMillis(read: suspend () -> Unit): List<Double> {
        repeat(WARMUP) { read() }
        return List(SAMPLES) {
            val started = System.nanoTime()
            read()
            (System.nanoTime() - started) / 1e6
        }
    }

    private fun report(label: String, samples: List<Double>) {
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[(sorted.size * 9) / 10]
        println(
            "READ_PATH_COST\t$label\tmedian_ms=${format(median)}\tp90_ms=${format(p90)}\tsamples=${samples.size}"
        )
    }

    /** Fails when the median of [samples] is over [ceilingMillis]. */
    private fun assertMedianAtMost(samples: List<Double>, ceilingMillis: Double, what: String) {
        val median = samples.sorted()[samples.size / 2]
        assertTrue(median <= ceilingMillis, "$what: median $median ms, over $ceilingMillis ms")
    }

    /** The first emission of a reactive cache read of [list]. */
    private suspend fun firstEmission(items: OfflinePostgrestQueryBuilder, list: String) {
        items
            .selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly, request = listQuery(list))
            .first()
    }

    /** This row with its `id` set to [id]. */
    private fun JsonObject.withId(id: String): JsonObject =
        JsonObject(this + ("id" to JsonPrimitive(id)))

    /** Row [position] of [list], titled [title]. */
    private fun rowOf(list: String, position: Int, title: String) = buildJsonObject {
        put("id", "$list-$position")
        put("list_id", list)
        put("position", position)
        put("title", title)
        put("owner", "3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f")
        put("done", position % 3 == 0)
    }

    private fun format(value: Double): String = ((value * 100).toLong() / 100.0).toString()

    private fun processCpuNanos(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).processCpuTime

    private companion object {
        const val TABLE = "items"
        const val READERS = 20
        const val COLD_QUERIES = 50
        const val COLD_STARTS = 7
        const val BORROW_LISTS = 50

        /**
         * Ceilings for 50 cached lists of one table, each a small multiple of the highest median
         * the base measured on the host: a cached read 0.21 ms one-shot and 0.60 ms reactive, an
         * online write by key 11.6 ms and one the engine cannot evaluate 8.0 ms.
         */
        const val CACHED_READ_CEILING_MS = 1.5
        const val REACTIVE_READ_CEILING_MS = 3.5
        const val ONLINE_WRITE_CEILING_MS = 70.0
        const val UNEVALUATED_WRITE_CEILING_MS = 35.0
        const val WARMUP = 10
        const val SAMPLES = 50
        val SETTLE = 50.milliseconds

        /** 50 rows of [list], ordered by position. */
        fun rowsOf(list: String): String =
            JsonArray(
                    (0 until 50).map { position ->
                        buildJsonObject {
                            put("id", "$list-$position")
                            put("list_id", list)
                            put("position", position)
                            put("title", "Item $position of $list")
                            put("owner", "3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f")
                            put("done", position % 3 == 0)
                        }
                    }
                )
                .toString()
    }
}
