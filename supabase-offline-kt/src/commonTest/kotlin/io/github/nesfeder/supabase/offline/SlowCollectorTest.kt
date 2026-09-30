// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A collector of a reactive read that takes its time is handed the latest list, not every list in
 * between, and never holds back another collector of the same query.
 */
class SlowCollectorTest {

    /** The `n` of the one row each shown list holds, in the order a collector was handed them. */
    private fun List<OfflineReadResult>.counts(): List<Int> = mapNotNull { read ->
        read.result
            ?.data
            ?.let { data -> Json.parseToJsonElement(data).jsonArray.firstOrNull() }
            ?.jsonObject
            ?.get("n")
            ?.jsonPrimitive
            ?.content
            ?.toInt()
    }

    private fun TestScope.newClient(): SupabaseOfflineClientImpl {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server =
            RecordingPostgrestServer(
                answer = { okJson("""[{"id":1,"n":0}]""") },
                engineDispatcher = dispatcher,
            )
        return newTestOfflineClient(
            supabase =
                newTestSupabaseClient(engine = server.engine) {
                    coroutineDispatcher = dispatcher
                    install(Postgrest)
                },
            store = newTestOfflineStore(),
            coordinatorScope = backgroundScope,
        )
    }

    @Test
    fun each_collector_ends_on_the_latest_list_and_the_slow_one_does_not_hold_back_the_other() =
        runTest {
            FlowReadStrategy.entries.forEach { strategy ->
                // Given — a fast and a slow collector of one query
                val client = newClient()
                client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
                val fast = mutableListOf<OfflineReadResult>()
                val slow = mutableListOf<OfflineReadResult>()
                val fastCollection = launch {
                    client.from(TABLE).selectAsFlow(readStrategy = strategy).collect { read ->
                        fast += read
                    }
                }
                val slowCollection = launch {
                    client.from(TABLE).selectAsFlow(readStrategy = strategy).collect { read ->
                        slow += read
                        delay(1.seconds)
                    }
                }
                runCurrent()

                // When — the list changes a hundred times while the slow one takes its first
                for (count in 1..CHANGES) {
                    client.applyServerRecord(
                        TABLE,
                        Json.parseToJsonElement("""{"id":1,"n":$count}""") as JsonObject,
                    )
                    runCurrent()
                }

                // Then — the fast one shows the latest at once
                assertEquals(CHANGES, fast.counts().last(), "$strategy fast ${fast.counts()}")

                // When — the slow one catches up
                advanceTimeBy(5.minutes)
                runCurrent()

                // Then — it ends on the latest too, handed a few lists in order, not every one
                val counts = slow.counts()
                assertEquals(CHANGES, counts.last(), "$strategy slow $counts")
                assertEquals(counts.sorted(), counts, "$strategy slow")
                assertTrue(counts.size < CHANGES / 10, "$strategy slow $counts")
                fastCollection.cancel()
                slowCollection.cancel()
                client.close()
            }
        }

    private companion object {
        const val TABLE = "items"
        const val CHANGES = 100
    }
}
