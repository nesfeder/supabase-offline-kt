// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalObjCRefinement::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.PendingMutation
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlin.time.Instant

/**
 * The internal constructors of the public value types, for the factories of the
 * `supabase-offline-kt-testing` artifact; the library keeps the constructors themselves internal,
 * so it can add fields without breaking a caller. Each function takes the constructor's own
 * parameters.
 */
@OfflineInternal
@HiddenFromObjC
public object ValueConstructors {

    /** A [MutationOutcome.Applied] carrying [result]. */
    public fun applied(result: PostgrestResult): MutationOutcome.Applied =
        MutationOutcome.Applied(result)

    /** A [MutationOutcome.Queued] carrying [pendingMutation]. */
    public fun queued(pendingMutation: PendingMutation): MutationOutcome.Queued =
        MutationOutcome.Queued(pendingMutation)

    /** A [CachedFile] with the given fields. */
    public fun cachedFile(path: String, fetchedAt: Instant, sizeBytes: Long): CachedFile =
        CachedFile(path = path, fetchedAt = fetchedAt, sizeBytes = sizeBytes)

    /** A [CacheMissException] with [message]. */
    public fun cacheMissException(message: String): CacheMissException = CacheMissException(message)

    /** A [MutationNotQueueableException] with [message] and [cause]. */
    public fun mutationNotQueueableException(
        message: String,
        cause: Throwable,
    ): MutationNotQueueableException = MutationNotQueueableException(message, cause)

    /** A [NetworkDisabledException] with [message]. */
    public fun networkDisabledException(message: String): NetworkDisabledException =
        NetworkDisabledException(message)
}
