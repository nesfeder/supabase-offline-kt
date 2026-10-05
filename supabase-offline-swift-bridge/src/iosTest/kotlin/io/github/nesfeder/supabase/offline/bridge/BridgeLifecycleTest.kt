// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.bridge

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSUUID

/** Opening and closing over a real database, and where answers arrive. */
class BridgeLifecycleTest {

    @Test
    fun a_close_canceled_at_once_still_closes_and_frees_the_database() = bridgeTest {
        // Given — a client over a database on disk
        val directory = NSTemporaryDirectory() + "bridge-close-" + NSUUID().UUIDString()
        try {
            repeat(4) {
                val client = openOnDisk(directory)

                // When — the close's handle is canceled straight away, then it closes again
                val closing = CompletionProbe<Unit>()
                client.close(closing.errorCompletion).cancel()
                val first = closing.await()
                val second = awaitErrorOnly { client.close(it) }

                // Then — both answered, once, and the database is free for the next round
                assertNull(first.second)
                assertNull(second)
                assertEquals(1, closing.callCount)
            }
            val last = openOnDisk(directory)
            last.closeAndWait()
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
        }
    }

    @Test
    fun an_answer_known_at_once_never_runs_inside_the_call_that_takes_it() = bridgeTest {
        // Given — a closed client, whose answer to any call is known before the call returns
        val client = openTestClient(RecordingServer())
        client.closeAndWait()

        // When — the completion records whether it runs inside the call: on the caller's thread
        // before the call has returned. It may run elsewhere before the return, or on the
        // caller's thread once the caller has moved on, and both are fine.
        val callerThread = NSThread.currentThread
        val returned = AtomicBoolean(false)
        var ranInsideTheCall: Boolean? = null
        val probe = CompletionProbe<BridgeQueryResult>()
        client.select(readOf()) { value, error ->
            ranInsideTheCall = NSThread.currentThread == callerThread && !returned.load()
            probe.completion(value, error)
        }
        returned.store(true)
        val error = probe.awaitError()

        // Then
        assertEquals(BridgeErrorKind.CLIENT_CLOSED, error.kind)
        assertEquals(false, ranInsideTheCall)
    }

    @Test
    fun a_second_client_on_the_same_directory_is_refused_until_the_first_closes() = bridgeTest {
        // Given — one client on a directory
        val directory = NSTemporaryDirectory() + "bridge-claim-" + NSUUID().UUIDString()
        try {
            val first = openOnDisk(directory)

            // When
            val second = BridgeClient.open(diskOptions(directory))
            first.closeAndWait()
            val third = openOnDisk(directory)

            // Then
            assertNull(second.client)
            assertNotNull(second.error)
            assertFalse(second.error?.message.isNullOrBlank())
            third.closeAndWait()
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
        }
    }

    /** Options for a real client in [directory], which sends nothing in these tests. */
    private fun diskOptions(directory: String): BridgeClientOptions =
        BridgeClientOptions(
            supabaseUrl = TEST_URL,
            supabaseKey = "fixture-anon-key",
            dataDirectory = directory,
        )

    /** A real client over a database in [directory], failing the test when it does not open. */
    private fun openOnDisk(directory: String): BridgeClient {
        val opened = BridgeClient.open(diskOptions(directory))
        return checkNotNull(opened.client) { "the client did not open: ${opened.error}" }
    }
}
