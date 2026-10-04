// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * A failure the library itself reports, rather than one supabase-kt or the server raised, which
 * pass through unchanged.
 *
 * The hierarchy is open: a release may add a subclass, so match the ones you handle and keep a
 * branch for the rest.
 */
public abstract class OfflineException internal constructor(message: String, cause: Throwable?) :
    Exception(message, cause)

/**
 * A read that may use only the device's cache found nothing there for it. No request was sent.
 *
 * A one-shot [ReadStrategy.CacheOnly] read throws it for a query that is not cached, for cached
 * rows older than its `maxAge`, and for a shape the cache never holds (`single()`, a count,
 * `csv()`, …). A reactive [FlowReadStrategy.CacheOnly] read fails with it only for such a shape:
 * for a query that is not cached it emits a snapshot whose rows are `null`.
 */
public class CacheMissException internal constructor(message: String) :
    OfflineException(message, null)

/**
 * A [MutationStrategy.QueueIfOffline] write that the mutation queue cannot hold faithfully was sent
 * at once instead, and could not be sent for want of a connection. Nothing was queued.
 *
 * Such writes are:
 * - a dry run, a write carrying `Prefer: tx=rollback` (supabase-kt 3.8's `dryRun()`, or the header
 *   added by hand), or one whose request could not be inspected to tell whether it is one;
 * - an UPDATE or DELETE whose filter the queue cannot record exactly;
 * - a write whose user cannot be read from the stored session;
 * - a write to a schema other than the client's default;
 * - a write that was on its way into the queue while local data was cleared.
 *
 * The message says which; [cause] is the connectivity failure, so [isNetworkOrConnectivityFailure]
 * is `true` for it. While the client is closing, such a write fails with the client's closed error
 * instead.
 */
public class MutationNotQueueableException internal constructor(message: String, cause: Throwable) :
    OfflineException(message, cause)

/**
 * A request that must reach the server was asked for while the app had turned the library's network
 * access off with [Offline.disableNetwork]. Nothing was sent.
 *
 * It is what a [ReadStrategy.NetworkOnly] read, a [MutationStrategy.OnlineOnly] write, an
 * [Offline.rpc] or a Storage call other than a cached download throws while access is off; a read
 * the cache can answer is answered from it, and a write the mutation queue can hold is queued, as
 * offline. [isNetworkOrConnectivityFailure] is `true` for it, so code that handles being offline
 * handles it too.
 */
public class NetworkDisabledException internal constructor(message: String) :
    OfflineException(message, null)

/**
 * Why a sync pass stopped with writes still queued, as [SyncStatus.lastSyncError] reports it: the
 * network could not be reached, the server answered with a failure that may pass (a `5xx`, `408`,
 * `429` or `401`, or PostgREST's schema cache not yet knowing the table or a column), or a local
 * failure stopped it. The writes stay queued and are sent by a later pass.
 *
 * The library builds it from what stopped the pass and keeps nothing else of that failure, whose
 * message may carry a row, a filter value or a token. [isNetworkOrConnectivityFailure] is `true`
 * for one the network caused, and [httpStatusOrNull] gives the server's status.
 *
 * @property httpStatus the HTTP status the server answered with, or `null` when it did not answer.
 * @property code PostgREST's error code (such as `PGRST000`) or the Postgres SQLSTATE, when the
 *   server sent one.
 */
public class SyncFailedException
internal constructor(
    message: String,
    public val httpStatus: Int?,
    public val code: String?,
    internal val isConnectivityFailure: Boolean,
) : OfflineException(message, null)
