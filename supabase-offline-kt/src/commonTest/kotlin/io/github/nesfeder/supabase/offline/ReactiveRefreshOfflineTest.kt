// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Offline, a `CacheFirstThenNetwork` read's refresh says `Stalled` once its own fast ladder is
 * spent — four attempts, 3.6 s — because each attempt fails at once: supabase-kt's retries of a
 * transport failure are skipped, since the refresh retries on its own schedule.
 */
class ReactiveRefreshOfflineTest {

    @Test
    fun offline_the_refresh_says_stalled_once_its_fast_ladder_is_spent() = runTest {
        listOf(true, false).forEach { cached ->
            // Given — no network, supabase-kt's default retries, the list cached or not
            val queryCacheDao = FakeQueryCacheDao()
            if (cached) queryCacheDao.seedPayload(TABLE, TABLE, ROWS, updatedAt = 1)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val server =
                RecordingPostgrestServer(
                    answer = { connectivityFailure() },
                    engineDispatcher = dispatcher,
                )
            val items =
                newTestOfflineClient(
                        supabase =
                            newTestSupabaseClient(engine = server.engine) {
                                coroutineDispatcher = dispatcher
                                install(Postgrest)
                            },
                        store = newTestOfflineStore(queryCacheDao = queryCacheDao),
                        coordinatorScope = backgroundScope,
                    )
                    .from(TABLE)
            val shown = mutableListOf<OfflineReadResult>()

            // When
            val collection = launch {
                items.selectAsFlow(readStrategy = FlowReadStrategy.CacheFirstThenNetwork).collect {
                    read ->
                    shown += read
                }
            }
            advanceTimeBy(LADDER_SPENT_AFTER)
            runCurrent()

            // Then — stalled, after one request per attempt
            assertTrue(shown.last().refresh is RefreshState.Stalled, "cached=$cached $shown")
            assertEquals(4, server.requests.size, "cached=$cached")
            assertEquals(if (cached) ROWS else null, shown.last().result?.data)
            collection.cancel()
        }
    }

    private companion object {
        const val TABLE = "items"
        const val ROWS = """[{"id":1}]"""

        /** The fast ladder's waits, 300 + 900 + 2,400 ms, and a margin. */
        val LADDER_SPENT_AFTER = 3_700.milliseconds
    }
}
