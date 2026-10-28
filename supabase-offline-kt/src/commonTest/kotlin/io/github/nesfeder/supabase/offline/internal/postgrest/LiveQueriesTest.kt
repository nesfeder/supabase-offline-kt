// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.OfflineClientClosedException
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.RefreshState
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException

/**
 * The shared refresh flows: a failure in one reaches its collectors instead of the process, and a
 * signature nobody observes any more leaves nothing running or retained.
 *
 * The coordinator's coroutines run on a real dispatcher here, as they do in the client, so the
 * waits use real time rather than the test scheduler.
 */
@OptIn(ExperimentalAtomicApi::class)
class LiveQueriesTest {

    private val scopeJob = SupervisorJob()
    private val uncaught = AtomicInt(0)
    private val scope =
        CoroutineScope(
            scopeJob +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, _ -> uncaught.addAndFetch(1) }
        )

    @AfterTest
    fun tearDown() {
        scopeJob.cancel()
    }

    @Test
    fun a_failure_in_the_shared_upstream_reaches_its_collector_and_not_the_scope() = runTest {
        // Given — the cache read under a shared refresh fails, as a malformed database does
        val liveQueries = LiveQueries(scope)
        val shared =
            liveQueries.sharedReadFlow(TABLE, "todos?select=*") {
                flow<QuerySnapshot> { throw IOException("database disk image is malformed") }
            }

        // When
        val thrown =
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { assertFailsWith<IOException> { shared.first() } }
            }

        // Then — the collector hears about it; nothing escaped to the scope's handler
        assertEquals("database disk image is malformed", thrown.message)
        assertEquals(0, uncaught.load(), "the failure escaped the shared flow")
    }

    @Test
    fun a_shared_upstream_that_fails_right_after_a_value_delivers_the_value_then_the_failure() =
        runTest {
            // One thread gives the order that dropped the value every time; a pool gives the rest
            // of the orders
            val oneThread = Dispatchers.Default.limitedParallelism(1)
            repeat(FAILURE_ORDERINGS) { attempt ->
                // Given — a shared read whose upstream fails right after its first value
                val dispatcher = if (attempt % 2 == 0) oneThread else Dispatchers.Default
                val liveQueries = LiveQueries(CoroutineScope(scope.coroutineContext + dispatcher))
                val values = mutableListOf<QuerySnapshot>()

                // When
                val failure =
                    withContext(dispatcher) {
                        withTimeout(5.seconds) {
                            runCatching {
                                liveQueries
                                    .sharedReadFlow(TABLE, "todos?attempt=$attempt") {
                                        flow {
                                            emit(RESULT)
                                            throw IOException(MALFORMED)
                                        }
                                    }
                                    .collect { value -> values += value }
                            }
                                .exceptionOrNull()
                        }
                    }

                // Then — the value, then the failure itself
                assertEquals(
                    1,
                    values.size,
                    "attempt $attempt: the value before the failure was dropped",
                )
                assertIs<IOException>(failure, "attempt $attempt ended with $failure")
                assertEquals(MALFORMED, failure.message)
            }
        }

    @Test
    fun a_slow_collector_is_handed_the_latest_value_before_the_failure() = runTest {
        val oneThread = Dispatchers.Default.limitedParallelism(1)
        repeat(SLOW_COLLECTOR_ATTEMPTS) { attempt ->
            // Given — a shared read whose upstream sends two values and fails while its collector
            // is still busy with the first
            val dispatcher = if (attempt % 2 == 0) oneThread else Dispatchers.Default
            val liveQueries = LiveQueries(CoroutineScope(scope.coroutineContext + dispatcher))
            val seen = mutableListOf<RefreshState>()

            // When
            val failure =
                withContext(dispatcher) {
                    withTimeout(5.seconds) {
                        runCatching {
                            liveQueries
                                .sharedReadFlow(TABLE, "todos?slow=$attempt") {
                                    flow {
                                        emit(
                                            QuerySnapshot(
                                                result = null,
                                                isFromCache = true,
                                                fetchedAt = null,
                                                hasPendingMutations = false,
                                                refresh = RefreshState.Refreshing,
                                                pendingRowKeys = emptySet(),
                                            )
                                        )
                                        emit(
                                            QuerySnapshot(
                                                result = null,
                                                isFromCache = true,
                                                fetchedAt = null,
                                                hasPendingMutations = false,
                                                refresh = RefreshState.Idle,
                                                pendingRowKeys = emptySet(),
                                            )
                                        )
                                        throw IOException(MALFORMED)
                                    }
                                }
                                .collect { value ->
                                    seen += value.refresh
                                    delay(SLOW_COLLECTOR_MILLIS)
                                }
                        }
                            .exceptionOrNull()
                    }
                }

            // Then — the latest value it had not taken yet, then the failure itself
            assertEquals(RefreshState.Idle, seen.lastOrNull(), "attempt $attempt saw $seen")
            assertIs<IOException>(failure, "attempt $attempt ended with $failure")
            assertEquals(MALFORMED, failure.message)
        }
    }

    @Test
    fun a_shared_upstream_that_gives_up_with_a_cancellation_ends_the_collection_with_it() =
        runTest {
            // Given — a shared read whose upstream gives up with a cancellation of its own
            val liveQueries = LiveQueries(scope)
            val shared =
                liveQueries.sharedReadFlow(TABLE, "todos?select=*") {
                    flow<QuerySnapshot> { throw CancellationException(UPSTREAM_GAVE_UP) }
                }

            // When
            val failure =
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) { runCatching { shared.first() }.exceptionOrNull() }
                }

            // Then — the collector sees the cancellation, not "no element"
            assertIs<CancellationException>(failure, "the collection ended with $failure")
            assertEquals(UPSTREAM_GAVE_UP, failure.message)
        }

    @Test
    fun a_collection_running_when_the_clients_scope_ends_fails_with_the_closed_error() = runTest {
        // Given — a shared read that has delivered its first value and waits for more
        val liveQueries = LiveQueries(scope)
        val shared =
            liveQueries.sharedReadFlow(TABLE, "todos?select=*") {
                flow {
                    emit(RESULT)
                    awaitCancellation()
                }
            }
        val delivered = CompletableDeferred<Unit>()

        // When — the client's scope ends, as the client's close ends it
        val failure =
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    val collecting = async {
                        runCatching { shared.collect { delivered.complete(Unit) } }
                            .exceptionOrNull()
                    }
                    delivered.await()
                    scopeJob.cancel()
                    collecting.await()
                }
            }

        // Then — the collection fails with the closed error rather than waiting for good
        assertIs<OfflineClientClosedException>(failure, "the collection ended with $failure")
    }

    @Test
    fun a_later_subscriber_to_a_failed_query_gets_a_fresh_attempt() = runTest {
        // Given — the first attempt fails, the second would succeed
        val liveQueries = LiveQueries(scope)
        var attempts = 0
        val upstream = {
            flow {
                attempts++
                if (attempts == 1) throw IOException("transient")
                emit(RESULT)
                awaitCancellation()
            }
        }
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                assertFailsWith<IOException> {
                    liveQueries.sharedReadFlow(TABLE, "todos?select=*", upstream).first()
                }
            }
        }

        // When
        val second =
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    liveQueries.sharedReadFlow(TABLE, "todos?select=*", upstream).first()
                }
            }

        // Then
        assertEquals(RESULT, second)
        assertEquals(2, attempts)
    }

    @Test
    fun a_query_nobody_observes_leaves_nothing_running_or_retained() = runTest {
        // Given — search-as-you-type: many distinct signatures, each observed briefly
        val liveQueries = LiveQueries(scope)

        // When
        repeat(CACHE_KEYS) { index ->
            val shared =
                liveQueries.sharedReadFlow(TABLE, "todos?title=ilike.*$index*") {
                    flow {
                        emit(RESULT)
                        awaitCancellation()
                    }
                }
            withContext(Dispatchers.Default) { withTimeout(5.seconds) { shared.first() } }
        }

        // Then — no sharing coroutine is left in the scope (each would hold its first subscriber's
        // request lambda, and whatever that lambda captured, for the life of the process)
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (scopeJob.children.any()) delay(10)
            }
        }
        assertEquals(0, scopeJob.children.count())
    }

    @Test
    fun the_shared_registry_empties_under_concurrent_joins_and_leaves() = runTest {
        // Given
        val liveQueries = LiveQueries(scope)
        val upstream = {
            flow {
                emit(RESULT)
                awaitCancellation()
            }
        }

        // When — 400 collectors on 4 signatures join and leave concurrently, each followed by a
        // one-shot reader racing the removal of the entry it just left
        withContext(Dispatchers.Default) {
            val collectors =
                List(400) { index ->
                    launch {
                        delay(Random.nextLong(0, 5))
                        val cacheKey = "$TABLE?sig=${index % 4}"
                        val collector = launch {
                            liveQueries.sharedReadFlow(TABLE, cacheKey, upstream).collect {}
                        }
                        delay(Random.nextLong(0, 5))
                        collector.cancelAndJoin()
                        withTimeout(5.seconds) {
                            liveQueries.sharedReadFlow(TABLE, cacheKey, upstream).first()
                        }
                    }
                }
            collectors.forEach { it.join() }
            withTimeout(5.seconds) { while (scopeJob.children.any()) delay(10) }
        }

        // Then — no sharing coroutine is left behind, and none failed
        assertEquals(0, scopeJob.children.count())
        assertEquals(0, uncaught.load())
    }

    private companion object {
        const val TABLE = "todos"
        const val CACHE_KEYS = 20
        val RESULT =
            QuerySnapshot(
                result = null,
                isFromCache = true,
                fetchedAt = null,
                hasPendingMutations = false,
                refresh = RefreshState.Idle,
                pendingRowKeys = emptySet(),
            )
        const val FAILURE_ORDERINGS = 500
        const val MALFORMED = "database disk image is malformed"
        const val UPSTREAM_GAVE_UP = "the shared upstream gave up"
        const val SLOW_COLLECTOR_ATTEMPTS = 20

        /** How long the slow collector takes over each value: long past the upstream's failure. */
        const val SLOW_COLLECTOR_MILLIS = 20L
    }
}
