// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A read the cache cannot answer, made while the connectivity monitor says the network is down, is
 * still sent, once: supabase-kt's retries, which wait out seconds before a transport failure, could
 * only delay the failure the app is handed.
 */
class ReadsWhileKnownOfflineTest {

    /** A server no request reaches, under supabase-kt's own default retries. */
    private val server = RecordingPostgrestServer(answer = { connectivityFailure() })
    private val client =
        newTestOfflineClient(
            supabase = newTestSupabaseClient(engine = server.engine),
            networkAvailability = flowOf(false),
        )

    @Test
    fun a_cold_network_first_read_is_sent_once() =
        runTest(timeout = 2.minutes) {
            assertSentOnceAndFailedAtTheTransport(ReadStrategy.NetworkFirst())
        }

    @Test
    fun a_cold_cache_first_read_is_sent_once() =
        runTest(timeout = 2.minutes) {
            assertSentOnceAndFailedAtTheTransport(ReadStrategy.CacheFirst())
        }

    @Test
    fun a_network_only_read_is_sent_once() =
        runTest(timeout = 2.minutes) {
            assertSentOnceAndFailedAtTheTransport(ReadStrategy.NetworkOnly)
        }

    private suspend fun assertSentOnceAndFailedAtTheTransport(strategy: ReadStrategy) {
        withContext(Dispatchers.Default) {
            // Given — the monitor has said the network is down; nothing is cached
            withTimeout(10.seconds) {
                client.syncStatus.first { status -> status.network == NetworkStatus.OFFLINE }
            }

            // When
            val failure =
                withTimeout(30.seconds) {
                        runCatching { client.from(TABLE).select(readStrategy = strategy) }
                    }
                    .exceptionOrNull()

            // Then — sent once, and the transport's failure is what the app is handed
            assertEquals(1, server.requests.size, "$strategy")
            assertTrue(assertNotNull(failure).isNetworkOrConnectivityFailure(), "$failure")
        }
        client.close()
    }

    private companion object {
        const val TABLE = "items"
    }
}
