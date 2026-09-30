// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whose token a request carries, as the replay owner check reads it from the `Authorization`
 * header: a user's JWT speaks for that user, and anything else for nobody signed in.
 */
class ReplayOwnerCheckTest {

    @Test
    fun a_users_jwt_speaks_for_that_user() {
        // Given / When
        val identity = identityCarriedBy("Bearer ${testJwt("user-a")}")

        // Then
        assertEquals(QueueIdentity.User("user-a"), identity)
    }

    @Test
    fun no_token_the_anon_key_or_a_new_api_key_speaks_for_nobody_signed_in() {
        // Given
        val headers =
            listOf(null, "Bearer unit-test-anon-key", "Bearer sb_publishable_x", "Basic x")

        // When
        val identities = headers.map(::identityCarriedBy)

        // Then
        assertEquals(List(headers.size) { QueueIdentity.Anonymous }, identities)
    }
}
