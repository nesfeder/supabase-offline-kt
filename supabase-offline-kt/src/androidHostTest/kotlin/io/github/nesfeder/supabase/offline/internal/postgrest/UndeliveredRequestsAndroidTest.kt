// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.IOException

/**
 * On the JVM a failed connection or host lookup proves a request never reached the server; a read
 * timeout or a reset may come after the server received it. An earlier attempt the engine carries
 * as a suppressed exception must prove it too.
 */
class UndeliveredRequestsAndroidTest {

    @Test
    fun connect_and_lookup_failures_prove_the_request_was_never_sent() {
        listOf(
                ConnectException("Connection refused"),
                UnknownHostException("unit-test.supabase.co"),
                NoRouteToHostException("No route to host"),
                UnresolvedAddressException(),
                ConnectTimeoutException("connect timed out", null),
                IOException("wrapped", ConnectException("Connection refused")),
                ConnectException("Failed to connect").apply {
                    addSuppressed(ConnectException("Connection refused"))
                },
            )
            .forEach { failure -> assertTrue(failure.provesRequestNeverSent(), "$failure") }
    }

    @Test
    fun failures_after_connecting_do_not() {
        listOf(
                SocketTimeoutException("read timed out", null),
                IOException("Connection reset"),
            )
            .forEach { failure -> assertFalse(failure.provesRequestNeverSent(), "$failure") }
    }

    @Test
    fun a_connect_failure_carrying_an_attempt_that_got_through_does_not() {
        // OkHttp retries a request whose connection failed after it was written, and throws the
        // retry's connect failure with the earlier attempt suppressed
        val retried =
            ConnectException("Failed to connect").apply {
                addSuppressed(IOException("unexpected end of stream"))
            }
        val wrapped = IOException("wrapped", retried)

        listOf(retried, wrapped).forEach { failure ->
            assertFalse(failure.provesRequestNeverSent(), "$failure")
        }
    }
}
