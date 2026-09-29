package io.github.nesfeder.supabase.offline.internal.util

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On the JVM a failed connection or host lookup proves a request never reached the server; a read
 * timeout or a reset may come after the server received it.
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
        ).forEach { failure -> assertTrue(failure.provesRequestNeverSent(), "$failure") }
    }

    @Test
    fun failures_after_connecting_do_not() {
        listOf(
            SocketTimeoutException("read timed out", null),
            IOException("Connection reset"),
        ).forEach { failure -> assertFalse(failure.provesRequestNeverSent(), "$failure") }
    }
}
