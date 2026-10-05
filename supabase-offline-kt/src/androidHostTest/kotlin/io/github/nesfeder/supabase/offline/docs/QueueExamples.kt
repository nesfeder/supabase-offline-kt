// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.offline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

/** The writes page's two strategies. */
internal suspend fun writeStrategies(supabase: SupabaseClient, todo: Todo) {
    val todos = supabase.offline.from("todos")
    // --8<-- [start:mutation-strategies]
    // Saved to the mutation queue, then sent at once or later, in order (the default)
    todos.insert(todo)

    // Sent now or not at all: never queued, and it can overtake writes still queued
    todos.insert(todo, mutationStrategy = MutationStrategy.OnlineOnly)
    // --8<-- [end:mutation-strategies]
}

/** The writes page's verbs, as supabase-kt takes them. */
internal suspend fun writeVerbs(supabase: SupabaseClient, todo: Todo): List<MutationOutcome> {
    val todos = supabase.offline.from("todos")
    // --8<-- [start:verbs]
    val outcomes =
        listOf(
            todos.insert(todo),
            todos.upsert(todo) { onConflict = "id" },
            todos.update({ set("done", true) }) { filter { eq("id", todo.id) } },
            todos.delete { filter { eq("id", todo.id) } },
        )
    // --8<-- [end:verbs]
    return outcomes
}

/** Two conditions on one column in a write: one `and` group, which is sent at once. */
internal suspend fun deleteWindow(supabase: SupabaseClient, start: String, end: String) {
    // --8<-- [start:same-column-write]
    // Refused: supabase-kt would send only the first condition, and delete more than asked
    // delete { filter { gte("created_at", start); lt("created_at", end) } }

    // Sent as one group. A group is never queued, so offline this throws
    // MutationNotQueueableException instead of waiting
    supabase.offline.from("todos").delete {
        filter {
            and {
                gte("created_at", start)
                lt("created_at", end)
            }
        }
    }
    // --8<-- [end:same-column-write]
}

/** A dry run on supabase-kt 3.6, which has no `dryRun()`. */
@OptIn(SupabaseExperimental::class)
internal suspend fun dryRunInsert(supabase: SupabaseClient, todo: Todo): MutationOutcome {
    // --8<-- [start:dry-run]
    val outcome =
        supabase.offline.from("todos").insert(todo) {
            headers.append("Prefer", "tx=rollback") // sent at once, never queued
        }
    // --8<-- [end:dry-run]
    return outcome
}

/** The current user's queued writes for a screen, and the way to drop one. */
internal suspend fun pendingWrites(
    supabase: SupabaseClient,
    id: Long,
): Flow<List<PendingMutation>> {
    // --8<-- [start:pending]
    // Each write waiting, in the order it will be sent
    val waiting: Flow<List<PendingMutation>> = supabase.offline.pendingMutations("todos")

    // How many, as it changes
    val waitingCount: Flow<Int> = supabase.offline.pendingMutationCountFlow()

    // Drop one for good, unsent: the rows show again as the server last sent them
    supabase.offline.discardPendingMutation(id) // a PendingMutation.id
    // --8<-- [end:pending]
    return waiting.combine(waitingCount) { list, _ -> list }
}

/** A "Sync now" button. */
internal suspend fun syncNow(supabase: SupabaseClient): String {
    // --8<-- [start:sync-now]
    val report = supabase.offline.syncPendingMutations()
    val summary =
        when {
            report.remainingCount == 0 -> "Everything is sent"
            report.heldBehindOtherTablesCount > 0 -> "Some changes wait for others to go first"
            else -> "${report.remainingCount} changes still waiting"
        }
    // report.rejected: what the server refused during this call, now on rejectedMutations()
    // --8<-- [end:sync-now]
    return summary
}

/** One line for a sync banner, from the status. */
internal fun syncBanner(supabase: SupabaseClient): Flow<String?> {
    // --8<-- [start:sync-banner]
    val banner: Flow<String?> =
        supabase.offline.syncStatus.map { status ->
            when {
                status.network == NetworkStatus.DISABLED -> "Offline mode is on"
                status.network == NetworkStatus.OFFLINE && status.pendingMutationCount > 0 ->
                    "Offline: ${status.pendingMutationCount} changes saved on this device"
                status.network == NetworkStatus.OFFLINE -> "Offline"
                status.isSyncing -> "Syncing…"
                status.rejectedMutationCount > 0 -> "Some changes were refused"
                status.lastSyncError != null -> "Can't reach the server: changes will be sent later"
                else -> null
            }
        }
    // --8<-- [end:sync-banner]
    return banner
}

/** An app's own offline switch, and waiting for the status to say so. */
internal suspend fun offlineSwitch(supabase: SupabaseClient, offline: Boolean) {
    // --8<-- [start:network-switch]
    if (offline) supabase.offline.disableNetwork() else supabase.offline.enableNetwork()
    // syncStatus.value.network says DISABLED, or the monitor's view, as soon as the call returns;
    // a flow derived from syncStatus catches up a moment later, so wait for it when you read one
    // --8<-- [end:network-switch]
}

/** Sending the queue when the app comes to the foreground, on Android. */
internal fun syncOnForeground(supabase: SupabaseClient, appScope: CoroutineScope) {
    // --8<-- [start:foreground]
    // In Application.onCreate(); appScope lives as long as the app
    ProcessLifecycleOwner.get()
        .lifecycle
        .addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    appScope.launch { supabase.offline.syncPendingMutations() }
                }
            }
        )
    // --8<-- [end:foreground]
}
