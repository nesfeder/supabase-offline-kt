// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.IOException
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSUnderlyingErrorKey

/**
 * On iOS the Darwin engine's failure carries its `NSError` in its message: the codes for no
 * connection, no host and a failed lookup prove a request never left the device; the rest may not.
 * Only the top-level error counts, and only in the message the engine itself writes.
 */
class UndeliveredRequestsIosTest {

    @Test
    fun url_errors_before_connecting_prove_the_request_was_never_sent() {
        listOf(-1003L, -1004L, -1006L, -1009L, -1018L, -1020L).forEach { code ->
            assertTrue(darwinFailure(urlError(code)).provesRequestNeverSent(), "code $code")
        }
    }

    @Test
    fun url_errors_after_connecting_do_not() {
        listOf(-1001L, -1005L, -1017L).forEach { code ->
            assertFalse(darwinFailure(urlError(code)).provesRequestNeverSent(), "code $code")
        }
    }

    @Test
    fun a_never_sent_code_underneath_another_error_does_not() {
        val offline = urlError(-1009L)
        listOf(
                NSError.errorWithDomain(
                    NSURLErrorDomain,
                    -1005L,
                    mapOf<Any?, Any?>(NSUnderlyingErrorKey to offline),
                ),
                NSError.errorWithDomain(
                    "kCFErrorDomainCFNetwork",
                    -1005L,
                    mapOf<Any?, Any?>(NSUnderlyingErrorKey to offline),
                ),
            )
            .forEach { error ->
                assertFalse(darwinFailure(error).provesRequestNeverSent(), "$error")
            }
    }

    @Test
    fun the_code_counts_only_in_the_engine_s_own_message() {
        assertFalse(IOException("Request failed: ${urlError(-1009L)}").provesRequestNeverSent())
    }

    private fun urlError(code: Long): NSError =
        NSError.errorWithDomain(
            NSURLErrorDomain,
            code,
            mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "Localized text, any language"),
        )

    /** The failure as the Darwin engine raises it: its message is the `NSError`'s description. */
    private fun darwinFailure(error: NSError): IOException =
        IOException("Exception in http request: $error")
}
