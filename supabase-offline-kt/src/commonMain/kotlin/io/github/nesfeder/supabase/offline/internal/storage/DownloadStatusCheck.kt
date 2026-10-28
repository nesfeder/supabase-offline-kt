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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSecure
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Carried in a coroutine context, it records what the download status check saw of a download sent
 * from that coroutine: whether the request, as it was finally sent, or its answer covered only part
 * of the object, the ETag of a whole-object answer, and an HTTP 304 to the download's own
 * condition; see [installDownloadStatusCheck].
 *
 * @param refusesErrors whether the check also stops the download when its answer is not a success,
 *   as a channel download needs; a bytes download, which supabase-kt checks itself, passes `false`.
 */
internal class CheckedDownload(val refusesErrors: Boolean = true) :
    AbstractCoroutineContextElement(CheckedDownloadKey) {
    private val partial = AtomicBoolean(false)
    private val notModified = AtomicBoolean(false)
    private val tag = AtomicReference<String?>(null)
    private val length = AtomicLong(UNKNOWN_LENGTH)
    private val condition = AtomicReference<String?>(null)
    private val conditionWithdrawn = AtomicBoolean(false)

    /**
     * Whether the download fetched part of the object, or none of it: its request, with every
     * header the bucket adds, was not a `GET` or carried `Range` or `If-Range`, or its answer was
     * HTTP 206. Such bytes are the caller's, never the cached copy.
     */
    val fetchedPart: Boolean
        get() = partial.load()

    /**
     * Whether the server answered HTTP 304 to the download's own condition: a `GET` of the whole
     * object that asked, with the `If-None-Match` [askAbout] set and no other condition, whether
     * the cached copy it names is current. It is.
     */
    val answeredNotModified: Boolean
        get() = notModified.load()

    /**
     * The ETag of the cached copy the download's request asks about, set as the request is built,
     * while it carries `If-None-Match` with it alone; `null` when it asks about none.
     */
    val askedETag: String?
        get() = condition.load()

    /**
     * Whether the download's own condition went out as it was set: a request that asked about the
     * cached copy and had it left on.
     */
    val sentItsCondition: Boolean
        get() = condition.load() != null && !conditionWithdrawn.load()

    /**
     * Records that the request being built asks about the cached copy whose ETag is [eTag] with
     * `If-None-Match`; `null` when it asks about none.
     */
    fun askAbout(eTag: String?) {
        condition.store(eTag)
    }

    /**
     * Records that the check took the download's condition off its request, which went out partial
     * or with a condition of the caller's.
     */
    fun withdrawCondition() {
        conditionWithdrawn.store(true)
    }

    /** The `ETag` of a success that holds the whole object, or `null` when it carried none. */
    val eTag: String?
        get() = tag.load()

    /**
     * The size of the whole object a success holds, from its `Content-Length`; `null` until such an
     * answer arrives, and when it gave none or encoded its body, which then arrives at another
     * length.
     */
    val expectedLength: Long?
        get() = length.load().takeIf { it != UNKNOWN_LENGTH }

    /** Records that the download fetched part of the object. */
    fun markPartial() {
        partial.store(true)
    }

    /** Records that the server answered HTTP 304 to the download's own condition. */
    fun markNotModified() {
        notModified.store(true)
    }

    /** Records the `ETag` of a success that holds the whole object. */
    fun recordETag(eTag: String?) {
        tag.store(eTag)
    }

    /** Records the size of the whole object a success holds, or `null` when it is not known. */
    fun recordLength(expectedLength: Long?) {
        length.store(expectedLength ?: UNKNOWN_LENGTH)
    }
}

/** Stands for a length an answer did not give. */
private const val UNKNOWN_LENGTH: Long = -1L

/** The key [CheckedDownload] is found under in a coroutine context. */
private object CheckedDownloadKey : CoroutineContext.Key<CheckedDownload>

/**
 * Runs [download], a channel download through supabase-kt's Storage plugin, so that a response that
 * is not a success throws what the plugin's bytes download throws for it, before any of its body
 * reaches the download's channel. supabase-kt's channel download checks no status, and would copy
 * Storage's error body into the channel as the object. Needs the check on the client
 * ([installDownloadStatusCheck]).
 *
 * @param checked where the check records what it saw; pass one to read it after a failure too.
 * @return what the check saw of the download: whether it fetched only part of the object, and the
 *   answer's ETag.
 */
internal suspend fun checkingDownloadStatus(
    checked: CheckedDownload = CheckedDownload(),
    download: suspend () -> Unit,
): CheckedDownload {
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

/**
 * Takes the download's own `If-None-Match` ([CheckedDownload.askAbout]) off [request] when the
 * request, as it is about to go out with every header the bucket adds, could not be answered about
 * the cached copy alone: it fetches part of the object, or carries a condition of the caller's.
 * supabase-kt adds a bucket's headers to a channel download after the request's own options, where
 * the download set its condition, so only here are they all seen. A server looks at `If-None-Match`
 * before `Range` (RFC 9110, 13.2.2), so left on, the condition could turn the caller's range into a
 * 304 about the whole copy, or a 304 to the caller's condition could pass for one about the copy.
 * The request then goes out as it would have without the cache.
 */
private fun CheckedDownload.withdrawUnusableCondition(request: HttpRequestBuilder) {
    val ours = askedETag ?: return
    val headers = request.headers
    val conditions = headers.getAll(HttpHeaders.IfNoneMatch).orEmpty()
    val usable =
        conditions == listOf(ours) &&
            request.method == HttpMethod.Get &&
            !headers.contains(HttpHeaders.Range) &&
            !headers.contains(HttpHeaders.IfRange) &&
            !headers.contains(HttpHeaders.IfModifiedSince)
    if (usable) return
    withdrawCondition()
    val callers = conditions.toMutableList().apply { remove(ours) }
    headers.remove(HttpHeaders.IfNoneMatch)
    callers.forEach { condition -> headers.append(HttpHeaders.IfNoneMatch, condition) }
}

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
 * its status, is marked on the download, which then never caches it. The `ETag` of a success that
 * holds the whole object is recorded on the download, with its `Content-Length` when its body is
 * not encoded, and so is an HTTP 304 to the download's own condition, before it is refused as any
 * other answer that is not a success. That condition goes out only on a request for the whole
 * object that carries no other: the check takes it off any other request before it is sent.
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
        currentCoroutineContext()[CheckedDownloadKey]?.withdrawUnusableCondition(request)
        val call = execute(request)
        val checked = currentCoroutineContext()[CheckedDownloadKey] ?: return@intercept call
        val status = call.response.status
        val fetchesPart = call.request.fetchesPart(status)
        if (status.isSuccess()) {
            if (fetchesPart) {
                checked.markPartial()
            } else {
                checked.recordETag(call.response.headers[HttpHeaders.ETag])
                checked.recordLength(
                    call.response.contentLength()?.takeIf {
                        call.response.headers[HttpHeaders.ContentEncoding] == null
                    }
                )
            }
            return@intercept call
        }
        if (status == HttpStatusCode.NotModified && !fetchesPart && checked.sentItsCondition)
            checked.markNotModified()
        // supabase-kt's bytes download refuses it itself, and its own way
        if (!checked.refusesErrors) return@intercept call
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
