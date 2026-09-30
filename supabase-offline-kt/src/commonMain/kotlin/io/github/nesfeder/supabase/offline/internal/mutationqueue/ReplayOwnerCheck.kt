// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Carried in a coroutine context while a queued mutation is sent: the identity that owns it. The
 * phase [installReplayOwnerCheck] adds stops a PostgREST request sent from that coroutine unless
 * its token is [owner]'s, and records here that it did.
 */
internal class ReplayOwner(val owner: QueueIdentity) : AbstractCoroutineContextElement(Key) {

    /** The key a replay owner is found under in a coroutine context. */
    companion object Key : CoroutineContext.Key<ReplayOwner>

    private val stopped = AtomicBoolean(false)

    /** Whether the check stopped a request because its token was not [owner]'s. */
    val stoppedForAnotherSession: Boolean
        get() = stopped.load()

    /** Records that the check stopped a request. */
    fun recordStop() {
        stopped.store(true)
    }
}

/** Thrown by the replay owner check to stop a request before it leaves the device. */
private class ReplayOwnerCheckStop :
    IllegalStateException(
        "Request stopped by the offline engine: the session no longer belongs to the mutation's owner"
    )

/** The request-pipeline phase the check runs in, after every plugin's own phases. */
private val ReplayOwnerCheckPhase = PipelinePhase("SupabaseOfflineReplayOwnerCheck")

/** Set on a client's HTTP client once the check is installed. */
private val ReplayOwnerCheckInstallation =
    AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.ReplayOwnerCheck")

/**
 * Installs, once per client and only when the Auth plugin is installed, the phase that binds a
 * queued mutation's send to its owner. It runs just before the request is sent, after supabase-kt
 * set the `Authorization` header, and stops a PostgREST request made under a [ReplayOwner] whose
 * token names another identity. Without Auth the session never changes identity.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installReplayOwnerCheck() {
    if (pluginManager.getPluginOrNull(Auth) == null) return
    val client = httpClient.httpClient
    val installation =
        client.attributes.computeIfAbsent(ReplayOwnerCheckInstallation) { AtomicBoolean(false) }
    if (!installation.compareAndSet(expectedValue = false, newValue = true)) return
    client.requestPipeline.insertPhaseBefore(HttpRequestPipeline.Send, ReplayOwnerCheckPhase)
    client.requestPipeline.intercept(ReplayOwnerCheckPhase) {
        val replay = currentCoroutineContext()[ReplayOwner] ?: return@intercept
        if (!isPostgrestRequest(context)) return@intercept
        if (identityCarriedBy(context.headers[HttpHeaders.Authorization]) != replay.owner) {
            replay.recordStop()
            throw ReplayOwnerCheckStop()
        }
    }
}

/**
 * The identity a request's `Authorization` header speaks for: the user its JWT names as `sub`, or
 * the anonymous identity for no token, the anon key or a key that is not a user's JWT.
 */
internal fun identityCarriedBy(authorization: String?): QueueIdentity {
    val token =
        authorization
            ?.takeIf { header -> header.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
    val subject = token?.let(::jwtSubjectOrNull) ?: return QueueIdentity.Anonymous
    return QueueIdentity.User(subject)
}

/** The scheme prefix of a bearer `Authorization` header. */
private const val BEARER_PREFIX = "Bearer "

/**
 * Whether [request] goes to this client's PostgREST with the session's token: a PostgREST
 * configured with its own `jwtToken` sends that token whoever is signed in.
 */
private fun SupabaseClient.isPostgrestRequest(request: HttpRequestBuilder): Boolean {
    val postgrest = pluginManager.getPluginOrNull(Postgrest) ?: return false
    if (postgrest.config.jwtToken != null) return false
    val base = postgrest.resolveUrl().trimEnd('/')
    val url = request.url.buildString()
    return url == base || url.startsWith("$base/") || url.startsWith("$base?")
}
