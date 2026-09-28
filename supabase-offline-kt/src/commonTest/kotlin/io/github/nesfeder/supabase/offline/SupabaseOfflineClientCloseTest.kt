package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `close()` releases what the client holds: it stops the client's background work — without
 * touching the scope the caller handed in — runs the factory's release once, and refuses later use.
 */
class SupabaseOfflineClientCloseTest {

    private val callerJob = SupervisorJob()
    private val callerScope = CoroutineScope(callerJob + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        callerJob.cancel()
    }

    @Test
    fun close_stops_the_clients_background_work_and_ends_its_reactive_reads() = runTest {
        // Given — a live reactive read, whose shared refresh runs in the client's own scope
        var releases = 0
        val client = newTestOfflineClient(onClose = { releases++ }, coordinatorScope = callerScope)
        val observer = callerScope.launch { client.from("todos").selectAsFlow().collect { } }
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (callerJob.children.none { it !== observer && it.children.any() }) delay(5)
            }
        }

        // When
        client.close()
        client.close()

        // Then — the read ends, everything the client started is gone, the caller's own scope is
        // untouched, and the factory's release ran exactly once
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                observer.join()
                while (callerJob.children.any()) delay(5)
            }
        }
        assertTrue(callerJob.isActive, "close() cancelled the caller's scope")
        assertEquals(1, releases)
    }

    @Test
    fun a_closed_client_refuses_new_queries_and_buckets() = runTest {
        // Given
        val client = newTestOfflineClient(coordinatorScope = callerScope)

        // When
        client.close()

        // Then
        assertFailsWith<IllegalStateException> { client.from("todos") }
        assertFailsWith<IllegalStateException> { client.storage("avatars") }
    }
}
