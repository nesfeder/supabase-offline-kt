// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.onRealThreads
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.seededServer
import io.github.nesfeder.supabase.offline.internal.db.RecordedLiveList
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A live read open through [Offline.clearLocalData] fetches again, as it does when the cache
 * passes to another identity: from the reader's side the two are the same event.
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

    private fun row(id: String, title: String) = buildJsonObject {
        put("id", id)
        put("title", title)
    }
}
