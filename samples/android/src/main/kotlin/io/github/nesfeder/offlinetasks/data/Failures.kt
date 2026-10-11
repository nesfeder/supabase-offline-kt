// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

/**
 * Simulate offline is on, and the request needed the server. It is a [NetworkUnavailableException]
 * too, so everything that handles being offline handles it.
 */
class NetworkSwitchedOffException(cause: Throwable? = null) : NetworkUnavailableException(cause)

/** A read that may use only the device's copy found nothing saved for its query. */
class NotSavedOnDeviceException(cause: Throwable? = null) :
    Exception("Nothing is saved on this device for it", cause)

/**
 * A write the mutation queue cannot hold, such as a delete filtered by a group of conditions, could
 * not be sent for want of a connection. Nothing was queued.
 *
 * @property libraryMessage the library's own sentence, which says which kind of write it was.
 */
class WriteNeedsConnectionException(val libraryMessage: String, cause: Throwable? = null) :
    Exception(libraryMessage, cause)

/**
 * Why the last replay stopped with writes still queued. They stay queued and go when it passes.
 *
 * @property httpStatus the server's HTTP status, or null when the network failed.
 * @property code the PostgREST or Postgres code the server gave, if any.
 * @property isConnectivityFailure true when the network, not the server, stopped it.
 */
class SyncStoppedException(
    val httpStatus: Int?,
    val code: String?,
    val isConnectivityFailure: Boolean,
    cause: Throwable? = null,
) : Exception("The last sync stopped early", cause)

/** The session expired and could not be refreshed; the server took the request as no one's. */
class SessionExpiredException(cause: Throwable? = null) : Exception("The session expired", cause)

/**
 * The server refused a request that was not a write of the user's: a read, a function or a count.
 *
 * @property status the HTTP status.
 * @property code the PostgREST or Postgres code, if any.
 */
class ServerRefusedException(
    val status: Int,
    val code: String?,
    message: String,
    cause: Throwable?,
) : Exception(message, cause)

/**
 * The library refused a request before sending it: two top-level filters on one column, or a retry
 * of a refused write that can no longer be sent as it was made.
 *
 * @property libraryMessage the library's own sentence.
 */
class InvalidRequestException(val libraryMessage: String, cause: Throwable? = null) :
    Exception(libraryMessage, cause)

/** A row from the server did not decode into the app's type, or a value did not encode. */
class RowUnreadableException(cause: Throwable? = null) : Exception("A row could not be read", cause)

/** The Supabase client is closed, or was built without a plugin the call needs. */
class ClientUnavailableException(cause: Throwable? = null) :
    Exception("The Supabase client is unavailable", cause)

/** A failure the offline library reports that this version of the app does not know by name. */
class UnknownLibraryFailureException(cause: Throwable? = null) :
    Exception("The offline library reported a failure", cause)
