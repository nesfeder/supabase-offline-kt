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
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig
import io.github.nesfeder.supabase.offline.createSupabaseOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toOkioPath
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Measures the read path on a real database: a cached list read against 0, 100 and 1,000 pending
 * mutations on its table, of each kind and mixed, the recompute 20 live readers do for one queued
 * write, and a cold start that restores 50 cached queries. Prints its figures; runs only when
 * `SUPABASE_OFFLINE_BENCHMARK` is set in the environment of the Gradle run.
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
                okJson(rowsOf(list))
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
                items.updateJson(buildJsonObject { put("title", "edit $index") }) {
                    if (reaches) filter { eq("id", "L0-$position") }
                    else filter { eq("owner", "someone-else") }
                }
            PendingKind.UPSERT ->
                items.upsertJson(
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

                    // Then
                    report("recompute 20-readers write-to-emission", latencies)
                    report("recompute 20-readers process-cpu-per-write", cpu)
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

    private fun assumeBenchmark() {
        assumeTrue(
            "set SUPABASE_OFFLINE_BENCHMARK to run",
            System.getenv("SUPABASE_OFFLINE_BENCHMARK") != null,
        )
    }

    private suspend fun withClient(
        storage: File = directory,
        block: suspend (SupabaseOfflineClient) -> Unit,
    ) {
        val client =
            createSupabaseOfflineClient(
                context = context,
                supabase = server.supabaseClient(),
                config = SupabaseOfflineConfig(offlineStoragePath = storage.toOkioPath()),
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
        items.insertJson(
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

    private fun format(value: Double): String = ((value * 100).toLong() / 100.0).toString()

    private fun processCpuNanos(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).processCpuTime

    private companion object {
        const val TABLE = "items"
        const val READERS = 20
        const val COLD_QUERIES = 50
        const val COLD_STARTS = 7
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
