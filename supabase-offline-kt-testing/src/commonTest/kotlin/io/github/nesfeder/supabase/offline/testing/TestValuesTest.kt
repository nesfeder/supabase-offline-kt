// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.io.IOException

/** The factories hand back the library's own types, holding exactly what they were given. */
class TestValuesTest {

    @Test
    fun a_cached_file_holds_its_fields() {
        // Given
        val fetchedAt = Instant.parse("2026-01-01T00:00:00Z")

        // When
        val file = cachedFile(path = "/photos/one.jpg", fetchedAt = fetchedAt, sizeBytes = 7)

        // Then
        assertEquals("/photos/one.jpg", file.path)
        assertEquals(fetchedAt, file.fetchedAt)
        assertEquals(7, file.sizeBytes)
    }

    @Test
    fun the_exceptions_carry_their_message_and_cause() {
        // Given
        val offline = IOException("network down")

        // When
        val miss = cacheMissException("nothing cached")
        val notQueueable = mutationNotQueueableException(cause = offline, message = "not queued")
        val disabled = networkDisabledException("switched off")

        // Then
        assertEquals("nothing cached", miss.message)
        assertEquals("not queued", notQueueable.message)
        assertSame(offline, notQueueable.cause)
        assertTrue(notQueueable.isNetworkOrConnectivityFailure())
        assertEquals("switched off", disabled.message)
    }
}
