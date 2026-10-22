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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A reactive read's refresh answered with something other than a list of rows (a captive portal's
 * sign-in page at `200`, a self-hosted server's fallback page) keeps the cached rows and asks
 * again: `Refreshing` through the fast retries, `Stalled` by that cause after them, and `Idle` once
 * an answer is a list of rows.
 */
class NonRowListRefreshTest {

    /** A live read of the cached list against a server answering a page until [portalOpen]. */
    private class Portal(
        val server: RecordingPostgrestServer,
        val queryCacheDao: FakeQueryCacheDao,
        val shown: MutableList<QuerySnapshot>,
    ) {
        var portalOpen = false
    }

    private fun TestScope.readThroughAPortal(): Portal {
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload(TABLE, TABLE, ROWS, updatedAt = 1)
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var portal: Portal
        val server =
            RecordingPostgrestServer(
                answer = {
                    if (portal.portalOpen) okJson(NEW_ROWS)
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
        portal = Portal(server, queryCacheDao, shown)
        backgroundScope.launch {
            items.selectAsFlow(readStrategy = FlowReadStrategy.CacheFirstThenNetwork).collect { read
                ->
                shown += read
            }
        }
        return portal
    }

    @Test
    fun a_refresh_answered_with_a_page_keeps_the_cached_rows_and_asks_again() = runTest {
        // Given — the list cached, and a server answering a sign-in page until it is let through
        val portal = readThroughAPortal()

        // When — two attempts get the page
        advanceTimeBy(FIRST_RETRY_WAIT)
        runCurrent()

        // Then — the cached rows are shown, nothing of the page was stored, and the refresh is
        // still under way, not yet stalled
        assertEquals(ROWS, portal.queryCacheDao.getRow(TABLE, TABLE)?.payload)
        assertEquals(ROWS, portal.shown.last().result?.data)
        assertEquals(RefreshState.Refreshing, portal.shown.last().refresh, "${portal.shown}")
        assertTrue(portal.server.requests.size >= 2, "asked ${portal.server.requests.size} times")

        // When — the portal lets requests through
        portal.portalOpen = true
        advanceTimeBy(LADDER_SPENT_AFTER)
        runCurrent()

        // Then — the server's rows, stored and shown, and the refresh idle
        assertEquals(NEW_ROWS, portal.queryCacheDao.getRow(TABLE, TABLE)?.payload)
        assertEquals(NEW_ROWS, portal.shown.last().result?.data)
        assertEquals(RefreshState.Idle, portal.shown.last().refresh, "${portal.shown}")
    }

    @Test
    fun a_server_that_only_answers_pages_stalls_the_refresh_after_the_fast_retries() = runTest {
        // Given — a server that never lets a request through
        val portal = readThroughAPortal()

        // When — the fast retries are spent
        advanceTimeBy(LADDER_SPENT_AFTER)
        runCurrent()

        // Then — the refresh keeps failing and says so, with its cause; the cached rows still show
        val refresh = portal.shown.last().refresh
        assertTrue(refresh is RefreshState.Stalled, "$refresh")
        assertTrue(refresh.cause is NotARowListException, "${refresh.cause}")
        assertEquals(ROWS, portal.shown.last().result?.data)
        assertEquals(4, portal.server.requests.size)
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
