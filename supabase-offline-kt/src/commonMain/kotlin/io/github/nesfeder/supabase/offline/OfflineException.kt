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
 * A read that may use only the device's cache found nothing there for it: a
 * [ReadStrategy.CacheOnly] or [FlowReadStrategy.CacheOnly] read of a query that is not cached, or
 * cached rows older than its `maxAge`, or of a shape the cache never holds (`single()`, a count,
 * `csv()`, …). No request was sent.
 */
public class CacheMissException internal constructor(message: String) :
    OfflineException(message, null)

/**
 * A [MutationStrategy.QueueIfOffline] write that the mutation queue cannot hold faithfully was sent
 * at once instead, and could not be sent for want of a connection. Nothing was queued.
 *
 * Such writes are:
 * - a `dryRun()` write, or one whose request could not be inspected to tell whether it is one;
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
 * access off. Nothing was sent.
 */
public class NetworkDisabledException internal constructor(message: String) :
    OfflineException(message, null)
