// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.onRealThreads
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.seededServer
import io.github.nesfeder.supabase.offline.internal.db.RecordedLiveList
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A live read open through [Offline.clearLocalData] fetches again, as it does when the cache passes
 * to another identity: from the reader's side the two are the same event.
 */
class LiveReadsAfterClearTest {

    private val server = seededServer("a" to "Milk")
    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            nowMillis = { Clock.System.now().toEpochMilliseconds() },
        )

    @Test
    fun a_live_list_fetches_again_after_local_data_is_cleared_online() =
        runTest(timeout = 2.minutes) {
            onRealThreads {
                // Given — a live list showing the server's rows, then a row only the server has
                val list = RecordedLiveList(client, LiveReadConsistency.TABLE)
                list.await { seen -> seen.titles == listOf("Milk") && !seen.isFromCache }
                server.seed(LiveReadConsistency.TABLE, row("b", "Eggs"))

                // When — local data is cleared
                client.clearLocalData()

                // Then — the list asks the server again and shows both rows
                list.await { seen -> seen.titles == listOf("Milk", "Eggs") && !seen.isFromCache }
                list.stop()
            }
            client.close()
        }

    @Test
    fun a_live_list_cleared_offline_waits_paused_and_fetches_once_the_network_is_back() =
        runTest(timeout = 2.minutes) {
            onRealThreads {
                // Given — a live list showing the server's rows, the network switched off
                val list = RecordedLiveList(client, LiveReadConsistency.TABLE)
                list.await { seen -> seen.titles == listOf("Milk") && !seen.isFromCache }
                client.disableNetwork()

                // When — local data is cleared, then the network comes back
                client.clearLocalData()
                list.await { seen -> seen.titles == null && seen.refresh == RefreshState.Paused }
                val paused = list.count
                client.enableNetwork()

                // Then — the list fetches again
                list.await(from = paused) { seen ->
                    seen.titles == listOf("Milk") && !seen.isFromCache
                }
                list.stop()
            }
            client.close()
        }

    @Test
    fun a_live_list_fetches_again_after_a_clear_cut_short_while_a_read_holds_the_log() =
        runTest(timeout = 2.minutes) {
            // Given — a client whose clear is cancelled while it rewrites the database, with an
            // open read holding the log, so the rewrite leaves work for later
            val heldServer = seededServer("a" to "Milk")
            val clear = CompletableDeferred<Job>()
            val heldClient =
                newTestOfflineClient(
                    supabase = heldServer.supabaseClient(),
                    nowMillis = { Clock.System.now().toEpochMilliseconds() },
                    scrubDatabase = {
                        clear.await().cancel()
                        false
                    },
                    emptyWriteAheadLog = { false },
                )
            onRealThreads {
                // And — a live list showing the server's rows, then a row only the server has
                val list = RecordedLiveList(heldClient, LiveReadConsistency.TABLE)
                list.await { seen -> seen.titles == listOf("Milk") && !seen.isFromCache }
                heldServer.seed(LiveReadConsistency.TABLE, row("b", "Eggs"))

                // When — local data is cleared, and the clear's caller is cancelled
                val job =
                    launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        heldClient.clearLocalData()
                    }
                clear.complete(job)
                job.start()
                job.join()

                // Then — the clear was cut short, and the list still asks the server again
                assertTrue(job.isCancelled)
                list.await { seen -> seen.titles == listOf("Milk", "Eggs") && !seen.isFromCache }
                list.stop()
            }
            heldClient.close()
            client.close()
        }

    private fun row(id: String, title: String) = buildJsonObject {
        put("id", id)
        put("title", title)
    }
}
