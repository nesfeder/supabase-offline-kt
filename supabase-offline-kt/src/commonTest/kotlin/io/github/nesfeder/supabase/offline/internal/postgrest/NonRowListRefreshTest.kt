// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
 * A reactive read's refresh answered with something other than a list of rows (a captive portal's
 * sign-in page at `200`, a self-hosted server's fallback page) keeps the cached rows, stays
 * `Refreshing` and asks again, until an answer is a list of rows.
 */
class NonRowListRefreshTest {

    @Test
    fun a_refresh_answered_with_a_page_keeps_the_cached_rows_and_asks_again() = runTest {
        // Given — the list cached, and a server answering a sign-in page until it is let through
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload(TABLE, TABLE, ROWS, updatedAt = 1)
        val dispatcher = StandardTestDispatcher(testScheduler)
        var portalOpen = false
        val server =
            RecordingPostgrestServer(
                answer = {
                    if (portalOpen) okJson(NEW_ROWS)
                    else
                        respond(
                            content = "<!doctype html><html><body>Sign in</body></html>",
                            status = HttpStatusCode.OK,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType,
                                    ContentType.Text.Html.toString(),
                                ),
                        )
                },
                engineDispatcher = dispatcher,
            )
        val items =
            newTestOfflineClient(
                    supabase =
                        newTestSupabaseClient(engine = server.engine) {
                            coroutineDispatcher = dispatcher
                            install(Postgrest) { maxRetries = 0 }
                        },
                    queryCacheDao = queryCacheDao,
                    coordinatorScope = backgroundScope,
                )
                .from(TABLE)
        val shown = mutableListOf<QuerySnapshot>()
        val collection = launch {
            items.selectAsFlow(readStrategy = FlowReadStrategy.CacheFirstThenNetwork).collect { read
                ->
                shown += read
            }
        }

        // When — two attempts get the page
        advanceTimeBy(FIRST_RETRY_WAIT)
        runCurrent()

        // Then — the cached rows are shown, nothing of the page was stored, and the refresh is
        // still under way, not stalled: an answer came, just not the server's rows
        assertEquals(ROWS, queryCacheDao.getRow(TABLE, TABLE)?.payload)
        assertEquals(ROWS, shown.last().result?.data)
        assertEquals(RefreshState.Refreshing, shown.last().refresh, "$shown")
        assertTrue(server.requests.size >= 2, "asked ${server.requests.size} times")

        // When — the portal lets requests through
        portalOpen = true
        advanceTimeBy(LADDER_SPENT_AFTER)
        runCurrent()

        // Then — the server's rows, stored and shown, and the refresh idle
        assertEquals(NEW_ROWS, queryCacheDao.getRow(TABLE, TABLE)?.payload)
        assertEquals(NEW_ROWS, shown.last().result?.data)
        assertEquals(RefreshState.Idle, shown.last().refresh, "$shown")
        collection.cancel()
    }

    private companion object {
        const val TABLE = "items"
        const val ROWS = """[{"id":1}]"""
        const val NEW_ROWS = """[{"id":1},{"id":2}]"""

        /** The first retry's wait, 300 ms, and a margin. */
        val FIRST_RETRY_WAIT = 400.milliseconds

        /** The fast ladder's waits, 300 + 900 + 2,400 ms, and a margin. */
        val LADDER_SPENT_AFTER = 3_700.milliseconds
    }
}
