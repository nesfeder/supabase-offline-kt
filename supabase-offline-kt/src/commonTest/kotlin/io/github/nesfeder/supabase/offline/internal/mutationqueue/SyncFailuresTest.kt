// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.ktor.http.IllegalHeaderValueException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a stopped pass reports as the sync status's last error: never the failure's own text. */
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
}
