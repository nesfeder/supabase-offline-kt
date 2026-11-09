// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.httpStatusOrNull as engineHttpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure as engineIsNetworkOrConnectivityFailure

/**
 * Whether this failure is the network's rather than the server's: a timeout, or an I/O failure such
 * as supabase-kt's `HttpRequestException`, here or in a cause. A cancellation, or anything carrying
 * an HTTP status, never is.
 *
 * It is the same test the library applies to decide when a read falls back to the cache and a write
 * is queued. Use it to tell an expected offline failure from a server's error.
 *
 * @return `true` for a connectivity failure.
 */
public fun Throwable.isNetworkOrConnectivityFailure(): Boolean =
    engineIsNetworkOrConnectivityFailure()

/**
 * The status the server answered with, read from this failure or a cause: a supabase-kt
 * `RestException` (PostgREST's and Storage's errors among them), a Ktor `ResponseException`, or a
 * [SyncFailedException]. For Storage it is the status Storage's error body names, which supabase-kt
 * keeps as its `StorageRestException`'s: hosted Storage may send a refusal on an HTTP 400 with its
 * real status, 404 for a missing object say, in the body.
 *
 * @return the status, or `null` when no failure in the chain carries one, as for a connectivity
 *   failure.
 */
public fun Throwable.httpStatusOrNull(): Int? = engineHttpStatusOrNull()
