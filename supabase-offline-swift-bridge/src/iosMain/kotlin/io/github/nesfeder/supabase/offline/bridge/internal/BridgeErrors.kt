// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.bridge.BridgeError
import io.github.nesfeder.supabase.offline.bridge.BridgeErrorKind
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** A description the bridge could not read; [BridgeErrorKind.INVALID_REQUEST]. */
internal class InvalidDescriptionException(message: String) : IllegalArgumentException(message)

/** A description asked for what this build does not offer; [BridgeErrorKind.UNSUPPORTED]. */
internal class UnsupportedDescriptionException(message: String) : IllegalArgumentException(message)

/** The app's access-token callback answered with a failure; [BridgeErrorKind.ACCESS_TOKEN]. */
internal class AccessTokenException(message: String) : Exception(message)

/** An error of [kind] the bridge itself reports, with no server fields. */
internal fun bridgeError(
    kind: BridgeErrorKind,
    message: String,
    debugType: String,
    isConnectivityFailure: Boolean = false,
): BridgeError =
    BridgeError(
        kind = kind,
        message = message,
        httpStatus = null,
        code = null,
        details = null,
        hint = null,
        isConnectivityFailure = isConnectivityFailure,
        debugType = debugType,
    )

/** The error the caller's own cancellation reports. */
internal fun cancelledError(): BridgeError =
    bridgeError(BridgeErrorKind.CANCELLED, "The call was cancelled", "CancellationException")

/** The error a call reports once its client is closed. */
internal fun clientClosedError(debugType: String = "ClientClosed"): BridgeError =
    bridgeError(BridgeErrorKind.CLIENT_CLOSED, "The client is closed", debugType)

/**
 * [failure] as the value a completion carries.
 *
 * @param clientClosing whether the bridge client is closing or closed, which makes the library's
 *   closed error, and a cancellation, [BridgeErrorKind.CLIENT_CLOSED].
 */
internal fun bridgeErrorOf(failure: Throwable, clientClosing: Boolean): BridgeError {
    val type = failure::class.simpleName ?: "Throwable"
    return when {
        failure is InvalidDescriptionException ->
            bridgeError(BridgeErrorKind.INVALID_REQUEST, failure.messageOr(type), type)
        failure is UnsupportedDescriptionException ->
            bridgeError(BridgeErrorKind.UNSUPPORTED, failure.messageOr(type), type)
        failure is AccessTokenException ->
            bridgeError(BridgeErrorKind.ACCESS_TOKEN, failure.messageOr(type), type)
        failure is CacheMissException ->
            bridgeError(BridgeErrorKind.CACHE_MISS, failure.messageOr(type), type)
        failure is NetworkDisabledException ->
            bridgeError(BridgeErrorKind.NETWORK_DISABLED, failure.messageOr(type), type, true)
        failure is MutationNotQueueableException ->
            bridgeError(BridgeErrorKind.MUTATION_NOT_QUEUEABLE, failure.messageOr(type), type, true)
        failure is SyncFailedException -> syncFailedError(failure)
        failure is PostgrestRestException -> postgrestError(failure, type)
        failure is RestException -> restError(failure, type)
        failure is ResponseException ->
            serverFieldsError(
                kind = BridgeErrorKind.HTTP,
                message = "The server answered ${failure.response.status.value}",
                httpStatus = failure.response.status.value,
                type = type,
            )
        clientClosing && (failure is IllegalStateException || failure is CancellationException) ->
            clientClosedError(type)
        failure.isNetworkOrConnectivityFailure() -> connectivityError(failure, type)
        failure is SerializationException ->
            bridgeError(BridgeErrorKind.DECODING, "A response could not be decoded", type)
        failure is IllegalArgumentException ->
            bridgeError(BridgeErrorKind.INVALID_ARGUMENT, failure.messageOr(type), type)
        else -> bridgeError(BridgeErrorKind.UNEXPECTED, failure.messageOr(type), type)
    }
}

/** [SyncStatus.lastSyncError] and a sync failure as a value: its status and code, nothing else. */
internal fun syncFailedError(failure: SyncFailedException): BridgeError =
    BridgeError(
        kind = BridgeErrorKind.SYNC_FAILED,
        message = failure.messageOr("SyncFailedException"),
        httpStatus = failure.httpStatus,
        code = failure.code,
        details = null,
        hint = null,
        isConnectivityFailure = failure.isNetworkOrConnectivityFailure(),
        debugType = "SyncFailedException",
    )

/** PostgREST's refusal, with its fields as it sent them; never the URL its message quotes. */
private fun postgrestError(failure: PostgrestRestException, type: String): BridgeError =
    BridgeError(
        kind = BridgeErrorKind.SERVER,
        message = failure.error,
        httpStatus = failure.statusCode,
        code = failure.code,
        details = failure.details?.asDetailsText(),
        hint = failure.hint,
        isConnectivityFailure = false,
        debugType = type,
    )

/** Another REST failure: the server's error and description, never the URL and headers. */
private fun restError(failure: RestException, type: String): BridgeError =
    BridgeError(
        kind = BridgeErrorKind.HTTP,
        message = failure.error,
        httpStatus = failure.statusCode,
        code = null,
        details = failure.description,
        hint = null,
        isConnectivityFailure = false,
        debugType = type,
    )

/** An error of [kind] carrying the server's [httpStatus] and nothing else. */
private fun serverFieldsError(
    kind: BridgeErrorKind,
    message: String,
    httpStatus: Int,
    type: String,
): BridgeError =
    BridgeError(
        kind = kind,
        message = message,
        httpStatus = httpStatus,
        code = null,
        details = null,
        hint = null,
        isConnectivityFailure = false,
        debugType = type,
    )

/**
 * A transport failure, in the library's own words. The transport's text quotes the request's URL
 * with its query and filter values, so none of it is kept: only a reason from [transportReason].
 */
private fun connectivityError(failure: Throwable, type: String): BridgeError {
    val reason = transportReason(failure)
    val message = if (reason == null) CONNECTIVITY_MESSAGE else "$CONNECTIVITY_MESSAGE ($reason)"
    return bridgeError(BridgeErrorKind.CONNECTIVITY, message, type, isConnectivityFailure = true)
}

/** What every transport failure says. */
private const val CONNECTIVITY_MESSAGE = "The request could not reach the server"

/**
 * Why the request failed, from a fixed vocabulary: `timed out`, or the platform error's domain and
 * code (such as `NSURLErrorDomain -1009`); `null` when neither can be told.
 */
internal fun transportReason(failure: Throwable): String? {
    var current: Throwable? = failure
    while (current != null) {
        when (current) {
            is HttpRequestTimeoutException,
            is ConnectTimeoutException,
            is SocketTimeoutException -> return TIMED_OUT
            is DarwinHttpRequestException ->
                return platformReason(current.origin.domain, current.origin.code)
        }
        current = current.cause
    }
    // supabase-kt keeps only the transport's text, after its own sentence that quotes the URL.
    val transportText =
        (failure as? HttpRequestException)
            ?.message
            ?.substringAfter(TRANSPORT_REASON_MARKER, missingDelimiterValue = "") ?: return null
    if (transportText.startsWith(TIMEOUT_TEXT)) return TIMED_OUT
    val platformError = PLATFORM_ERROR.find(transportText) ?: return null
    return platformReason(platformError.groupValues[1], platformError.groupValues[2].toLong())
}

/** A platform error's domain and code as a reason, or `null` for a domain that is not a name. */
private fun platformReason(domain: String?, code: Long): String? =
    domain?.takeIf { PLATFORM_DOMAIN.matches(it) }?.let { "$it $code" }

/** The reason a timed-out request gives. */
private const val TIMED_OUT = "timed out"

/** What supabase-kt's `HttpRequestException` message puts before the transport's own text. */
private const val TRANSPORT_REASON_MARKER = "failed with message: "

/** How Ktor's request timeout begins its text. */
private const val TIMEOUT_TEXT = "Request timeout has expired"

/** The start of the Darwin engine's text: an `NSError`'s domain and code, then its description. */
private val PLATFORM_ERROR =
    Regex(
        """^Exception in http request: Error Domain=([A-Za-z][A-Za-z0-9_.]{0,63}) Code=(-?\d{1,9})\b"""
    )

/** An error domain's shape: an identifier, such as `NSURLErrorDomain`. */
private val PLATFORM_DOMAIN = Regex("""[A-Za-z][A-Za-z0-9_.]{0,63}""")

/** PostgREST's `details`, as text: a string as it is, anything else as JSON. */
private fun JsonElement.asDetailsText(): String? =
    when {
        this is JsonNull -> null
        this is JsonPrimitive && isString -> content
        else -> toString()
    }

/** This failure's message, or [fallback] when it has none. */
private fun Throwable.messageOr(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback
