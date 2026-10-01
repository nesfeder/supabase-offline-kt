// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.OfflineReadResult
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRemoteTable
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.postgrestResultOf
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Integration tests across the real read stack: [QueryReader], [cacheFirstWithParallelRefresh], a
 * real [OfflineStore] over in-memory DAOs and a real [LiveQueries], with only the network SELECT
 * scripted. They pin what unit tests cannot: the wiring of the accelerator and the coordinator into
 * the refresh, the per-query [OfflineReadResult], single-flight sharing, and that nothing seeds an
 * empty cache with `[]`. They run under the non-default
 * [ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY], whose rules their expectations assume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueryReaderRecoveryTest {

    private val freshPayload = """[{"id":"project-1"}]"""

    @Test
    fun with_state_reports_no_data_then_stalled_then_network_idle_on_late_success() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: nothing cached, and a network SELECT that fails until told otherwise
            val postgrest = testPostgrest()
            val wakeUps = MutableSharedFlow<Unit>()
            var failing = true
            val remoteTable =
                FakeRemoteTable(
                    table = "projects",
                    postgrest = postgrest,
                    onSelect = {
                        if (failing) throw RuntimeException("network down")
                        else postgrestResultOf(freshPayload, postgrest)
                    },
                )
            val resolver = buildResolver(remoteTable, backgroundScope, wakeUps)

            // When: a CacheFirstThenNetwork subscription with state starts and the fast retries run
            // out
            val reads = mutableListOf<OfflineReadResult>()
            val collector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect { read -> reads += read }
            }
            advanceTimeBy(5_000)
            runCurrent()

            // Then: the first emission is the typed no-data state, and the ladder exhaustion is
            // Stalled — never a fabricated empty payload
            assertNull(
                reads.first().result,
                "fresh cache-less subscription starts at no-data, not empty []",
            )
            assertIs<RefreshState.Refreshing>(reads.first().refresh)
            assertTrue(
                reads.any { it.result == null && it.refresh is RefreshState.Stalled },
                "fast-ladder exhaustion surfaces Stalled while still no-data",
            )
            assertFalse(collector.isCompleted, "subscription must survive exhaustion")

            // When: the network heals and an accelerator edge fires
            failing = false
            wakeUps.emit(Unit)
            runCurrent()

            // Then: the fresh payload arrives with the refresh settled to Idle
            val last = reads.last()
            assertEquals(freshPayload, last.result?.data)
            assertIs<RefreshState.Idle>(last.refresh)

            collector.cancelAndJoin()
        }

    @Test
    fun plain_select_as_flow_emits_no_empty_seed_then_the_network_payload() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a network SELECT that succeeds immediately
            val postgrest = testPostgrest()
            val remoteTable =
                FakeRemoteTable(
                    table = "projects",
                    postgrest = postgrest,
                    onSelect = { postgrestResultOf(freshPayload, postgrest) },
                )
            val resolver = buildResolver(remoteTable, backgroundScope)

            // When: a CacheFirstThenNetwork subscription collects, mapped the way every consumer
            // maps it (drop the typed no-data state, dedupe consecutive payloads)
            val emissions = mutableListOf<String>()
            val collector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .mapNotNull { read -> read.result?.data }
                    .distinctUntilChanged()
                    .collect { data -> emissions += data }
            }
            advanceUntilIdle()

            // Then: no phantom "[]" seed emission — the first (only) payload is the real one
            assertEquals(listOf(freshPayload), emissions)

            collector.cancelAndJoin()
        }

    @Test
    fun two_collectors_on_one_signature_share_a_single_refresh() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a scripted network counter
            val postgrest = testPostgrest()
            var networkCalls = 0
            val remoteTable =
                FakeRemoteTable(
                    table = "projects",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        postgrestResultOf(freshPayload, postgrest)
                    },
                )
            val resolver = buildResolver(remoteTable, backgroundScope)

            // When: two collectors subscribe to the same query (same signature) concurrently
            val firstCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            val secondCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()

            // Then: exactly one refresh executed — the loop was shared, not duplicated
            assertEquals(
                1,
                networkCalls,
                "two collectors on one signature share a single refresh loop",
            )

            firstCollector.cancelAndJoin()
            secondCollector.cancelAndJoin()
        }

    @Test
    fun re_subscription_after_last_collector_leaves_refreshes_fresh_and_serves_no_stale_replay() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: the network serves a distinct payload on each call, tracked by a counter
            val postgrest = testPostgrest()
            val secondPayload = """[{"id":"project-2"}]"""
            var networkCalls = 0
            val remoteTable =
                FakeRemoteTable(
                    table = "projects",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        postgrestResultOf(
                            if (networkCalls == 1) freshPayload else secondPayload,
                            postgrest,
                        )
                    },
                )
            val resolver = buildResolver(remoteTable, backgroundScope)

            // When: one collector subscribes, receives the first refresh, then fully unsubscribes
            val firstCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()
            firstCollector.cancelAndJoin()
            advanceUntilIdle()
            assertEquals(1, networkCalls, "the first subscription runs exactly one refresh")

            // And: a fresh collector subscribes to the same signature after the first has left
            val laterReads = mutableListOf<String>()
            val secondCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .mapNotNull { read -> read.result?.data }
                    .distinctUntilChanged()
                    .collect { data -> laterReads += data }
            }
            advanceUntilIdle()

            // Then: the memoized single-flight share did not replay the first subscription's stale
            // outcome — with replayExpirationMillis = 0 the replay cache was dropped when the last
            // collector left, so the re-subscription starts a genuinely fresh refresh (second
            // network call) and observes the fresh payload rather than a stale replayed one.
            assertEquals(2, networkCalls, "a re-subscription starts a fresh refresh, not a replay")
            assertEquals(
                secondPayload,
                laterReads.last(),
                "re-subscription serves fresh data, not a stale replay",
            )

            secondCollector.cancelAndJoin()
        }

    @Test
    fun one_shot_cache_first_misses_cache_when_a_live_subscription_is_unpopulated() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a live CacheFirstThenNetwork subscription whose network is down — with the
            // seed removed, it populates nothing
            val postgrest = testPostgrest()
            var failing = true
            val remoteTable =
                FakeRemoteTable(
                    table = "projects",
                    postgrest = postgrest,
                    onSelect = {
                        if (failing) throw RuntimeException("network down")
                        else postgrestResultOf(freshPayload, postgrest)
                    },
                )
            val resolver = buildResolver(remoteTable, backgroundScope)

            val live = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            runCurrent()

            // When: a one-shot CacheFirst read runs for the same signature while the network heals
            failing = false
            val oneShot = resolver.select(Columns.ALL, ReadStrategy.CacheFirst()) {}

            // Then: it missed the (non-existent, un-seeded) cache and went to the network — a
            // phantom "[]" seed would have short-circuited it to an empty cache hit instead
            assertEquals(freshPayload, oneShot.data)

            live.cancelAndJoin()
        }

    @Test
    fun auth_ready_empty_refresh_replaces_cached_non_empty_payload() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a first subscription cached a non-empty payload (the user's rows)
            val postgrest = testPostgrest()
            var serverRows = freshPayload
            var networkCalls = 0
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        postgrestResultOf(serverRows, postgrest)
                    },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val resolver =
                buildResolver(remoteTable, backgroundScope, queryCacheDao = queryCacheDao)
            val primingCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()
            primingCollector.cancelAndJoin()
            // Let the single-flight share fully tear down so the re-subscription below starts
            // a genuinely fresh refresh instead of attaching to the completed one.
            advanceUntilIdle()

            // When: every row matching the query is deleted remotely and a fresh subscription
            // refreshes with the auth session fully restored
            serverRows = "[]"
            val emissions = mutableListOf<String>()
            val collector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .mapNotNull { read -> read.result?.data }
                    .distinctUntilChanged()
                    .collect { data -> emissions += data }
            }
            advanceUntilIdle()

            // Then: the authoritative empty replaces the cached rows, in the store and at the
            // collector, rather than being dropped while the deleted rows are still served. (The
            // eager refresh may overwrite the cache before the first cached emission reaches the
            // collector, so only convergence is asserted, not the stale-then-empty sequence.)
            assertEquals(2, networkCalls, "the re-subscription must run its own refresh")
            assertEquals("[]", emissions.last(), "collector converges to the server's empty")
            assertEquals("[]", queryCacheDao.allForTable("plans").single().payload)

            collector.cancelAndJoin()
        }

    @Test
    fun empty_refresh_while_auth_restoring_preserves_cache_until_session_ready() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a cached non-empty payload from an auth-ready session, after which the
            // process restarts — the next cold-start refresh races session restore while the
            // server (legitimately or RLS-filtered, the client can't tell yet) returns []
            val postgrest = testPostgrest()
            var serverRows = freshPayload
            var authState: RequestSession = SIGNED_IN
            val wakeUps = MutableSharedFlow<Unit>()
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = { postgrestResultOf(serverRows, postgrest) },
                )
            val resolver =
                buildResolver(remoteTable, backgroundScope, wakeUps, authState = { authState })
            val primingCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()
            primingCollector.cancelAndJoin()
            // Let the single-flight share fully tear down so the re-subscription below starts
            // a genuinely fresh refresh instead of attaching to the completed one.
            advanceUntilIdle()
            serverRows = "[]"
            authState = RequestSession.Restoring

            // When: a new subscription refreshes during the restore window
            val emissions = mutableListOf<String>()
            val collector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .mapNotNull { read -> read.result?.data }
                    .distinctUntilChanged()
                    .collect { data -> emissions += data }
            }
            advanceTimeBy(1_000)
            runCurrent()

            // Then: the empty is treated as the cold-start artifact — cached rows are
            // preserved and the refresh keeps retrying rather than accepting it
            assertEquals(listOf(freshPayload), emissions)

            // When: session restore completes and fires the recovery accelerator (the
            // auth-restored edge), short-circuiting the current backoff sleep
            authState = SIGNED_IN
            wakeUps.emit(Unit)
            runCurrent()

            // Then: the same [] is now authoritative and replaces the cached rows
            assertEquals(listOf(freshPayload, "[]"), emissions)

            collector.cancelAndJoin()
        }

    @Test
    fun one_shot_network_first_accepts_authoritative_empty_when_authenticated() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a cached non-empty payload from a prior authenticated subscription
            val postgrest = testPostgrest()
            var serverRows = freshPayload
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = { postgrestResultOf(serverRows, postgrest) },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val resolver =
                buildResolver(remoteTable, backgroundScope, queryCacheDao = queryCacheDao)
            val primingCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()
            primingCollector.cancelAndJoin()

            // When: every matching row is deleted remotely and a one-shot NetworkFirst read
            // (the default per-call auth snapshot, not the flow-refresh path) sees the empty
            serverRows = "[]"
            val oneShot = resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

            // Then: the caller sees the server's answer and the cache converges to it
            assertEquals("[]", oneShot.data)
            assertEquals("[]", queryCacheDao.allForTable("plans").single().payload)
        }

    @Test
    fun one_shot_network_first_drops_empty_when_session_not_authenticated() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a cached non-empty payload from an authenticated subscription, after
            // which the user signs out (or token refresh terminally fails) with the
            // subscription torn down but the cache not yet cleared
            val postgrest = testPostgrest()
            var serverRows = freshPayload
            var authState: RequestSession = SIGNED_IN
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = { postgrestResultOf(serverRows, postgrest) },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val resolver =
                buildResolver(
                    remoteTable,
                    backgroundScope,
                    authState = { authState },
                    queryCacheDao = queryCacheDao,
                )
            val primingCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()
            primingCollector.cancelAndJoin()

            // When: a one-shot NetworkFirst read runs on the anon key and RLS filters every row
            serverRows = "[]"
            authState = RequestSession.SignedOut
            val oneShot = resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

            // Then: the caller still sees the server response, but the RLS-shaped empty says
            // nothing about the signed-in user's data — the cached rows are preserved for
            // identity teardown (clearAllCache) to remove, not clobbered
            assertEquals("[]", oneShot.data)
            assertEquals(freshPayload, queryCacheDao.allForTable("plans").single().payload)
        }

    @Test
    fun mid_request_sign_out_does_not_mark_the_empty_authoritative() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: cached rows from an authenticated session. supabase-kt attaches the JWT
            // *inside* the select call — after the resolver's pre-request snapshot — so a
            // sign-out landing in that gap sends the request out on the anon key. Modeled by
            // flipping the session state from within onSelect.
            val postgrest = testPostgrest()
            var serverRows = freshPayload
            var authState: RequestSession = SIGNED_IN
            var flipMidRequest = false
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = {
                        if (flipMidRequest) authState = RequestSession.SignedOut
                        postgrestResultOf(serverRows, postgrest)
                    },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val resolver =
                buildResolver(
                    remoteTable,
                    backgroundScope,
                    authState = { authState },
                    queryCacheDao = queryCacheDao,
                )
            val primingCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()
            primingCollector.cancelAndJoin()

            // When: a refresh whose pre-request snapshot said AUTHENTICATED races a sign-out,
            // and the anon-key request comes back RLS-empty
            serverRows = "[]"
            flipMidRequest = true
            val oneShot = resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

            // Then: the post-response check sees the session is gone — the [] is anon-shaped,
            // not authoritative, and the cached rows survive for identity teardown to clear
            assertEquals("[]", oneShot.data)
            assertEquals(freshPayload, queryCacheDao.allForTable("plans").single().payload)
        }

    @Test
    fun retry_and_accept_decisions_share_one_pre_request_snapshot() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: cached rows, and a session whose very first state read is RESTORING with
            // every later read AUTHENTICATED — the restore completing right after the refresh
            // snapshots it. The refresh must thread that ONE snapshot to both the store-write
            // and the retry decision: if the write path took its own (now AUTHENTICATED)
            // snapshot instead, this attempt would wrongly accept the [] AND schedule a retry.
            val postgrest = testPostgrest()
            var serverRows = freshPayload
            var authStateProvider: () -> RequestSession = { SIGNED_IN }
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = { postgrestResultOf(serverRows, postgrest) },
                )
            val resolver =
                buildResolver(remoteTable, backgroundScope, authState = { authStateProvider() })
            val primingCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()
            primingCollector.cancelAndJoin()
            // Let the single-flight share fully tear down before re-subscribing.
            advanceUntilIdle()
            serverRows = "[]"
            var stateReads = 0
            authStateProvider = {
                if (stateReads++ == 0) RequestSession.Restoring else SIGNED_IN
            }

            // When: a new subscription runs its first refresh attempt (snapshot = RESTORING)
            val emissions = mutableListOf<String>()
            val collector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .mapNotNull { read -> read.result?.data }
                    .distinctUntilChanged()
                    .collect { data -> emissions += data }
            }
            runCurrent()

            // Then: the [] was NOT accepted on the first attempt — the RESTORING snapshot
            // governed the write even though the session read AUTHENTICATED by response time
            assertEquals(listOf(freshPayload), emissions)

            // When: the transient retry fires after the first backoff step
            advanceTimeBy(300)
            runCurrent()

            // Then: the retry's own AUTHENTICATED snapshot makes the same [] authoritative
            assertEquals(listOf(freshPayload, "[]"), emissions)

            collector.cancelAndJoin()
        }

    @Test
    fun every_network_bound_one_shot_strategy_walks_the_restore_ladder() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: the three one-shot strategies that can reach the network. CacheOnly is
            // excluded by design — it never issues a request, so it has nothing to retry. Running
            // them from one body keeps the wiring pinned for all three instead of for whichever
            // two someone remembered to copy.
            val networkBoundStrategies =
                listOf(
                    "NetworkFirst" to ReadStrategy.NetworkFirst(),
                    "NetworkOnly" to ReadStrategy.NetworkOnly,
                    "CacheFirst (cold miss)" to ReadStrategy.CacheFirst(),
                )

            for ((strategyName, strategy) in networkBoundStrategies) {
                // And: a cold cache and a read racing session restore: sent with only the anon key,
                // it has every row hidden by RLS, so PostgREST answers `200 []` for rows that exist
                val postgrest = testPostgrest()
                var serverRows = "[]"
                var authState: RequestSession = RequestSession.Restoring
                var networkCalls = 0
                val wakeUps = MutableSharedFlow<Unit>()
                val remoteTable =
                    FakeRemoteTable(
                        table = "plans",
                        postgrest = postgrest,
                        onSelect = {
                            networkCalls++
                            postgrestResultOf(serverRows, postgrest)
                        },
                    )
                val queryCacheDao = FakeQueryCacheDao()
                val resolver =
                    buildResolver(
                        remoteTable = remoteTable,
                        scope = backgroundScope,
                        wakeUps = wakeUps,
                        authState = { authState },
                        queryCacheDao = queryCacheDao,
                    )

                // When: the one-shot read runs inside the restore window
                val oneShot = async { resolver.select(Columns.ALL, strategy) {} }
                runCurrent()

                // Then: the RLS-shaped [] is not handed back as the answer — the read is on the
                // ladder waiting for a session that can actually see the user's rows
                assertEquals(1, networkCalls, "$strategyName: the first attempt has gone out")
                assertFalse(
                    oneShot.isCompleted,
                    "$strategyName: a restore-window empty must not resolve the read",
                )

                // When: session restore completes and fires the recovery accelerator (the
                // auth-restored edge), short-circuiting the current backoff sleep
                authState = SIGNED_IN
                serverRows = freshPayload
                wakeUps.emit(Unit)
                runCurrent()

                // Then: the retry goes out with a usable JWT, the caller gets the real rows
                // instead of an empty list, and the cache converges on them
                assertEquals(2, networkCalls, "$strategyName: the edge cut the backoff short")
                assertEquals(freshPayload, oneShot.await().data, strategyName)
                assertEquals(
                    freshPayload,
                    queryCacheDao.allForTable("plans").single().payload,
                    strategyName,
                )
            }
        }

    @Test
    fun one_shot_restore_window_ladder_waits_before_it_spends_a_second_request() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a session that never finishes restoring, so RLS filters every request
            val postgrest = testPostgrest()
            var networkCalls = 0
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        postgrestResultOf("[]", postgrest)
                    },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val resolver =
                buildResolver(
                    remoteTable = remoteTable,
                    scope = backgroundScope,
                    authState = { RequestSession.Restoring },
                    queryCacheDao = queryCacheDao,
                )

            // When: a one-shot read walks the whole ladder
            val oneShot = async { resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {} }
            runCurrent()
            assertEquals(1, networkCalls, "attempt 1 goes out immediately")

            // Then: each step boundary re-reads the session and declines to spend a request while
            // it is still restoring — a request issued now is guaranteed to come back RLS-shaped
            advanceTimeBy(300)
            runCurrent()
            assertEquals(1, networkCalls, "the 300ms boundary re-checks rather than re-requests")
            advanceTimeBy(900)
            runCurrent()
            assertEquals(1, networkCalls, "so does the 900ms boundary")

            // And: only the final boundary spends the terminal attempt the caller is owed
            advanceTimeBy(2_400)
            runCurrent()
            assertEquals(2, networkCalls, "the exhausted ladder fires one terminal attempt")

            // And: the ladder is bounded — the caller is answered rather than blocked forever
            assertEquals("[]", oneShot.await().data, "the terminal fallback is today's behavior")
            assertEquals(3_600L, currentTime, "three waits, ~3.6s worst case")
            assertEquals(2, networkCalls, "two round trips for the whole ladder, not four")

            // And: nothing was cached. The store never persists an unattributed empty, so an
            // exhausted ladder costs one render of "no data" and not a poisoned cache slot that
            // later cache-first reads would serve as the user's data.
            assertTrue(
                queryCacheDao.allForTable("plans").isEmpty(),
                "an RLS-shaped empty must not reach a cold cache slot",
            )
        }

    @Test
    fun one_shot_ladder_fires_at_the_next_boundary_when_the_session_returns_without_an_edge() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a restore-window empty on the first attempt. The production accelerator is a
            // cold `drop(1)` flow, so an auth-ready transition that lands between two collections
            // of it is simply never delivered — this test emits no edge at all to model that.
            val postgrest = testPostgrest()
            var serverRows = "[]"
            var authState: RequestSession = RequestSession.Restoring
            var networkCalls = 0
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        postgrestResultOf(serverRows, postgrest)
                    },
                )
            val resolver =
                buildResolver(
                    remoteTable = remoteTable,
                    scope = backgroundScope,
                    authState = { authState },
                )
            val oneShot = async { resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {} }
            runCurrent()
            assertEquals(1, networkCalls)

            // When: the session finishes restoring mid-wait and nothing wakes the ladder
            authState = SIGNED_IN
            serverRows = freshPayload

            // Then: the very next step boundary observes it and fires — the missed edge costs one
            // ladder step, not the whole ladder
            advanceTimeBy(300)
            runCurrent()
            assertEquals(2, networkCalls, "the boundary re-read the session and fired")
            assertEquals(freshPayload, oneShot.await().data)
            assertEquals(300L, currentTime, "the remaining ladder steps are never slept")
        }

    @Test
    fun one_shot_restore_window_empty_still_retries_when_a_pending_mutation_masks_it() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a queued offline INSERT. Its overlay makes the payload the caller is served
            // non-empty even though the server sent `[]`, so classifying emptiness on the served
            // result — rather than on the raw server body — would skip the ladder entirely.
            val postgrest = testPostgrest()
            var serverRows = "[]"
            var authState: RequestSession = RequestSession.Restoring
            var networkCalls = 0
            val wakeUps = MutableSharedFlow<Unit>()
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        postgrestResultOf(serverRows, postgrest)
                    },
                )
            val store = buildStore()
            store.enqueueInsert(
                table = "plans",
                body =
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "plan-queued")
                                put("user_id", "u-1")
                            }
                        )
                    },
            )
            val resolver =
                buildResolver(
                    remoteTable = remoteTable,
                    scope = backgroundScope,
                    wakeUps = wakeUps,
                    authState = { authState },
                    store = store,
                )

            // When: a one-shot read runs inside the restore window
            val oneShot = async { resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {} }
            runCurrent()

            // Then: the overlaid row does not mask the RLS-shaped empty — the ladder engages
            assertEquals(1, networkCalls)
            assertFalse(
                oneShot.isCompleted,
                "emptiness must be classified on the raw server body, not the overlaid result",
            )

            // When: the session returns
            authState = SIGNED_IN
            serverRows = freshPayload
            wakeUps.emit(Unit)
            runCurrent()

            // Then: the caller is served the real server row (with its own pending write still
            // overlaid on top, as it was before)
            assertEquals(2, networkCalls)
            val served = oneShot.await().data
            assertTrue(served.contains("project-1"), "the retry's server rows reach the caller")
            assertTrue(served.contains("plan-queued"), "the pending write is still overlaid")
        }

    @Test
    fun one_shot_teardown_refused_response_is_never_retried() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a one-shot read whose request parks in flight during the restore window
            val postgrest = testPostgrest()
            var networkCalls = 0
            val inFlight = CompletableDeferred<Unit>()
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        if (networkCalls == 1) inFlight.await()
                        postgrestResultOf("[]", postgrest)
                    },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val store = buildStore(queryCacheDao)
            val resolver =
                buildResolver(
                    remoteTable = remoteTable,
                    scope = backgroundScope,
                    authState = { RequestSession.Restoring },
                    store = store,
                )
            val oneShot = async { resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {} }
            runCurrent()
            assertEquals(1, networkCalls, "the request must be in flight before teardown")

            // When: the user signs out and the cache is wiped while the request is out, and the
            // parked response is then delivered
            store.clearLocalData()
            inFlight.complete(Unit)
            advanceUntilIdle()

            // Then: the caller is answered with the synthesized empty the emptied cache now holds,
            // and the ladder does NOT re-request. Sign-out is exactly when a follow-up could still
            // carry the outgoing session's JWT and re-import the rows teardown just removed — so
            // the teardown refusal is checked before the emptiness classification, not after.
            assertEquals("[]", oneShot.await().data)
            assertEquals(1, networkCalls, "a teardown-refused response must not trigger a retry")
            assertEquals(0L, currentTime, "and must not spend a ladder step either")
            assertTrue(queryCacheDao.allForTable("plans").isEmpty())
        }

    @Test
    fun one_shot_non_empty_response_while_restoring_waits_for_the_session_like_an_empty_one() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: the session is still restoring, and the request came back with rows — the anon
            // key's answer, narrowed by RLS whatever its size
            val postgrest = testPostgrest()
            var networkCalls = 0
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        postgrestResultOf(freshPayload, postgrest)
                    },
                )
            val resolver =
                buildResolver(
                    remoteTable = remoteTable,
                    scope = backgroundScope,
                    authState = { RequestSession.Restoring },
                )

            // When: a one-shot read runs
            val oneShot = resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

            // Then: it waits out the restore window (300, 900 and 2,400 ms), asks once more, and
            // returns that answer uncached when the session never settles
            assertEquals(freshPayload, oneShot.data)
            assertEquals(2, networkCalls, "a non-empty response waits for the session too")
            assertEquals(3_600L, currentTime)
        }

    @Test
    fun one_shot_unauthenticated_empty_is_returned_without_a_retry() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: the user is signed out. The anon-key request is RLS-filtered exactly like the
            // restore-window case and looks identical on the wire — but a blocking caller must not
            // sit waiting on a sign-in that may never come.
            val postgrest = testPostgrest()
            var networkCalls = 0
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        postgrestResultOf("[]", postgrest)
                    },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val resolver =
                buildResolver(
                    remoteTable = remoteTable,
                    scope = backgroundScope,
                    authState = { RequestSession.SignedOut },
                    queryCacheDao = queryCacheDao,
                )

            // When: a one-shot read runs on the anon key
            val oneShot = resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

            // Then: the response is returned immediately — no ladder, no sleep — and the
            // RLS-shaped empty still never reaches the cache
            assertEquals("[]", oneShot.data)
            assertEquals(1, networkCalls, "an unauthenticated empty must not enter the ladder")
            assertEquals(0L, currentTime, "and must not spend a single backoff step")
            assertTrue(queryCacheDao.allForTable("plans").isEmpty())
        }

    @Test
    fun one_shot_authenticated_empty_seeds_a_cold_cache_slot() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a signed-in session and a table that genuinely has no rows for this user
            val postgrest = testPostgrest()
            var networkCalls = 0
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        postgrestResultOf("[]", postgrest)
                    },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val resolver =
                buildResolver(
                    remoteTable = remoteTable,
                    scope = backgroundScope,
                    authState = { SIGNED_IN },
                    queryCacheDao = queryCacheDao,
                )

            // When: a one-shot read runs
            val oneShot = resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

            // Then: the empty is the server's real answer, returned at once and written through —
            // the end-to-end half of the store rule that only *unattributed* empties are dropped.
            // Without this seed a collector would wait forever for a row that is never coming.
            assertEquals("[]", oneShot.data)
            assertEquals(1, networkCalls, "an authenticated empty never enters the ladder")
            assertEquals(0L, currentTime)
            assertEquals("[]", queryCacheDao.allForTable("plans").single().payload)
        }

    @Test
    fun a_refused_empty_does_not_retire_a_synced_mutation() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a primed cache, plus a mutation that has replayed successfully but has not
            // been retired yet — its overlay is still standing in for server state
            val postgrest = testPostgrest()
            var serverRows = freshPayload
            var authState: RequestSession = SIGNED_IN
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = { postgrestResultOf(serverRows, postgrest) },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val mutationDao = FakeMutationDao()
            val store = buildStore(queryCacheDao, mutationDao)
            val resolver =
                buildResolver(
                    remoteTable = remoteTable,
                    scope = backgroundScope,
                    authState = { authState },
                    store = store,
                )
            val primingCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()
            primingCollector.cancelAndJoin()
            advanceUntilIdle()

            store.enqueueInsert(
                table = "plans",
                body =
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "plan-replayed")
                                put("user_id", "u-1")
                            }
                        )
                    },
            )
            store.markMutationSynced(id = mutationDao.inserted.single().id, table = "plans")

            // When: the next SELECT comes back RLS-shaped empty and the cache refuses it
            serverRows = "[]"
            authState = RequestSession.SignedOut
            resolver.select(Columns.ALL, ReadStrategy.NetworkFirst()) {}

            // Then: the refused payload did not authorize retirement. Retiring the overlay while
            // refusing the payload that was supposed to replace it would drop the replayed write
            // out of the queue and the cache in the same pass — just as true when the refusal is
            // the empty-clobber guard as when it is the stale watermark.
            assertEquals(1, mutationDao.inserted.size, "a refused empty must not retire the replay")
            assertEquals(
                freshPayload,
                queryCacheDao.allForTable("plans").single().payload,
                "and the cached rows it stood in for are still there",
            )
        }

    @Test
    fun unauthenticated_empty_refresh_keeps_the_subscription_retrying_until_the_session_returns() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a cold cache and a subscription whose refresh goes out while the token
            // refresh is failing — the anon-key request is RLS-filtered to []
            val postgrest = testPostgrest()
            var serverRows = "[]"
            var authState: RequestSession = RequestSession.SignedOut
            val wakeUps = MutableSharedFlow<Unit>()
            val remoteTable =
                FakeRemoteTable(
                    table = "plans",
                    postgrest = postgrest,
                    onSelect = { postgrestResultOf(serverRows, postgrest) },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val resolver =
                buildResolver(
                    remoteTable = remoteTable,
                    scope = backgroundScope,
                    wakeUps = wakeUps,
                    authState = { authState },
                    queryCacheDao = queryCacheDao,
                )

            // When: the subscription runs long enough to exhaust the fast ladder
            val reads = mutableListOf<OfflineReadResult>()
            val collector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect { read -> reads += read }
            }
            advanceTimeBy(5_000)
            runCurrent()

            // Then: nothing was cached, and the refresh has NOT reported itself settled. Treating
            // only RESTORING as retryable would land here at Idle over a slot that was never
            // written — a subscription that looks healthy while showing an empty screen, with no
            // retry left to fix it when the token refresh recovers.
            assertTrue(
                queryCacheDao.allForTable("plans").isEmpty(),
                "the RLS-shaped empty is not cached",
            )
            assertNull(reads.last().result, "and the subscription is still at no-data")
            assertTrue(
                reads.none { it.refresh is RefreshState.Idle },
                "an unattributed empty must never settle the refresh to Idle",
            )
            assertTrue(
                reads.any { it.refresh is RefreshState.Stalled },
                "the fast ladder exhausting is surfaced as Stalled, and retrying continues",
            )

            // When: the session comes back and the auth-restored edge fires
            authState = SIGNED_IN
            serverRows = freshPayload
            wakeUps.emit(Unit)
            runCurrent()

            // Then: the still-alive loop converges on the real rows
            assertEquals(freshPayload, reads.last().result?.data)
            assertIs<RefreshState.Idle>(reads.last().refresh)

            collector.cancelAndJoin()
        }

    @Test
    fun a_slow_refresh_that_started_before_a_local_insert_does_not_erase_it() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: the cache is primed with the server's single row, and the next refresh hangs
            // in flight after capturing the server state it will eventually report
            val postgrest = testPostgrest()
            val bothProjects = """[{"id":"project-1"},{"id":"project-2"}]"""
            var serverRows = freshPayload
            var networkCalls = 0
            val inFlight = CompletableDeferred<Unit>()
            val remoteTable =
                FakeRemoteTable(
                    table = "projects",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        // Snapshot before parking: this request answers with the server as it was
                        // when the request went out, not when the response is finally delivered.
                        val answer = serverRows
                        if (networkCalls == 2) inFlight.await()
                        postgrestResultOf(answer, postgrest)
                    },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val store = buildStore(queryCacheDao)
            val resolver = buildResolver(remoteTable, backgroundScope, store = store)
            val primingCollector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            advanceUntilIdle()
            primingCollector.cancelAndJoin()
            // Let the single-flight share tear down so the next subscription refreshes for real.
            advanceUntilIdle()

            // When: a new subscription's refresh goes out (and parks), and while it is in flight
            // the user saves a second project — it succeeds online and is patched into the cache
            val emissions = mutableListOf<String>()
            val collector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .mapNotNull { read -> read.result?.data }
                    .distinctUntilChanged()
                    .collect { data -> emissions += data }
            }
            runCurrent()
            assertEquals(2, networkCalls, "the second subscription's refresh must be in flight")
            store.patchCachedQueries(
                table = "projects",
                operation = MutationOperation.INSERT,
                bodyJson = """[{"id":"project-2"}]""",
                filterJson = null,
                defaultToNull = true,
            )
            runCurrent()
            assertEquals(
                bothProjects,
                emissions.last(),
                "the save is visible before the race resolves",
            )

            // And: the server now holds both rows, and the parked pre-insert response is delivered
            serverRows = bothProjects
            inFlight.complete(Unit)
            advanceUntilIdle()

            // Then: the stale response did not roll the cache back to the single row — it was
            // refused, one follow-up request was issued, and that request (which started after
            // the insert) converged the cache on real server state.
            assertEquals(
                bothProjects,
                queryCacheDao.allForTable("projects").single().payload,
                "the pre-insert response must not erase the saved row",
            )
            assertEquals(bothProjects, emissions.last(), "the collector never loses the saved row")
            assertFalse(
                emissions.contains(freshPayload) &&
                    emissions.indexOf(freshPayload) > emissions.indexOf(bothProjects),
                "the cache must never revert to the pre-insert payload",
            )
            assertEquals(3, networkCalls, "a refused stale response triggers exactly one re-fetch")

            collector.cancelAndJoin()
        }

    @Test
    fun a_refresh_parked_across_sign_out_does_not_repopulate_the_wiped_cache() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a subscription whose first refresh goes out and parks in flight
            val postgrest = testPostgrest()
            var networkCalls = 0
            val inFlight = CompletableDeferred<Unit>()
            val remoteTable =
                FakeRemoteTable(
                    table = "projects",
                    postgrest = postgrest,
                    onSelect = {
                        networkCalls++
                        if (networkCalls == 1) inFlight.await()
                        postgrestResultOf(freshPayload, postgrest)
                    },
                )
            val queryCacheDao = FakeQueryCacheDao()
            val store = buildStore(queryCacheDao)
            val resolver = buildResolver(remoteTable, backgroundScope, store = store)
            val collector = launch {
                resolver
                    .selectAsFlow(Columns.ALL, FlowReadStrategy.CacheFirstThenNetwork) {}
                    .collect {}
            }
            runCurrent()
            assertEquals(1, networkCalls, "the refresh must be in flight before teardown")

            // When: the user signs out (or switches accounts) and the cache is wiped mid-flight
            store.clearLocalData()

            // And: the parked response — the outgoing account's rows — is finally delivered
            inFlight.complete(Unit)
            advanceUntilIdle()

            // Then: the wipe holds. Writing the response back would put one account's rows in
            // front of the next user, and a follow-up request during sign-out could still carry
            // the outgoing JWT — so neither happens.
            assertTrue(
                queryCacheDao.allForTable("projects").isEmpty(),
                "a response that predates teardown must not repopulate the cache",
            )
            assertEquals(1, networkCalls, "a teardown-refused response must not trigger a re-fetch")

            collector.cancelAndJoin()
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun buildResolver(
        remoteTable: FakeRemoteTable,
        scope: CoroutineScope,
        wakeUps: MutableSharedFlow<Unit> = MutableSharedFlow(),
        authState: () -> RequestSession = { SIGNED_IN },
        queryCacheDao: FakeQueryCacheDao = FakeQueryCacheDao(),
        store: OfflineStore = buildStore(queryCacheDao),
    ): QueryReader =
        QueryReader(
            delegate = remoteTable,
            store = store,
            requestSession = authState,
            trustPolicy = ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY,
            liveQueries = LiveQueries(scope),
            wakeUps = wakeUps,
        )

    /**
     * Minimal real [Postgrest] for [FakeRemoteTable] / [postgrestResultOf] — the resolver reads its
     * config for signature building and stamps it on results. The [MockEngine] is never hit; the
     * fake wrapper intercepts all SELECT traffic.
     */
    private fun testPostgrest(): Postgrest = newTestSupabaseClient().postgrest

    private fun buildStore(
        queryCacheDao: FakeQueryCacheDao = FakeQueryCacheDao(),
        mutationDao: FakeMutationDao = FakeMutationDao(),
    ): OfflineStore =
        newTestOfflineStore(
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
        )

    private companion object {
        /** A signed-in session, as the reader's session source reports one. */
        val SIGNED_IN: RequestSession = RequestSession.SignedIn(QueueIdentity.User("u-1"))
    }
}
