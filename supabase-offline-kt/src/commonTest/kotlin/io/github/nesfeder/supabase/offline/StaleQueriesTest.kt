// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * An online write the cache cannot patch exactly marks stale only the cached queries it may have
 * reached — table-wide only when it cannot tell — and has their live readers fetch again. Stale
 * means "fetch again when you can": online, a read with a cache duration asks the server; offline,
 * the stale rows are still served, across a restart too.
 */
class StaleQueriesTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()

    /** Whether the server can be reached. */
    private var online = true

    /** The server's rows of each list, as a read of that list answers. */
    private val lists =
        mutableMapOf(
            "A" to
                """[{"id":"a1","list_id":"A","priority":5,"done":false},{"id":"a2","list_id":"A","priority":1,"done":false}]""",
            "Z" to """[{"id":"z1","list_id":"Z","priority":9,"done":false}]""",
        )

    /** The server every launch reads, on the test's scheduler; see [launchApp]. */
    private var serverOrNull: RecordingPostgrestServer? = null

    private val server: RecordingPostgrestServer
        get() = checkNotNull(serverOrNull)

    /**
     * A client over this test's database, as each launch of the app builds one; its requests and
     * supabase-kt's own work run on the test's scheduler.
     */
    private fun TestScope.launchApp(
        scope: CoroutineScope = idleCoroutineScope()
    ): OfflinePostgrestQueryBuilder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recording =
            serverOrNull
                ?: RecordingPostgrestServer(
                        answer = { request ->
                            if (!online) connectivityFailure()
                            when (request.method) {
                                "GET" ->
                                    okJson(
                                        lists[request.query["list_id"]?.removePrefix("eq.")] ?: "[]"
                                    )
                                else -> okJson("")
                            }
                        },
                        engineDispatcher = dispatcher,
                    )
                    .also { created -> serverOrNull = created }
        return newTestOfflineClient(
                supabase =
                    newTestSupabaseClient(engine = recording.engine) {
                        coroutineDispatcher = dispatcher
                        install(Postgrest) { maxRetries = 0 }
                    },
                store =
                    newTestOfflineStore(
                        queryCacheDao = queryCacheDao,
                        mutationDao = mutationDao,
                        nowMillis = { NOW },
                    ),
                coordinatorScope = scope,
            )
            .from(TABLE)
    }

    private fun list(name: String): SelectRequestBuilder.() -> Unit = {
        filter { eq("list_id", name) }
    }

    private fun readsOf(list: String): Int =
        server.requests.count { request ->
            request.method == "GET" && request.query["list_id"] == "eq.$list"
        }

    /** Whether a read with an hour's cache duration of [list] asked the server. */
    private suspend fun OfflinePostgrestQueryBuilder.askedTheServer(list: String): Boolean {
        val before = readsOf(list)
        select(
            readStrategy = ReadStrategy.CacheFirst(cacheDuration = 1.hours),
            request = list(list),
        )
        return readsOf(list) > before
    }

    private suspend fun OfflinePostgrestQueryBuilder.cacheBoth() {
        select(readStrategy = ReadStrategy.NetworkOnly, request = list("A"))
        select(readStrategy = ReadStrategy.NetworkOnly, request = list("Z"))
    }

    private suspend fun OfflinePostgrestQueryBuilder.deleteThroughAGroup() {
        delete {
            filter {
                eq("list_id", "A")
                or {
                    eq("done", true)
                    eq("archived", true)
                }
            }
        }
    }

    @Test
    fun a_write_through_a_group_marks_only_the_lists_it_may_reach() = runTest {
        // Given — lists A and Z cached
        val items = launchApp()
        items.cacheBoth()

        // When — a delete the cache cannot evaluate, within list A
        items.deleteThroughAGroup()

        // Then — A is fetched again; Z, which no row of the delete can be in, is not
        assertEquals(true, items.askedTheServer("A"))
        assertEquals(false, items.askedTheServer("Z"))
    }

    @Test
    fun a_stale_list_is_still_served_offline_by_every_strategy() = runTest {
        // Given — list A marked stale by a write the cache could not patch
        val items = launchApp()
        items.cacheBoth()
        items.deleteThroughAGroup()

        // When — offline
        online = false

        // Then — served, not thrown
        val rows = lists.getValue("A")
        assertEquals(
            rows,
            items
                .select(
                    readStrategy = ReadStrategy.CacheFirst(cacheDuration = 1.hours),
                    request = list("A"),
                )
                .data,
        )
        assertEquals(
            rows,
            items
                .select(
                    readStrategy = ReadStrategy.NetworkFirst(cacheDuration = 1.hours),
                    request = list("A"),
                )
                .data,
        )
        assertEquals(
            rows,
            items
                .select(
                    readStrategy = ReadStrategy.CacheOnly(cacheDuration = 1.hours),
                    request = list("A"),
                )
                .data,
        )
    }

    @Test
    fun the_mark_survives_a_restart_and_clears_once_the_list_is_fetched() = runTest {
        // Given — list A marked stale, then the app restarts offline
        val first = launchApp()
        first.cacheBoth()
        first.deleteThroughAGroup()
        online = false
        val offlineLaunch = launchApp()

        // Then — offline, the stale rows are served
        assertEquals(
            lists.getValue("A"),
            offlineLaunch
                .select(
                    readStrategy = ReadStrategy.CacheFirst(cacheDuration = 1.hours),
                    request = list("A"),
                )
                .data,
        )

        // When — the next launch is online
        online = true
        val onlineLaunch = launchApp()

        // Then — it asks the server once, and the fetched list is fresh again
        assertEquals(true, onlineLaunch.askedTheServer("A"))
        assertEquals(false, onlineLaunch.askedTheServer("A"))
        assertEquals(false, onlineLaunch.askedTheServer("Z"))
    }

    @Test
    fun an_online_update_whose_filter_the_cache_cannot_evaluate_marks_the_lists_it_may_reach() =
        runTest {
            // Given — lists A and Z cached
            val items = launchApp()
            items.cacheBoth()

            // When — an update the server applies by a comparison the cache does not evaluate
            items.updateJson(Json.parseToJsonElement("""{"done":true}""") as JsonObject) {
                filter {
                    eq("list_id", "A")
                    gt("priority", 3)
                }
            }

            // Then — A, whose rows it may have changed, is fetched again; Z is not
            assertEquals(true, items.askedTheServer("A"))
            assertEquals(false, items.askedTheServer("Z"))
        }

    @Test
    fun an_online_delete_whose_filter_the_cache_cannot_evaluate_marks_the_lists_it_may_reach() =
        runTest {
            // Given — lists A and Z cached
            val items = launchApp()
            items.cacheBoth()

            // When
            items.delete { filter { lt("priority", 3) } }

            // Then — every list holding a row it may have deleted is fetched again
            assertEquals(true, items.askedTheServer("A"))
            assertEquals(true, items.askedTheServer("Z"))
        }

    @Test
    fun a_live_reader_of_a_list_the_write_may_reach_fetches_again_once_and_no_other() = runTest {
        // Given — lists A and Z observed live, their first refresh done
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val items = launchApp(scope)
        listOf("A", "Z").forEach { name ->
            scope.launch { items.selectAsFlow(request = list(name)).collect {} }
        }
        advanceTimeBy(1.minutes)
        runCurrent()
        val readsBefore = readsOf("A") to readsOf("Z")

        // When — an update the cache cannot evaluate, within list A
        items.updateJson(Json.parseToJsonElement("""{"done":true}""") as JsonObject) {
            filter {
                eq("list_id", "A")
                gt("priority", 3)
            }
        }
        advanceTimeBy(1.minutes)
        runCurrent()
        scope.cancel()

        // Then — A's reader fetched once more; Z's did not
        assertEquals(readsBefore.first + 1, readsOf("A"))
        assertEquals(readsBefore.second, readsOf("Z"))
    }

    @Test
    fun a_stale_list_read_live_offline_shows_its_rows_and_says_the_refresh_has_not_landed() =
        runTest {
            // Given — list A marked stale, then offline
            val first = launchApp()
            first.cacheBoth()
            first.deleteThroughAGroup()
            online = false

            // When — a live read of it
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val items = launchApp(scope)
            var last: OfflineReadResult? = null
            scope.launch { items.selectAsFlow(request = list("A")).collect { read -> last = read } }
            advanceTimeBy(1.minutes)
            runCurrent()
            scope.cancel()

            // Then — the rows, and a refresh state that is not idle
            assertEquals(lists.getValue("A"), last?.result?.data)
            assertEquals(true, last?.refresh is RefreshState.Stalled, "${last?.refresh}")
        }

    private companion object {
        const val TABLE = "items"

        /**
         * The store's clock: every write is stamped now, so an hour's cache duration keeps it
         * fresh.
         */
        val NOW: Long = Clock.System.now().toEpochMilliseconds()
    }
}
