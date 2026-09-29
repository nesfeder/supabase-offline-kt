// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.HttpClientEngine
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.first

/**
 * A real supabase-kt client with the Auth plugin installed over an in-memory session store, signed
 * out once it returns. It never refreshes on its own.
 *
 * @param engine the Ktor engine answering every request.
 * @param alwaysAutoRefresh whether a request refreshes an expired session inline first.
 * @return the client.
 */
internal suspend fun newTestSupabaseClientWithAuth(
    engine: HttpClientEngine,
    alwaysAutoRefresh: Boolean = false,
): SupabaseClient {
    val supabase =
        newTestSupabaseClient(engine = engine) {
            install(Postgrest) { maxRetries = 0 }
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                this.alwaysAutoRefresh = alwaysAutoRefresh
                enableLifecycleCallbacks = false
            }
        }
    supabase.auth.sessionStatus.first { status -> status !is SessionStatus.Initializing }
    return supabase
}

/**
 * Signs [subject] in on this client with a session that does not refresh on its own.
 *
 * @param subject the user id carried as the token's `sub`.
 * @param expiresInSeconds the session's lifetime; negative for one that has already expired.
 */
internal suspend fun SupabaseClient.signInForTest(subject: String, expiresInSeconds: Long = 3600) {
    auth.importSession(
        UserSession(
            accessToken = testJwt(subject),
            refreshToken = "refresh-token",
            expiresIn = expiresInSeconds,
            tokenType = "bearer",
        ),
        autoRefresh = false,
    )
}

/**
 * An unsigned JWT whose `sub` is [subject], enough for the engine to tell sessions apart.
 *
 * @param subject the token's subject.
 * @return the token.
 */
internal fun testJwt(subject: String): String {
    val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
    val payload =
        encoder.encode(
            """{"sub":"$subject","role":"authenticated","exp":9999999999}""".encodeToByteArray()
        )
    return "$header.$payload.signature"
}
