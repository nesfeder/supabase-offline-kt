// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Size-based eviction never removes a payload a live reader shows: it takes the oldest payload
 * nobody observes instead, and the reader never drops to "no data".
 */
class LiveEvictionTest {

    /**
     * Runs one reader of list A under [strategy] while a large read pushes the cache over its
     * limit.
     */
    private fun TestScope.evictWhileListAIsShown(
        strategy: FlowReadStrategy
    ): Pair<List<String>, List<String?>> {
        var clock = 0L
        val queryCacheDao = FakeQueryCacheDao()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server =
            RecordingPostgrestServer(
                answer = { request ->
                    when (request.query["list_id"]) {
                        "eq.A" -> okJson(LIST_A)
                        "eq.B" -> okJson(LIST_B)
                        else -> okJson(LARGE_LIST)
                    }
                },
                engineDispatcher = dispatcher,
            )
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val items =
            newTestOfflineClient(
                    supabase =
                        newTestSupabaseClient(engine = server.engine) {
                            install(Postgrest) { maxRetries = 0 }
                        },
                    store =
                        newTestOfflineStore(
                            queryCacheDao = queryCacheDao,
                            // The large list and list A fit together; one more list does not
                            queryCacheLimits =
                                QueryCacheLimits(
                                    maxPayloadBytes =
                                        (LARGE_LIST.length + LIST_A.length + 1).toLong(),
                                    evictionBatchSize = 1,
                                    drainHeadroomBytes = 0,
                                ),
                            nowMillis = { clock },
                        ),
                    coordinatorScope = scope,
                )
                .from(TABLE)
        val shown = mutableListOf<String?>()

        // List A cached first, so it is the oldest, and shown; then list B; then the large list
        scope.launch {
            clock = 1
            items.select(readStrategy = ReadStrategy.NetworkOnly) { filter { eq("list_id", "A") } }
            launch {
                items
                    .selectAsFlow(readStrategy = strategy) { filter { eq("list_id", "A") } }
                    .collect { read -> shown += read.result?.data }
            }
        }
        advanceUntilIdle()
        scope.launch {
            clock = 2
            items.select(readStrategy = ReadStrategy.NetworkOnly) { filter { eq("list_id", "B") } }
            clock = 3
            items.select(readStrategy = ReadStrategy.NetworkOnly) {
                filter { eq("list_id", "large") }
            }
        }
        advanceUntilIdle()
        scope.cancel()
        return queryCacheDao.rowsOf(TABLE).map { row -> row.payload } to shown
    }

    @Test
    fun eviction_keeps_the_oldest_payload_while_a_reader_shows_it() = runTest {
        FlowReadStrategy.entries.forEach { strategy ->
            // Given / When
            val (cached, shown) = evictWhileListAIsShown(strategy)

            // Then — B, which nobody observes, went; A stayed, and its reader never lost it
            assertTrue(LIST_A in cached, "$strategy: the observed list was evicted")
            assertTrue(LIST_B !in cached, "$strategy: the unobserved list was kept")
            assertEquals(listOf(LIST_A), shown.distinct(), "$strategy: the reader showed $shown")
        }
    }

    private companion object {
        const val TABLE = "items"
        const val LIST_A = """[{"id":1,"list_id":"A"}]"""
        const val LIST_B = """[{"id":2,"list_id":"B"}]"""
        val LARGE_LIST = """[{"id":3,"list_id":"large","note":"${"x".repeat(5_000)}"}]"""
    }
}
