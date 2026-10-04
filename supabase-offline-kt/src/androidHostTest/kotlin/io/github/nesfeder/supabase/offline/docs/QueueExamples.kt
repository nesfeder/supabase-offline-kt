// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.offline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The documentation site's pages on writes, the queue, failures and signing out include the
// sections of this file between the `--8<--` markers, so every Kotlin sample there compiles.
// Nothing here runs.

/** A refused write as a screen lists it. */
internal data class RefusedWrite(
    val id: Long,
    val table: String,
    val reason: String,
    val canRetry: Boolean,
)

/** What a screen tells its user about a write it just made, or `null` when all went well. */
internal suspend fun writeFailureMessage(supabase: SupabaseClient, todo: Todo): String? {
    // --8<-- [start:write-failure]
    val message: String? =
        try {
            supabase.offline.from("todos").insert(todo) // Applied or Queued: both are fine
            null
        } catch (refused: PostgrestRestException) {
            // Sent at once and refused. Show PostgREST's own error and code: the exception's
            // message also carries the request's URL, with its filter values
            "Not saved: ${refused.error} (${refused.code})"
        } catch (notQueueable: MutationNotQueueableException) {
            // A write the queue cannot hold, and no connection to send it now
            "This change needs a connection"
        }
    // --8<-- [end:write-failure]
    return message
}

/** The rejected list as a screen shows it, newest first. */
internal fun refusedWrites(supabase: SupabaseClient): Flow<List<RefusedWrite>> {
    // --8<-- [start:rejected-list]
    val refused: Flow<List<RefusedWrite>> =
        supabase.offline.rejectedMutations().map { entries ->
            entries.asReversed().map { entry ->
                val error = entry.serverError
                RefusedWrite(
                    id = entry.id,
                    table = entry.table,
                    reason =
                        if (error == null) "It can no longer be sent as it was made"
                        else "The server answered HTTP ${error.status} · ${error.code}",
                    canRetry = entry.isRetryable,
                )
            }
        }
    // --8<-- [end:rejected-list]
    return refused
}

/** The two things a user can do with a refused write. */
internal suspend fun resolveRefusedWrite(
    supabase: SupabaseClient,
    write: RefusedWrite,
    retry: Boolean,
) {
    // --8<-- [start:rejected-actions]
    if (retry && write.canRetry) {
        supabase.offline.retryRejectedMutation(write.id) // queued again, at the end, and sent
    } else {
        supabase.offline.dismissRejectedMutation(write.id) // gone for good
    }
    // --8<-- [end:rejected-actions]
}

/** Signing a user out for good: what can still be sent goes, then everything on the device. */
internal suspend fun signOutForGood(supabase: SupabaseClient) {
    // --8<-- [start:sign-out]
    // Send what can still be sent as this user; offline this returns at once
    supabase.offline.syncPendingMutations()
    try {
        supabase.auth.signOut()
    } finally {
        // Offline, signOut() throws and supabase-kt keeps the session: clear the device anyway.
        // Every write still queued is deleted here, never sent
        supabase.offline.clearLocalData()
    }
    // --8<-- [end:sign-out]
}

/** Deleting a user's rows on the server, then everything on the device. */
internal suspend fun deleteMyData(supabase: SupabaseClient, userId: String) {
    // --8<-- [start:delete-my-data]
    val network = supabase.offline.syncStatus.value.network
    check(network != NetworkStatus.OFFLINE && network != NetworkStatus.DISABLED) {
        "Deleting your data needs a connection"
    }
    // First, so no queued write can be replayed after the deletes and leave a row behind
    supabase.offline.clearLocalData()
    // Sent now or not at all: a queued delete could outlive the user it is for
    supabase.offline.from("todos").delete(mutationStrategy = MutationStrategy.OnlineOnly) {
        filter { eq("user_id", userId) }
    }
    supabase.auth.signOut()
    // --8<-- [end:delete-my-data]
}
