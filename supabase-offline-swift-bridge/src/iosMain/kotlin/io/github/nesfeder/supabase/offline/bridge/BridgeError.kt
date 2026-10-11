// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

/**
 * Every failure of a bridge call, as a value: nothing the bridge exports ever throws.
 *
 * @property kind what failed; decides what the other fields hold.
 * @property message what happened, in words. It never quotes a URL, a header, a row or a token.
 * @property httpStatus the server's HTTP status, for [BridgeErrorKind.SERVER],
 *   [BridgeErrorKind.HTTP] and a [BridgeErrorKind.SYNC_FAILED] the server caused; otherwise `null`.
 * @property code the Postgres SQLSTATE or PostgREST code (such as `23505` or `PGRST204`), or
 *   Storage's error code (such as `not_found`), when the server sent one.
 * @property details the server's details, when it sent any.
 * @property hint the server's hint, when it sent one.
 * @property isConnectivityFailure whether the network, not the server, failed: `true` for
 *   [BridgeErrorKind.CONNECTIVITY], [BridgeErrorKind.NETWORK_DISABLED],
 *   [BridgeErrorKind.MUTATION_NOT_QUEUEABLE] and a [BridgeErrorKind.SYNC_FAILED] the network
 *   caused.
 * @property debugType the name of the failure's type, for diagnostics only; never matched on.
 */
public class BridgeError
internal constructor(
    public val kind: BridgeErrorKind,
    public val message: String,
    public val httpStatus: Int?,
    public val code: String?,
    public val details: String?,
    public val hint: String?,
    public val isConnectivityFailure: Boolean,
    public val debugType: String,
) {
    /** The kind and message, for a log line; no other field. */
    override fun toString(): String = "BridgeError($kind: $message)"
}

/** What a [BridgeError] reports. A later build may add a kind; match the ones you handle. */
public enum class BridgeErrorKind {
    /** The caller canceled the call through its [BridgeCancellable]. */
    CANCELLED,

    /** The client is closed, or closed while the call ran. */
    CLIENT_CLOSED,

    /** The request could not reach the server, or got no answer: a transport failure or timeout. */
    CONNECTIVITY,

    /** PostgREST refused the request: status, code, message, details and hint as it sent them. */
    SERVER,

    /** Another HTTP failure, such as Storage's: status, and Storage's error code and message. */
    HTTP,

    /** A cache-only read found nothing it may serve; nothing was sent. */
    CACHE_MISS,

    /** A request that must reach the server was made while network access was turned off. */
    NETWORK_DISABLED,

    /** A write the mutation queue cannot hold could not be sent for want of a connection. */
    MUTATION_NOT_QUEUEABLE,

    /**
     * Why a sync pass stopped with writes still queued: status and code, when the server sent them.
     */
    SYNC_FAILED,

    /** The app's access-token callback answered with a failure. */
    ACCESS_TOKEN,

    /** A description could not be read: malformed JSON, a missing field, an unknown one. */
    INVALID_REQUEST,

    /** A description asked for something this build does not offer, such as a filter operator. */
    UNSUPPORTED,

    /**
     * The library refused an argument, such as an uncacheable object path or a retry it forbids.
     */
    INVALID_ARGUMENT,

    /** A response could not be decoded. The message says where, and names no payload or type. */
    DECODING,

    /** Anything else: a defect, reported rather than thrown. [BridgeError.debugType] names it. */
    UNEXPECTED,
}
