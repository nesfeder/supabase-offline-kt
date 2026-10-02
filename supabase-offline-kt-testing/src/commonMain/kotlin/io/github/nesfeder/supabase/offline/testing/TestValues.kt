// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.internal.testing.ValueConstructors
import kotlin.time.Instant

/*
 * Values a test double returns or throws in place of the library's. Their constructors are internal
 * so that the library can add fields without breaking a caller; these factories take every field.
 */

/**
 * The outcome of a write the server applied, as a test double of the app's own returns it.
 *
 * @param result the server's response.
 */
@OptIn(OfflineInternal::class)
public fun appliedOutcome(result: PostgrestResult): MutationOutcome.Applied =
    ValueConstructors.applied(result)

/**
 * The outcome of a write that waits in the mutation queue.
 *
 * @param pendingMutation the queued write.
 */
@OptIn(OfflineInternal::class)
public fun queuedOutcome(pendingMutation: PendingMutation): MutationOutcome.Queued =
    ValueConstructors.queued(pendingMutation)

/**
 * A Storage object held in the file cache, as the offline bucket reports one.
 *
 * @param path where the file is.
 * @param fetchedAt when it was downloaded.
 * @param sizeBytes its size.
 */
@OptIn(OfflineInternal::class)
public fun cachedFile(path: String, fetchedAt: Instant, sizeBytes: Long): CachedFile =
    ValueConstructors.cachedFile(path = path, fetchedAt = fetchedAt, sizeBytes = sizeBytes)

/**
 * The failure of a read that may use only the cache and found nothing there.
 *
 * @param message what the exception says.
 */
@OptIn(OfflineInternal::class)
public fun cacheMissException(
    message: String = "No valid cached data found for this query"
): CacheMissException = ValueConstructors.cacheMissException(message)

/**
 * The failure of a write the mutation queue could not hold, sent at once and refused for want of a
 * connection.
 *
 * @param cause the connectivity failure.
 * @param message what the exception says.
 */
@OptIn(OfflineInternal::class)
public fun mutationNotQueueableException(
    cause: Throwable,
    message: String = "The write could not be queued, and could not be sent",
): MutationNotQueueableException = ValueConstructors.mutationNotQueueableException(message, cause)

/**
 * The failure of a request asked for while the app had turned the library's network access off.
 *
 * @param message what the exception says.
 */
@OptIn(OfflineInternal::class)
public fun networkDisabledException(
    message: String = "The library's network access is turned off"
): NetworkDisabledException = ValueConstructors.networkDisabledException(message)
