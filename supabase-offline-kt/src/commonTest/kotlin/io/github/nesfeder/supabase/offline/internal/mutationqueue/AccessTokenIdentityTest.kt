// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.postgrest.RequestSession
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

/**
 * The identity a client without Auth reads from its provider's token: the user its `sub` names,
 * nobody signed in for no token or the project key, and no identity for a token that names no user.
 * A failing or hanging provider keeps the last user as the owner of local data, never as a settled
 * session, and nothing of the token or the user reaches a log.
 */
class AccessTokenIdentityTest {

    private val logs = RecordingLogSink()

    @Test
    fun a_token_speaks_for_its_sub_and_no_token_or_the_project_key_for_nobody_signed_in() {
        // Given / When / Then
        assertEquals(QueueIdentity.User("user-a"), identityOfAccessToken(testJwt("user-a"), KEY))
        assertEquals(QueueIdentity.Anonymous, identityOfAccessToken(null, KEY))
        assertEquals(QueueIdentity.Anonymous, identityOfAccessToken(KEY, KEY))
    }

    @Test
    fun a_token_that_names_no_user_is_no_identity() {
        // Given — an opaque token, a key of the new kind, a JWT without a sub, one with a blank sub
        val tokens =
            listOf(
                "opaque-token",
                "sb_publishable_x",
                jwtWithClaims("""{"role":"anon"}"""),
                jwtWithClaims("""{"sub":" "}"""),
            )

        // When / Then
        assertEquals(List(tokens.size) { null }, tokens.map { identityOfAccessToken(it, KEY) })
    }

    @Test
    fun each_answer_is_published_and_a_new_identity_is_said_without_naming_it() = runTest {
        // Given — a provider whose token changes from one user to another
        var token: String? = testJwt("user-a")
        val identity = newIdentity { token }
        assertEquals(AccessTokenIdentity.State.Unread, identity.state.value)
        assertEquals(RequestSession.Restoring, identity.requestSession())

        // When / Then — the first user, settled
        assertEquals(
            AccessTokenIdentity.State.Named(QueueIdentity.User("user-a")),
            identity.resolve(),
        )
        assertEquals(QueueIdentity.User("user-a"), identity.state.value.settled)
        assertEquals(
            RequestSession.SignedIn(QueueIdentity.User("user-a")),
            identity.requestSession(),
        )

        // When / Then — another user
        token = testJwt("user-b")
        assertEquals(QueueIdentity.User("user-b"), identity.resolve().settled)
        val said = logs.entriesAt(EngineLogLevel.INFO).single().message
        assertTrue("another identity" in said, said)
        assertTrue("user-" !in said, said)

        // When / Then — signed out, then a token that names no user
        token = null
        assertEquals(QueueIdentity.Anonymous, identity.resolve().settled)
        assertEquals(RequestSession.SignedOut, identity.requestSession())
        token = "opaque-token"
        val unnamed = identity.resolve()
        assertEquals(AccessTokenIdentity.State.Unnamed, unnamed)
        assertNull(unnamed.owner)
        assertNull(unnamed.settled)
        assertEquals(RequestSession.SignedIn(null), identity.requestSession())
    }

    @Test
    fun a_provider_that_fails_keeps_the_last_user_as_owner_and_settles_nothing() = runTest {
        // Given — a provider that named a user, then fails
        var fails = false
        val identity = newIdentity {
            if (fails) throw IllegalStateException("refresh failed for ${testJwt("user-a")}")
            testJwt("user-a")
        }
        identity.resolve()

        // When
        fails = true
        val failed = identity.resolve()

        // Then — still the user's data, never their session; said at DEBUG by its type alone
        assertEquals(AccessTokenIdentity.State.ProviderFailed(QueueIdentity.User("user-a")), failed)
        assertEquals(QueueIdentity.User("user-a"), failed.owner)
        assertNull(failed.settled)
        assertEquals(RequestSession.RefreshFailed, identity.requestSession())
        val debug = logs.entriesAt(EngineLogLevel.DEBUG).single().message
        assertTrue("IllegalStateException" in debug, debug)
        assertTrue("refresh failed" !in debug, debug)
        assertTrue(logs.entriesAt(EngineLogLevel.WARNING).isEmpty())

        // When — it fails again: the same user stays the owner
        assertEquals(QueueIdentity.User("user-a"), identity.resolve().owner)
    }

    @Test
    fun a_provider_that_never_answers_counts_as_failed_after_its_bound() = runTest {
        // Given — a provider that hangs, and no user named before
        val identity = newIdentity { awaitCancellation() }

        // When
        val state = identity.resolve()

        // Then
        assertEquals(AccessTokenIdentity.State.ProviderFailed(last = null), state)
        assertNull(state.owner)
    }

    @Test
    fun a_client_without_a_provider_is_nobody_signed_in() = runTest {
        // Given / When
        val identity =
            AccessTokenIdentity(
                accessToken = null,
                projectKey = KEY,
                engineLogger = logs.asEngineLogger(),
            )

        // Then
        assertEquals(QueueIdentity.Anonymous, identity.resolve().settled)
        assertEquals(QueueIdentity.Anonymous, identity.identityOf(KEY))
        assertEquals(QueueIdentity.User("user-a"), identity.identityOf(testJwt("user-a")))
    }

    private fun newIdentity(provider: suspend () -> String?): AccessTokenIdentity =
        AccessTokenIdentity(
            accessToken = provider,
            projectKey = KEY,
            engineLogger = logs.asEngineLogger(),
            timeout = 5.seconds,
        )

    /** An unsigned JWT whose payload is [claims]. */
    private fun jwtWithClaims(claims: String): String {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        return "$header.${encoder.encode(claims.encodeToByteArray())}.signature"
    }

    private companion object {
        const val KEY = "unit-test-anon-key"
    }
}
