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
import io.ktor.client.request.HttpRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
 * coroutine whose response is not a success, and records whether the request, as it was finally
 * sent, or its answer covered only part of the object; see [installDownloadStatusCheck].
 */
internal class CheckedDownload : AbstractCoroutineContextElement(CheckedDownloadKey) {
    private val partial = AtomicBoolean(false)

    /**
     * Whether the download fetched part of the object, or none of it: its request, with every
     * header the bucket adds, was not a `GET` or carried `Range` or `If-Range`, or its answer was
     * HTTP 206. Such bytes are the caller's, never the cached copy.
     */
    val fetchedPart: Boolean
        get() = partial.load()

    /** Records that the download fetched part of the object. */
    fun markPartial() {
        partial.store(true)
    }
}

/** The key [CheckedDownload] is found under in a coroutine context. */
private object CheckedDownloadKey : CoroutineContext.Key<CheckedDownload>

/**
 * Runs [download], a channel download through supabase-kt's Storage plugin, so that a response that
 * is not a success throws what the plugin's bytes download throws for it, before any of its body
 * reaches the download's channel. supabase-kt's channel download checks no status, and would copy
 * Storage's error body into the channel as the object. Needs the check on the client
 * ([installDownloadStatusCheck]).
 *
 * @return what the check saw of the download: whether it fetched only part of the object.
 */
internal suspend fun checkingDownloadStatus(download: suspend () -> Unit): CheckedDownload {
    val checked = CheckedDownload()
    withContext(checked) { download() }
    return checked
}

/**
 * Whether this request, as sent, or its answer's [status] covers part of the object, or none of it:
 * a `Range` or `If-Range` header, a method other than `GET`, or an HTTP 206.
 */
private fun HttpRequest.fetchesPart(status: HttpStatusCode): Boolean =
    status == HttpStatusCode.PartialContent ||
        method != HttpMethod.Get ||
        headers.contains(HttpHeaders.Range) ||
        headers.contains(HttpHeaders.IfRange)

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
 * Installs, once per client with Storage, an interceptor beside the engine that fails a download
 * made through [checkingDownloadStatus] whose response is not a success. It throws what the bytes
 * download throws, after reading the response whole. A redirect Ktor follows is checked where it
 * ends; one from https to plain http is refused, even with Ktor's `allowHttpsDowngrade` on, where
 * the bytes download follows it. An app's retry or bearer-refresh plugin sees the failure thrown. A
 * success that covers only part of the object, by the request as the bucket's headers left it or by
 * its status, is marked on the download, which then never caches it.
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
        val checked = currentCoroutineContext()[CheckedDownloadKey] ?: return@intercept call
        val status = call.response.status
        if (status.isSuccess()) {
            if (call.request.fetchesPart(status)) checked.markPartial()
            return@intercept call
        }
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
