package io.github.nesfeder.supabase.offline.internal.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [isPermanentMutationFailureStatus] — the offline mutation-replay drop decision. A permanent
 * status drops the queued mutation; a transient one keeps it for a later retry. 401 is transient
 * (token-refresh race after reconnect) and must never be treated as permanent.
 */
class MutationFailureStatusClassificationTest {

    @Test
    fun `401 unauthorized is transient not a permanent mutation failure`() {
        // Right after reconnect the access token can be expired while supabase-kt's auto-refresh
        // is still in flight; dropping the queued write here would silently lose it.
        assertFalse(401.isPermanentMutationFailureStatus())
    }

    @Test
    fun `408 and 429 are transient mutation failures`() {
        assertFalse(408.isPermanentMutationFailureStatus())
        assertFalse(429.isPermanentMutationFailureStatus())
    }

    @Test
    fun `other 4xx client errors are permanent mutation failures`() {
        assertTrue(403.isPermanentMutationFailureStatus())
        assertTrue(404.isPermanentMutationFailureStatus())
        assertTrue(409.isPermanentMutationFailureStatus())
        assertTrue(422.isPermanentMutationFailureStatus())
    }

    @Test
    fun `5xx and non-error statuses are not permanent mutation failures`() {
        assertFalse(500.isPermanentMutationFailureStatus())
        assertFalse(503.isPermanentMutationFailureStatus())
        assertFalse(200.isPermanentMutationFailureStatus())
        assertFalse(302.isPermanentMutationFailureStatus())
    }
}
