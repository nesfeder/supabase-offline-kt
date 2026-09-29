package io.github.nesfeder.supabase.offline.internal.util

import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On iOS the Darwin engine's failure carries its `NSError` in its message: the codes for no
 * connection, no host and a failed lookup prove a request never left the device; the rest may not.
 */
class UndeliveredRequestsIosTest {

    @Test
    fun url_errors_before_connecting_prove_the_request_was_never_sent() {
        listOf(-1003, -1004, -1006, -1009, -1018, -1020).forEach { code ->
            assertTrue(darwinFailure(code).provesRequestNeverSent(), "code $code")
        }
    }

    @Test
    fun url_errors_after_connecting_do_not() {
        listOf(-1001, -1005, -1017).forEach { code ->
            assertFalse(darwinFailure(code).provesRequestNeverSent(), "code $code")
        }
    }

    private fun darwinFailure(code: Int): IOException =
        IOException("Exception in http request: Error Domain=NSURLErrorDomain Code=$code \"The request failed.\" UserInfo={}")
}
