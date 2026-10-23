// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.SupabaseEncodingException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.OfflineClientClosedException
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.bridge.BridgeError
import io.github.nesfeder.supabase.offline.bridge.BridgeErrorKind
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.IllegalHeaderNameException
import io.ktor.http.IllegalHeaderValueException
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
internal fun canceledError(): BridgeError =
    bridgeError(BridgeErrorKind.CANCELLED, "The call was canceled", "CancellationException")

/** The error a call reports once its client is closed. */
internal fun clientClosedError(debugType: String = "ClientClosed"): BridgeError =
    bridgeError(BridgeErrorKind.CLIENT_CLOSED, "The client is closed", debugType)

/**
 * [failure] as the value a completion carries, by its type alone. The library's closed error,
 * [OfflineClientClosedException], is [BridgeErrorKind.CLIENT_CLOSED]; nothing else is, so a failure
 * of its own that a call meets while the client closes keeps its kind.
 */
internal fun bridgeErrorOf(failure: Throwable): BridgeError {
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
        failure is OfflineClientClosedException -> clientClosedError(type)
        failure is PostgrestRestException -> postgrestError(failure, type)
        failure is RestException -> restError(failure, type)
        failure is ResponseException ->
            serverFieldsError(
                kind = BridgeErrorKind.HTTP,
                message = "The server answered ${failure.response.status.value}",
                httpStatus = failure.response.status.value,
                type = type,
            )
        failure.isNetworkOrConnectivityFailure() -> connectivityError(failure, type)
        failure is SerializationException || failure is SupabaseEncodingException ->
            bridgeError(BridgeErrorKind.DECODING, decodingMessageOf(failure), type)
        // Ktor quotes the whole header, so a key or token with a control character would leak.
        failure is IllegalHeaderNameException || failure is IllegalHeaderValueException ->
            bridgeError(BridgeErrorKind.INVALID_ARGUMENT, ILLEGAL_HEADER_MESSAGE, type)
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

/** Storage's refusal: the status, Storage's error code and message, never the URL and headers. */
private fun restError(failure: RestException, type: String): BridgeError {
    val refusal = storageRefusalOf(failure)
    return BridgeError(
        kind = BridgeErrorKind.HTTP,
        message = refusal.message,
        httpStatus = failure.statusCode,
        code = refusal.code,
        details = null,
        hint = null,
        isConnectivityFailure = false,
        debugType = type,
    )
}

/** The error code and message Storage sent with a refusal; `code` is `null` when it sent none. */
internal class StorageRefusal(val code: String?, val message: String)

/**
 * What Storage sent with [failure]. supabase-kt keeps Storage's error and message as the error and
 * description of a `400`, and for any other status prints its own record of them into the error.
 */
internal fun storageRefusalOf(failure: RestException): StorageRefusal {
    val printed = PRINTED_STORAGE_ERROR.matchEntire(failure.error)
    val description = failure.description
    val (code, message) =
        when {
            printed != null -> printed.groupValues[1] to printed.groupValues[2]
            description != null -> failure.error to description
            else -> null to failure.error
        }
    return when {
        code == UNREAD_STORAGE_ERROR && message.isBlank() ->
            StorageRefusal(null, "The server answered ${failure.statusCode}")
        message.isBlank() ->
            StorageRefusal(code, code ?: "The server answered ${failure.statusCode}")
        else -> StorageRefusal(code, message)
    }
}

/** supabase-kt's printed record of a Storage refusal: Storage's error, then its message. */
private val PRINTED_STORAGE_ERROR =
    Regex(
        """Unknown error response StorageErrorResponse\(statusCode=-?\d+, error=(.*?), message=(.*)\)""",
        RegexOption.DOT_MATCHES_ALL,
    )

/** The error supabase-kt records when Storage's answer held no refusal it could read. */
private const val UNREAD_STORAGE_ERROR = "Unknown error"

/**
 * What could not be decoded and where, read from the failure's own text before the payload it
 * quotes: the missing field and the JSON path when it names them. Never the payload or a type name.
 */
internal fun decodingMessageOf(failure: Throwable): String {
    val text = QUOTED_PAYLOAD_MARKER.split(failure.message.orEmpty(), limit = 2).first()
    val field = MISSING_FIELD.find(text)?.groupValues?.get(1)
    val path = JSON_PATH.find(text)?.groupValues?.get(1)
    val reason =
        when {
            field != null && path != null -> ": the field \"$field\" is missing at $path"
            field != null -> ": the field \"$field\" is missing"
            path != null -> ": the value at $path is missing or of the wrong type"
            else -> ""
        }
    return "The server's answer could not be read$reason"
}

/**
 * What kotlinx.serialization and supabase-kt write before the payload they quote ("JSON input:").
 */
private val QUOTED_PAYLOAD_MARKER = Regex("""(?i)input:""")

/** A missing field's name, as kotlinx.serialization reports it. */
private val MISSING_FIELD = Regex("""Field '([^']+)' is required""")

/** The JSON path of a decoding failure, such as `$[1].signedURL`. */
private val JSON_PATH = Regex("""at path:? (\$[^\s,]*)""")

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

/**
 * What a request header holding a character HTTP forbids says: nothing of the header, which may be
 * the project key or the access token. A sync pass the header stopped reports the same sentence in
 * the library's own words, which a test holds to this one.
 */
internal const val ILLEGAL_HEADER_MESSAGE =
    "A request header holds a character a header cannot carry, such as a line break; check the " +
        "project key and the access token"

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
