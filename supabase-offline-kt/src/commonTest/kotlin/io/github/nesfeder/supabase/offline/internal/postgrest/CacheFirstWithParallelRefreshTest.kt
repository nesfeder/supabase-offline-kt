// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.RefreshState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Tests for [cacheFirstWithParallelRefresh] — the self-healing combinator that backs [QueryReader].
 * These lock in the contract the design depends on: cache forwards without waiting on the network,
 * the refresh survives exhaustion and keeps retrying on a capped schedule, an accelerator edge
 * short-circuits the current backoff, the first success ends the loop, and cancellation propagates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheFirstWithParallelRefreshTest {

    // Cache-first emits before the network completes ──────────────────────────

    @Test
    fun cache_emits_before_refresh_completes() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a cache with a value ready immediately, and a refresh that never completes
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("cached-X")
            val refreshBlocker = CompletableDeferred<Unit>()
            var refreshCalled = false

            // When: we collect the first emission
            val first =
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = {
                            refreshCalled = true
                            refreshBlocker.await()
                        },
                    )
                    .first()

            // Then: the cached value arrived and refresh was started in parallel, not blocking it
            assertEquals("cached-X", first)
            assertTrue(refreshCalled, "refresh should be launched in parallel")
            assertFalse(refreshBlocker.isCompleted, "refresh must still be in flight")

            refreshBlocker.complete(Unit)
        }

    // Refresh-side update propagates downstream ────────────────────────────────

    @Test
    fun refresh_side_update_reaches_observer() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a cache that starts with X; refresh writes Y into the cache
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("X")
            val emissions = mutableListOf<String>()
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = { cache.emit("Y") },
                    )
                    .take(2)
                    .toList(emissions)
            }

            // When: both values are collected
            collector.join()

            // Then: X (cache) then Y (refresh-triggered cache update)
            assertEquals(listOf("X", "Y"), emissions)
        }

    // Refresh failure does not terminate the flow ──────────────────────────────

    @Test
    fun refresh_failure_does_not_terminate_flow() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a cache that starts with X; refresh throws
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("X")
            val emissions = mutableListOf<String>()
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = { throw RuntimeException("network exploded") },
                    )
                    .take(2)
                    .toList(emissions)
            }
            yield()

            // When: a later cache emission is made
            cache.emit("Z")
            collector.join()

            // Then: both X and Z arrived — the refresh failure was tolerated and the cache still
            // flows
            assertEquals(listOf("X", "Z"), emissions)
        }

    // Transient failure retries and recovers ───────────────────────────────────

    @Test
    fun refresh_retries_after_transient_failure_and_writes_to_cache() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: the first refresh attempt fails, the second writes real data
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("[]")
            var attemptCount = 0
            val emissions = mutableListOf<String>()
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = {
                            attemptCount++
                            if (attemptCount == 1)
                                throw UntrustedResponseException(table = "ratings")
                            cache.emit("""[{"id":1}]""")
                        },
                    )
                    .take(2)
                    .toList(emissions)
            }

            // When: the first backoff (300ms) elapses and the retry runs
            advanceUntilIdle()
            collector.join()

            // Then: seeded value then post-retry payload; two attempts
            assertEquals(listOf("[]", """[{"id":1}]"""), emissions)
            assertEquals(2, attemptCount, "refresh should retry after a transient failure")
        }

    // Exhausted fast ladder keeps the flow alive and continues on the capped schedule ──

    @Test
    fun exhausted_fast_retries_keep_the_flow_alive_and_report_stalled() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a cache with a value and a refresh that never succeeds
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("cached-X")
            var attemptCount = 0
            val states = mutableListOf<RefreshState>()
            val emissions = mutableListOf<String>()
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = {
                            attemptCount++
                            throw RuntimeException("network still down")
                        },
                        onRefreshState = { state -> states += state },
                    )
                    .toList(emissions)
            }

            // When: the fast ladder (4 attempts across 300 + 900 + 2400ms) elapses
            advanceTimeBy(5_000)
            runCurrent()

            // Then: exactly 4 attempts spent, Stalled reported once, and the flow is still alive
            assertEquals(4, attemptCount, "fast ladder is one initial attempt plus three retries")
            assertTrue(
                states.any { it is RefreshState.Stalled },
                "fast-ladder exhaustion must report Stalled",
            )
            assertFalse(collector.isCompleted, "flow must survive fast-ladder exhaustion")

            // And: the capped schedule continues (a fifth attempt after the 5s backoff)
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(5, attemptCount, "capped backoff must keep retrying past the fast ladder")

            // And: a later cache update still reaches the collector (no synthetic empty injected)
            cache.emit("cached-Y")
            runCurrent()
            assertEquals(listOf("cached-X", "cached-Y"), emissions)

            collector.cancelAndJoin()
        }

    // Accelerator edge short-circuits the current backoff ───────────────────────

    @Test
    fun each_wake_up_triggers_one_immediate_retry() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a refresh that fails until told otherwise, and an accelerator we control
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("[]")
            val wakeUps = MutableSharedFlow<Unit>()
            var attemptCount = 0
            var failing = true
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = {
                            attemptCount++
                            if (failing) throw RuntimeException("down")
                            cache.emit("fresh")
                        },
                        wakeUps = wakeUps,
                    )
                    .collect {}
            }

            // First attempt fails immediately, then it is sleeping out the 300ms backoff
            runCurrent()
            assertEquals(1, attemptCount)

            // When: an accelerator edge lands well before the 300ms delay elapses
            advanceTimeBy(50)
            failing = false
            wakeUps.emit(Unit)
            runCurrent()

            // Then: exactly one immediate retry ran (not waiting out the backoff), and it succeeded
            assertEquals(
                2,
                attemptCount,
                "an accelerator edge triggers exactly one immediate retry",
            )

            collector.cancelAndJoin()
        }

    // State sequence: InFlight → Stalled → Idle on late success ─────────────────

    @Test
    fun reports_in_flight_then_stalled_then_idle_on_late_success() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a refresh failing through the whole fast ladder, recovered by an accelerator
            // edge
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("[]")
            val wakeUps = MutableSharedFlow<Unit>()
            val states = mutableListOf<RefreshState>()
            var failing = true
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = {
                            if (failing) throw RuntimeException("boom")
                            cache.emit("fresh")
                        },
                        wakeUps = wakeUps,
                        onRefreshState = { state -> states += state },
                    )
                    .collect {}
            }

            // When: the fast ladder exhausts, then the network heals and an edge fires
            advanceTimeBy(5_000)
            runCurrent()
            assertIs<RefreshState.Refreshing>(states.first())
            assertTrue(states.any { it is RefreshState.Stalled })
            failing = false
            wakeUps.emit(Unit)
            runCurrent()

            // Then: the terminal state is Idle (success ends the loop)
            assertIs<RefreshState.Idle>(states.last())

            collector.cancelAndJoin()
        }

    // First-try success reports Idle and never stalls ──────────────────────────

    @Test
    fun first_try_success_reports_idle_without_stall() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a refresh that succeeds immediately
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("[]")
            val states = mutableListOf<RefreshState>()
            var attemptCount = 0
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = {
                            attemptCount++
                            cache.emit("fresh")
                        },
                        onRefreshState = { state -> states += state },
                    )
                    .take(2)
                    .toList()
            }

            // When
            collector.join()

            // Then: one attempt, no Stalled, terminal Idle
            assertEquals(1, attemptCount)
            assertFalse(states.any { it is RefreshState.Stalled }, "success path must never stall")
            assertIs<RefreshState.Idle>(states.last())
        }

    // Cancellation while stalled stops the loop ─────────────────────────────────

    @Test
    fun cancellation_while_stalled_stops_the_retry_loop() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a refresh that never succeeds
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("[]")
            var attemptCount = 0
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = {
                            attemptCount++
                            throw RuntimeException("down")
                        },
                    )
                    .collect {}
            }
            advanceTimeBy(5_000)
            runCurrent()
            val attemptsAtCancel = attemptCount
            assertEquals(4, attemptsAtCancel)

            // When: the subscription is canceled while stalled
            collector.cancelAndJoin()
            advanceTimeBy(120_000)
            runCurrent()

            // Then: no further attempts run after cancellation
            assertEquals(attemptsAtCancel, attemptCount, "cancellation must stop the retry loop")
        }

    // An accelerator edge after success never re-runs the refresh ───────────────

    @Test
    fun a_wake_up_after_success_does_not_re_run_the_refresh() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a refresh that succeeds on the first attempt
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("[]")
            val wakeUps = MutableSharedFlow<Unit>()
            var attemptCount = 0
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = {
                            attemptCount++
                            cache.emit("fresh")
                        },
                        wakeUps = wakeUps,
                    )
                    .collect {}
            }
            runCurrent()
            assertEquals(1, attemptCount)

            // When: an accelerator edge fires after the refresh already succeeded
            wakeUps.emit(Unit)
            runCurrent()

            // Then: the loop already ended — no extra attempts
            assertEquals(1, attemptCount, "success ends the loop permanently")

            collector.cancelAndJoin()
        }

    @Test
    fun a_refresh_request_made_while_a_refresh_runs_runs_it_again_after() =
        runTest(UnconfinedTestDispatcher()) {
            // Given: a refresh held on the wire
            val cache = MutableSharedFlow<String>(replay = 1)
            cache.tryEmit("[]")
            val requests = MutableStateFlow(0L)
            val held = CompletableDeferred<Unit>()
            var attemptCount = 0
            val collector = launch {
                cacheFirstWithParallelRefresh(
                        cache = cache,
                        refresh = {
                            attemptCount++
                            if (attemptCount == 1) held.await()
                        },
                        refreshRequests = requests,
                    )
                    .collect {}
            }
            runCurrent()

            // When: a refresh is asked for while it runs, then it finishes
            requests.value = 1L
            runCurrent()
            held.complete(Unit)
            runCurrent()

            // Then: the request was not lost — the refresh ran again, once
            assertEquals(2, attemptCount)

            // When: asked again once the loop waits
            requests.value = 2L
            runCurrent()

            // Then: it runs again
            assertEquals(3, attemptCount)
            collector.cancelAndJoin()
        }

    // The cache's own failure reaches the collector intact ────────────────────

    @Test
    fun a_cache_that_fails_right_after_a_value_delivers_the_value_then_the_failure() = runTest {
        // The failure races the collector's taking of the value, so run it enough times to see
        // both orders of the two
        repeat(FAILURE_ORDERINGS) { attempt ->
            // Given: a cache that fails right after its first value, and a refresh that is done
            val values = mutableListOf<String>()

            // When
            val failure =
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        runCatching {
                            cacheFirstWithParallelRefresh(
                                    cache =
                                        flow {
                                            emit("cached")
                                            throw CacheFailed()
                                        },
                                    refresh = {},
                                )
                                .collect { value -> values += value }
                        }
                            .exceptionOrNull()
                    }
                }

            // Then: the value, then the failure itself
            assertEquals(
                listOf("cached"),
                values,
                "attempt $attempt: the value before the failure was dropped",
            )
            assertTrue(failure is CacheFailed, "attempt $attempt ended with $failure")
        }
    }

    @Test
    fun a_cache_that_gives_up_with_a_cancellation_ends_the_flow_with_it() = runTest {
        // Given: a cache that gives up with a cancellation of its own after a value, and a refresh
        // that is done, so nothing else would end the flow
        val values = mutableListOf<String>()

        // When
        val failure =
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    runCatching {
                        cacheFirstWithParallelRefresh(
                                cache =
                                    flow {
                                        emit("cached")
                                        throw CancellationException(CACHE_GAVE_UP)
                                    },
                                refresh = {},
                            )
                            .collect { value -> values += value }
                    }
                        .exceptionOrNull()
                }
            }

        // Then: the collector sees the cancellation, rather than waiting forever or a completion
        assertEquals(listOf("cached"), values)
        assertTrue(
            failure is CancellationException && failure.message == CACHE_GAVE_UP,
            "the flow ended with $failure",
        )
    }

    private class CacheFailed : RuntimeException("the cache read failed")

    private companion object {
        const val FAILURE_ORDERINGS = 500
        const val CACHE_GAVE_UP = "the cache read gave up"
    }
}
