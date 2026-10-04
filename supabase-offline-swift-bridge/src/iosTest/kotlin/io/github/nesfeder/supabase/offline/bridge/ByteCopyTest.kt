// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.bridge.internal.byteArraySizeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Bytes cross as `NSData` and are copied into a Kotlin array, which holds at most [Int.MAX_VALUE]
 * bytes: a larger body is refused before anything is allocated or copied, on every path.
 */
class ByteCopyTest {

    private val server = RecordingServer()

    @Test
    fun a_length_beyond_an_arrays_reach_is_refused_and_one_within_it_is_kept() {
        // Given — lengths at and around the bound, the 4 GiB ones truncating to small numbers
        val fourGiB = 4UL * 1024UL * 1024UL * 1024UL

        // Then
        assertEquals(Int.MAX_VALUE, byteArraySizeOf(Int.MAX_VALUE.toULong()))
        assertEquals(0, byteArraySizeOf(0UL))
        for (length in listOf(Int.MAX_VALUE.toULong() + 1UL, fourGiB, fourGiB + 16UL)) {
            assertFailsWith<IllegalArgumentException> { byteArraySizeOf(length) }
        }
    }

    @Test
    fun an_upload_too_large_to_copy_is_an_invalid_argument_and_sends_nothing() = bridgeTest {
        // Given — data that claims 4 GiB and 16 bytes
        val client = openTestClient(server)
        val oversized = FakeOversizedData(4UL * 1024UL * 1024UL * 1024UL + 16UL)

        // When
        val probe = CompletionProbe<String>()
        client.storage.upload("avatars", "a.png", oversized, null, probe.completion)
        val error = probe.awaitError()

        // Then
        assertEquals(BridgeErrorKind.INVALID_ARGUMENT, error.kind)
        assertTrue(error.message.contains("4294967312"))
        assertTrue(server.requests.isEmpty())
        client.closeAndWait()
    }

    @Test
    fun a_fake_servers_body_too_large_to_copy_fails_the_request_and_the_process_lives() =
        bridgeTest {
            // Given — a fake server answering with data that claims 3 GiB
            val client = openTestClient(server)
            server.answer = { null }
            val probe = CompletionProbe<BridgeQueryResult>()
            client.select(readOf(strategy = "networkOnly"), probe.completion)
            awaitUntil { server.heldResponders.isNotEmpty() }

            // When
            server.heldResponders
                .single()
                .respond(200, emptyMap(), FakeOversizedData(3UL * 1024UL * 1024UL * 1024UL))
            val error = probe.awaitError()

            // Then — the request failed as a transport failure naming the size, and the client
            // still reads
            assertEquals(BridgeErrorKind.CONNECTIVITY, error.kind)
            assertTrue(error.message.contains("3221225472"))
            server.answer = RecordingServer::defaultAnswer
            assertEquals(SERVER_ROWS, client.selectAndWait(readOf()).first?.data)
            client.closeAndWait()
        }
}
