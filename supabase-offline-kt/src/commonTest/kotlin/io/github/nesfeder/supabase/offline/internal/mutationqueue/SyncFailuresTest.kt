// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.SyncStopReason
import io.ktor.http.IllegalHeaderValueException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a stopped pass reports as the sync status's last error, never the failure's own text, and
 * the reason the app's report gives for it.
 */
class SyncFailuresTest {

    @Test
    fun a_header_the_http_client_refuses_is_named_without_its_value_or_its_class() {
        // Given — Ktor's refusal of a token ending in a line break, which quotes the whole header
        val header = "Bearer eyJhbGciOi.secret-token-tail\n"
        val refusal = IllegalHeaderValueException(header, header.length - 1)

        // When — reached directly, and wrapped in a cause as a request may wrap it
        val direct = syncFailureOf(refusal)
        val wrapped = syncFailureOf(IllegalStateException("request failed", refusal))

        // Then
        for (failure in listOf(direct, wrapped)) {
            val message = failure.message.orEmpty()
            assertTrue(message.startsWith("A request header holds a character"), message)
            assertFalse(message.contains("Exception"), message)
            assertFalse(message.contains("Bearer"), message)
            assertFalse(message.contains("eyJ") || message.contains("secret"), message)
            assertNull(failure.httpStatus)
            assertNull(failure.code)
        }
        assertEquals(direct.message, wrapped.message)
    }

    @Test
    fun a_failure_on_the_device_is_named_in_a_fixed_sentence_without_its_class_or_its_text() {
        // Given — failures that are neither the server's nor the network's
        val failures =
            listOf(
                IllegalStateException("row {\"email\":\"someone@example.com\"}"),
                UnsupportedOperationException("token eyJhbGciOi.secret-token-tail"),
            )

        // When
        val reported = failures.map(::syncFailureOf)

        // Then — one sentence for all of them, naming no class and quoting nothing
        for (failure in reported) {
            assertEquals(
                "A failure on this device stopped the sync pass; " +
                    "the queued writes stay queued for a later pass",
                failure.message,
            )
            assertNull(failure.httpStatus)
            assertNull(failure.code)
        }
    }

    @Test
    fun the_stops_no_call_reaches_alone_are_shown_as_the_reason_they_mean() {
        // Given — a refusal held behind an earlier write, and a header the HTTP client refuses
        val refusal =
            SyncFailedException(
                message = "refused",
                httpStatus = 409,
                code = null,
                isConnectivityFailure = false,
            )
        val header = "Bearer eyJhbGciOi.secret-token-tail\n"
        val badHeader = IllegalHeaderValueException(header, header.length - 1)

        // When
        val held = stopReasonOf(SyncStop.HeldBehindEarlierMutations(mutationId = 7, refusal))
        val refusedHeader = stopReasonOf(SyncStop.TransientFailure(badHeader))

        // Then — a pass that could not tell the user waited for the session, as one whose session
        // did not settle; another sender's hold never ends a call, and nothing failed by it; the
        // held refusal is the server's, and the header the device's
        assertEquals(SyncStopReason.SessionNotReady, stopReasonOf(SyncStop.IdentityUnknown))
        assertEquals(SyncStopReason.Completed, stopReasonOf(SyncStop.HeadInFlight))
        assertEquals(409, assertIs<SyncStopReason.ServerFailure>(held).error.httpStatus)
        val local = assertIs<SyncStopReason.LocalFailure>(refusedHeader)
        assertTrue(local.error.message.orEmpty().startsWith("A request header holds"))
    }
}
