package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.RefreshState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [cacheFirstWithParallelRefresh] — the self-healing combinator that backs
 * [QueryReader]. These lock in the contract the design depends on: cache forwards
 * without waiting on the network, the refresh survives exhaustion and keeps retrying on a capped
 * schedule, an accelerator edge short-circuits the current backoff, the first success ends the
 * loop, and cancellation propagates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CacheFirstWithParallelRefreshTest {

    // Cache-first emits before the network completes ──────────────────────────

    @Test
    fun cache_emits_before_refresh_completes() = runTest(UnconfinedTestDispatcher()) {
        // Given: a cache with a value ready immediately, and a refresh that never completes
        val cache = MutableSharedFlow<String>(replay = 1)
        cache.tryEmit("cached-X")
        val refreshBlocker = CompletableDeferred<Unit>()
        var refreshCalled = false

        // When: we collect the first emission
        val first = cacheFirstWithParallelRefresh(
            cache = cache,
            refresh = {
                refreshCalled = true
                refreshBlocker.await()
            },
        ).first()

        // Then: the cached value arrived and refresh was started in parallel, not blocking it
        assertEquals("cached-X", first)
        assertTrue(refreshCalled, "refresh should be launched in parallel")
        assertFalse(refreshBlocker.isCompleted, "refresh must still be in flight")

        refreshBlocker.complete(Unit)
    }

    // Refresh-side update propagates downstream ────────────────────────────────

    @Test
    fun refresh_side_update_reaches_observer() = runTest(UnconfinedTestDispatcher()) {
        // Given: a cache that starts with X; refresh writes Y into the cache
        val cache = MutableSharedFlow<String>(replay = 1)
        cache.tryEmit("X")
        val emissions = mutableListOf<String>()
        val collector = launch {
            cacheFirstWithParallelRefresh(
                cache = cache,
                refresh = { cache.emit("Y") },
            ).take(2).toList(emissions)
        }

        // When: both values are collected
        collector.join()

        // Then: X (cache) then Y (refresh-triggered cache update)
        assertEquals(listOf("X", "Y"), emissions)
    }

    // Refresh failure does not terminate the flow ──────────────────────────────

    @Test
    fun refresh_failure_does_not_terminate_flow() = runTest(UnconfinedTestDispatcher()) {
        // Given: a cache that starts with X; refresh throws
        val cache = MutableSharedFlow<String>(replay = 1)
        cache.tryEmit("X")
        val emissions = mutableListOf<String>()
        val collector = launch {
            cacheFirstWithParallelRefresh(
                cache = cache,
                refresh = { throw RuntimeException("network exploded") },
            ).take(2).toList(emissions)
        }
        yield()

        // When: a later cache emission is made
        cache.emit("Z")
        collector.join()

        // Then: both X and Z arrived — the refresh failure was tolerated and the cache still flows
        assertEquals(listOf("X", "Z"), emissions)
    }

    // Transient failure retries and recovers ───────────────────────────────────

    @Test
    fun refresh_retries_after_transient_failure_and_writes_to_cache() = runTest(UnconfinedTestDispatcher()) {
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
                    if (attemptCount == 1) throw UntrustedResponseException(table = "ratings")
                    cache.emit("""[{"id":1}]""")
                },
            ).take(2).toList(emissions)
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
    fun exhausted_fast_ladder_keeps_flow_alive_and_reports_stalled() = runTest(UnconfinedTestDispatcher()) {
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
            ).toList(emissions)
        }

        // When: the fast ladder (4 attempts across 300 + 900 + 2400ms) elapses
        advanceTimeBy(5_000)
        runCurrent()

        // Then: exactly 4 attempts spent, Stalled reported once, and the flow is still alive
        assertEquals(4, attemptCount, "fast ladder is one initial attempt plus three retries")
        assertTrue(states.any { it is RefreshState.Stalled }, "fast-ladder exhaustion must report Stalled")
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
    fun accelerator_edge_triggers_one_immediate_retry_per_edge() = runTest(UnconfinedTestDispatcher()) {
        // Given: a refresh that fails until told otherwise, and an accelerator we control
        val cache = MutableSharedFlow<String>(replay = 1)
        cache.tryEmit("[]")
        val accelerator = MutableSharedFlow<Unit>()
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
                accelerator = accelerator,
            ).collect { }
        }

        // First attempt fails immediately, then it is sleeping out the 300ms backoff
        runCurrent()
        assertEquals(1, attemptCount)

        // When: an accelerator edge lands well before the 300ms delay elapses
        advanceTimeBy(50)
        failing = false
        accelerator.emit(Unit)
        runCurrent()

        // Then: exactly one immediate retry ran (not waiting out the backoff), and it succeeded
        assertEquals(2, attemptCount, "an accelerator edge triggers exactly one immediate retry")

        collector.cancelAndJoin()
    }

    // State sequence: InFlight → Stalled → Idle on late success ─────────────────

    @Test
    fun reports_in_flight_then_stalled_then_idle_on_late_success() = runTest(UnconfinedTestDispatcher()) {
        // Given: a refresh failing through the whole fast ladder, recovered by an accelerator edge
        val cache = MutableSharedFlow<String>(replay = 1)
        cache.tryEmit("[]")
        val accelerator = MutableSharedFlow<Unit>()
        val states = mutableListOf<RefreshState>()
        var failing = true
        val collector = launch {
            cacheFirstWithParallelRefresh(
                cache = cache,
                refresh = {
                    if (failing) throw RuntimeException("boom")
                    cache.emit("fresh")
                },
                accelerator = accelerator,
                onRefreshState = { state -> states += state },
            ).collect { }
        }

        // When: the fast ladder exhausts, then the network heals and an edge fires
        advanceTimeBy(5_000)
        runCurrent()
        assertIs<RefreshState.InFlight>(states.first())
        assertTrue(states.any { it is RefreshState.Stalled })
        failing = false
        accelerator.emit(Unit)
        runCurrent()

        // Then: the terminal state is Idle (success ends the loop)
        assertIs<RefreshState.Idle>(states.last())

        collector.cancelAndJoin()
    }

    // First-try success reports Idle and never stalls ──────────────────────────

    @Test
    fun first_try_success_reports_idle_without_stall() = runTest(UnconfinedTestDispatcher()) {
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
            ).take(2).toList()
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
    fun cancellation_while_stalled_stops_the_retry_loop() = runTest(UnconfinedTestDispatcher()) {
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
            ).collect { }
        }
        advanceTimeBy(5_000)
        runCurrent()
        val attemptsAtCancel = attemptCount
        assertEquals(4, attemptsAtCancel)

        // When: the subscription is cancelled while stalled
        collector.cancelAndJoin()
        advanceTimeBy(120_000)
        runCurrent()

        // Then: no further attempts run after cancellation
        assertEquals(attemptsAtCancel, attemptCount, "cancellation must stop the retry loop")
    }

    // An accelerator edge after success never re-runs the refresh ───────────────

    @Test
    fun accelerator_edge_after_success_does_not_re_run_refresh() = runTest(UnconfinedTestDispatcher()) {
        // Given: a refresh that succeeds on the first attempt
        val cache = MutableSharedFlow<String>(replay = 1)
        cache.tryEmit("[]")
        val accelerator = MutableSharedFlow<Unit>()
        var attemptCount = 0
        val collector = launch {
            cacheFirstWithParallelRefresh(
                cache = cache,
                refresh = {
                    attemptCount++
                    cache.emit("fresh")
                },
                accelerator = accelerator,
            ).collect { }
        }
        runCurrent()
        assertEquals(1, attemptCount)

        // When: an accelerator edge fires after the refresh already succeeded
        accelerator.emit(Unit)
        runCurrent()

        // Then: the loop already ended — no extra attempts
        assertEquals(1, attemptCount, "success ends the loop permanently")

        collector.cancelAndJoin()
    }
}
