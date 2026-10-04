// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.storage.Storage
import io.ktor.client.call.save
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSecure
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Carried in a coroutine context, it has the download status check stop a download sent from that
 * coroutine whose response is not a success; see [installDownloadStatusCheck].
 */
private object CheckedDownload : AbstractCoroutineContextElement(CheckedDownloadKey)

/** The key [CheckedDownload] is found under in a coroutine context. */
private object CheckedDownloadKey : CoroutineContext.Key<CheckedDownload>

/**
 * Runs [download], a channel download through supabase-kt's Storage plugin, so that a response that
 * is not a success throws what the plugin's bytes download throws for it, before any of its body
 * reaches the download's channel. supabase-kt's channel download checks no status, and would copy
 * Storage's error body into the channel as the object. Needs the check on the client
 * ([installDownloadStatusCheck]).
 */
internal suspend fun <T> checkingDownloadStatus(download: suspend () -> T): T =
    withContext(CheckedDownload) { download() }

/** The statuses Ktor's `HttpRedirect` follows for a download's `GET`. */
private val FOLLOWED_REDIRECTS: Set<HttpStatusCode> =
    setOf(
        HttpStatusCode.MovedPermanently,
        HttpStatusCode.Found,
        HttpStatusCode.SeeOther,
        HttpStatusCode.TemporaryRedirect,
        HttpStatusCode.PermanentRedirect,
    )

/**
 * Whether a redirect from [from] to [location] leaves https for plain http, which Ktor's
 * `HttpRedirect` does not follow unless the app allowed it.
 */
private fun leavesHttps(from: Url, location: String): Boolean =
    from.protocol.isSecure() && !URLBuilder(from).takeFrom(location).protocol.isSecure()

/** Set on a client's HTTP client once the download status check is installed. */
private val DownloadStatusCheckInstallation =
    AttributeKey<AtomicBoolean>("io.github.nesfeder.supabase.offline.DownloadStatusCheck")

/**
 * Installs, once per client with the Storage plugin, an interceptor next to the engine that fails a
 * download made through [checkingDownloadStatus] whose response is not a success: with the
 * exception the Storage plugin makes of that response, which is what its bytes download throws. The
 * response is read whole first, so its connection is released. A redirect the client follows passes
 * through, and the answer it leads to is checked in turn; one from https to plain http is refused,
 * as the bytes download refuses it. An HTTP retry plugin the app installed,
 * or a Ktor bearer-auth refresh on `401`, sits further from the engine, and sees the failure as an
 * exception.
 */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.installDownloadStatusCheck() {
    val storage = pluginManager.getPluginOrNull(Storage) ?: return
    val client = httpClient.httpClient
    val installation =
        client.attributes.computeIfAbsent(DownloadStatusCheckInstallation) { AtomicBoolean(false) }
    if (!installation.compareAndSet(expectedValue = false, newValue = true)) return
    val followsRedirects = client.pluginOrNull(HttpRedirect) != null
    client.plugin(HttpSend).intercept { request ->
        val call = execute(request)
        val status = call.response.status
        if (currentCoroutineContext()[CheckedDownloadKey] == null || status.isSuccess())
            return@intercept call
        // Not the answer: HttpRedirect, further from the engine, asks again, back through here. It
        // does not follow one that leaves https, so that one is the answer, and refused
        val location = call.response.headers[HttpHeaders.Location]
        if (
            followsRedirects &&
                status in FOLLOWED_REDIRECTS &&
                location != null &&
                !leavesHttps(call.request.url, location)
        )
            return@intercept call
        val saved = call.save()
        val refusal =
            try {
                storage.parseErrorResponse(saved.response)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // What the bytes download throws too, when the body is not Storage's error
                failure
            }
        throw refusal
    }
}
