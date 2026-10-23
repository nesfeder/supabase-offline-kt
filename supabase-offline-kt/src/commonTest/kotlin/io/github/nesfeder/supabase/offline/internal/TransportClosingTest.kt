// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.OfflineClientClosedException
import io.github.nesfeder.supabase.offline.internal.postgrest.CLIENT_CLOSED_MESSAGE
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/** How a call's edge tells the client's closing from a failure of the call's own. */
class TransportClosingTest {

    @Test
    fun a_cancellation_is_the_closed_error_only_once_the_client_or_its_http_client_is_closed() =
        runTest {
            // Given — a cancellation met while the caller still runs
            val cancellation = CancellationException("the HTTP client's")

            // When
            val transportClosed =
                closedErrorFor(cancellation, isClosed = false, isTransportClosed = true)
            val open = closedErrorFor(cancellation, isClosed = false, isTransportClosed = false)

            // Then — the closed error keeps the cancellation as its cause; while open it is
            // passed on as it is
            assertIs<OfflineClientClosedException>(transportClosed)
            assertSame(cancellation, transportClosed.cause)
            assertSame(cancellation, open)
        }

    @Test
    fun the_closed_error_is_told_by_its_type_not_by_its_message() = runTest {
        // Then — once closed, the typed error ends a collection quietly, and an
        // IllegalStateException that only says the same is a failure of its own
        assertTrue(
            isEndedByClosing(
                OfflineClientClosedException(null),
                isClosed = true,
                isTransportClosed = false,
            )
        )
        assertFalse(
            isEndedByClosing(
                IllegalStateException(CLIENT_CLOSED_MESSAGE),
                isClosed = true,
                isTransportClosed = false,
            )
        )
    }
}
