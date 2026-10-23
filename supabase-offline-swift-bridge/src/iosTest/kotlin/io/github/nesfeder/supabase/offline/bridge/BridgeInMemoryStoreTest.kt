// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An in-memory store the app hands to each client it opens: the next client over it finds what the
 * one before left, and two clients never hold it at once.
 */
class BridgeInMemoryStoreTest {

    private val server = RecordingServer()

    @Test
    fun a_write_queued_before_close_is_sent_by_the_next_client_over_the_store() = bridgeTest {
        // Given — a client over a store that queues a write offline, then closes
        val store = BridgeInMemoryStore()
        val first = openTestClient(server, store = store) { autoSync = false }
        assertNull(awaitErrorOnly { first.disableNetwork(it) })
        val (outcome, _) = first.mutateAndWait(INSERT_TODO, """{"id":2,"title":"Tea"}""")
        first.closeAndWait()

        // When — the next client opens over the store and syncs
        val second = openTestClient(server, store = store) { autoSync = false }
        val counted = CompletionProbe<Int>()
        second.pendingMutationCount(null, counted.completion)
        val synced = CompletionProbe<BridgeSyncReport>()
        second.syncPendingMutations(null, synced.completion)

        // Then — it found the write, and sent it
        assertEquals(true, outcome?.isQueued)
        assertEquals(1, counted.awaitValue())
        assertEquals(1, assertNotNull(synced.awaitValue()).syncedCount)
        assertEquals(1, server.requests.count { it.method == "POST" })
        second.closeAndWait()
    }

    @Test
    fun a_client_over_a_store_another_holds_fails_to_open_until_that_one_closes() = bridgeTest {
        // Given — one client open over the store
        val store = BridgeInMemoryStore()
        val first = openTestClient(server, store = store)

        // When — another is opened over it, then again once the first closed
        val refused = BridgeTesting.openInMemory(testOptions(), server, null, null, store)
        first.closeAndWait()
        val next = BridgeTesting.openInMemory(testOptions(), server, null, null, store)

        // Then — the refusal says why, and the close freed the store
        assertNull(refused.client)
        val error = assertNotNull(refused.error)
        assertTrue("already open" in error.message, error.message)
        assertNotNull(next.client, "${next.error}").closeAndWait()
    }
}
