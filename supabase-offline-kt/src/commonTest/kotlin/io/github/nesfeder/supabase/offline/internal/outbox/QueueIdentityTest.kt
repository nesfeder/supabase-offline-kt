package io.github.nesfeder.supabase.offline.internal.outbox

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whose writes the queue holds: the signed-in user, the same user while their session restores or
 * their token refresh has not landed, or nobody.
 */
class QueueIdentityTest {

    @Test
    fun an_authenticated_session_is_its_user() = runTest {
        // Given
        val status = SessionStatus.Authenticated(session(userId = "user-a"))

        // When
        val identity = queueIdentityFor(status) { error("the stored session is not needed") }

        // Then
        assertEquals(QueueIdentity.User("user-a"), identity)
    }

    @Test
    fun a_session_without_a_user_object_is_its_tokens_subject() = runTest {
        // Given — a restored session whose user was not stored
        val status = SessionStatus.Authenticated(session(userId = null, subject = "user-b"))

        // When
        val identity = queueIdentityFor(status) { null }

        // Then
        assertEquals(QueueIdentity.User("user-b"), identity)
    }

    @Test
    fun a_refreshing_session_is_still_its_stored_user() = runTest {
        // Given — offline with an expired token: the usual state when writes are queued
        @Suppress("DEPRECATION")
        val status = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(Exception("offline")))

        // When
        val identity = queueIdentityFor(status) { session(userId = "user-a") }

        // Then — not "anonymous", which would lock the user out of their own queue once back online
        assertEquals(QueueIdentity.User("user-a"), identity)
    }

    @Test
    fun a_restoring_session_is_its_stored_user() = runTest {
        // Given / When
        val identity = queueIdentityFor(SessionStatus.Initializing) { session(userId = "user-a") }

        // Then
        assertEquals(QueueIdentity.User("user-a"), identity)
    }

    @Test
    fun a_restoring_session_with_nothing_stored_is_anonymous() = runTest {
        // Given / When
        val identity = queueIdentityFor(SessionStatus.Initializing) { null }

        // Then
        assertEquals(QueueIdentity.Anonymous, identity)
    }

    @Test
    fun signed_out_is_anonymous() = runTest {
        // Given / When
        val identity = queueIdentityFor(SessionStatus.NotAuthenticated(isSignOut = true)) { error("not needed") }

        // Then
        assertEquals(QueueIdentity.Anonymous, identity)
    }

    @Test
    fun a_session_that_names_no_user_cannot_be_told() = runTest {
        // Given — neither a user object nor a readable token
        val status = SessionStatus.Authenticated(session(userId = null, subject = null))

        // When / Then
        assertNull(queueIdentityFor(status) { null })
    }

    @Test
    fun a_client_without_the_auth_plugin_is_anonymous() = runTest {
        // Given / When
        val identity = newTestSupabaseClient().currentQueueIdentity()

        // Then
        assertEquals(QueueIdentity.Anonymous, identity)
    }

    @Test
    fun identities_are_stored_distinctly() {
        assertEquals("user:user-a", QueueIdentity.User("user-a").storedValue)
        assertEquals("anonymous", QueueIdentity.Anonymous.storedValue)
    }

    private fun session(userId: String?, subject: String? = userId): UserSession = UserSession(
        accessToken = jwt(subject),
        refreshToken = "refresh-token",
        expiresIn = 3600,
        tokenType = "bearer",
        user = userId?.let { id -> UserInfo(aud = "authenticated", id = id) },
    )

    private fun jwt(subject: String?): String {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val claims = if (subject == null) "{}" else """{"sub":"$subject","role":"authenticated"}"""
        return "$header.${encoder.encode(claims.encodeToByteArray())}.signature"
    }
}
