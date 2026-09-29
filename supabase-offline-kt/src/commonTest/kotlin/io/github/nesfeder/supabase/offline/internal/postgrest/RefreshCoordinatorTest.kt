package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.OfflineReadResult
import io.github.nesfeder.supabase.offline.RefreshState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * The shared refresh flows: a failure in one reaches its collectors instead of the process, and a
 * signature nobody observes any more leaves nothing running or retained.
 *
 * The coordinator's coroutines run on a real dispatcher here, as they do in the client, so the
 * waits use real time rather than the test scheduler.
 */
@OptIn(ExperimentalAtomicApi::class)
class RefreshCoordinatorTest {

    private val scopeJob = SupervisorJob()
    private val uncaught = AtomicInt(0)
    private val scope = CoroutineScope(
        scopeJob + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> uncaught.addAndFetch(1) },
    )

    @AfterTest
    fun tearDown() {
        scopeJob.cancel()
    }

    @Test
    fun a_failure_in_the_shared_upstream_reaches_its_collector_and_not_the_scope() = runTest {
        // Given — the cache read under a shared refresh fails, as a malformed database does
        val coordinator = RefreshCoordinator(scope)
        val shared = coordinator.sharedReadFlow(TABLE, "todos?select=*") {
            flow<OfflineReadResult> { throw IOException("database disk image is malformed") }
        }

        // When
        val thrown = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { assertFailsWith<IOException> { shared.first() } }
        }

        // Then — the collector hears about it; nothing escaped to the scope's handler
        assertEquals("database disk image is malformed", thrown.message)
        assertEquals(0, uncaught.load(), "the failure escaped the shared flow")
    }

    @Test
    fun a_later_subscriber_to_a_failed_signature_gets_a_fresh_attempt() = runTest {
        // Given — the first attempt fails, the second would succeed
        val coordinator = RefreshCoordinator(scope)
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
                assertFailsWith<IOException> { coordinator.sharedReadFlow(TABLE, "todos?select=*", upstream).first() }
            }
        }

        // When
        val second = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { coordinator.sharedReadFlow(TABLE, "todos?select=*", upstream).first() }
        }

        // Then
        assertEquals(RESULT, second)
        assertEquals(2, attempts)
    }

    @Test
    fun a_signature_nobody_observes_leaves_nothing_running_or_retained() = runTest {
        // Given — search-as-you-type: many distinct signatures, each observed briefly
        val coordinator = RefreshCoordinator(scope)

        // When
        repeat(SIGNATURES) { index ->
            val shared = coordinator.sharedReadFlow(TABLE, "todos?title=ilike.*$index*") {
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
        val coordinator = RefreshCoordinator(scope)
        val upstream = {
            flow {
                emit(RESULT)
                awaitCancellation()
            }
        }

        // When — 400 collectors on 4 signatures join and leave concurrently, each followed by a
        // one-shot reader racing the removal of the entry it just left
        withContext(Dispatchers.Default) {
            val collectors = List(400) { index ->
                launch {
                    delay(Random.nextLong(0, 5))
                    val signature = "$TABLE?sig=${index % 4}"
                    val collector = launch { coordinator.sharedReadFlow(TABLE, signature, upstream).collect { } }
                    delay(Random.nextLong(0, 5))
                    collector.cancelAndJoin()
                    withTimeout(5.seconds) { coordinator.sharedReadFlow(TABLE, signature, upstream).first() }
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
        const val SIGNATURES = 20
        val RESULT = OfflineReadResult(result = null, refresh = RefreshState.Idle)
    }
}
