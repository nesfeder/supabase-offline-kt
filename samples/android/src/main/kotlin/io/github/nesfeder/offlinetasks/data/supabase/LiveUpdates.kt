// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.nesfeder.offlinetasks.data.LiveUpdatesStatus
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.applyServerRecord
import io.github.nesfeder.supabase.offline.offline
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Realtime channel's name. */
private const val CHANNEL = "offline-tasks"

/** The schema both tables live in. */
private const val SCHEMA = "public"

/**
 * Changes other devices make to the two tables, applied to the device's cached lists as they
 * happen, through Realtime. It keeps the lists current between refreshes and never replaces them:
 * Realtime does not replay what happened while the app was away, so the lists still refresh.
 */
class LiveUpdates(private val supabase: SupabaseClient) {

    // --8<-- [start:live-updates]
    private val offline = supabase.offline // the plugin, as every call below reaches it

    /**
     * Subscribes to both tables and applies each change until the caller is canceled, then leaves
     * the channel. [onConnected] hears whether the channel is subscribed.
     */
    suspend fun applyChangesUntilCanceled(onConnected: (Boolean) -> Unit) {
        val channel = supabase.channel(CHANNEL)
        val tasks = channel.postgresChangeFlow<PostgresAction>(SCHEMA) { table = Tables.TASKS }
        val photos =
            channel.postgresChangeFlow<PostgresAction>(SCHEMA) { table = Tables.TASK_PHOTOS }
        try {
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) { tasks.collect(::applyTaskChange) }
                launch(start = CoroutineStart.UNDISPATCHED) { photos.collect(::applyPhotoChange) }
                launch {
                    channel.status.collect { onConnected(it == RealtimeChannel.Status.SUBSCRIBED) }
                }
                channel.subscribe()
            }
        } finally {
            withContext(NonCancellable) { supabase.realtime.removeChannel(channel) }
        }
    }

    // --8<-- [end:live-updates]

    // --8<-- [start:apply-records]
    /** A change to `tasks`: the row decoded into the app's own type and applied, or a delete. */
    suspend fun applyTaskChange(change: PostgresAction) {
        when (change) {
            is PostgresAction.Insert ->
                offline.applyServerRecord(Tables.TASKS, change.decodeRecord<TaskRecord>())
            is PostgresAction.Update ->
                offline.applyServerRecord(Tables.TASKS, change.decodeRecord<TaskRecord>())
            // With row-level security, an old record carries the key columns only: enough.
            is PostgresAction.Delete -> offline.applyServerDelete(Tables.TASKS, change.oldRecord)
            is PostgresAction.Select -> Unit // never sent for these subscriptions
        }
    }

    /** A change to `task_photos`, applied as the JSON the server sent. */
    suspend fun applyPhotoChange(change: PostgresAction) {
        when (change) {
            is PostgresAction.Insert -> offline.applyServerRecord(Tables.TASK_PHOTOS, change.record)
            is PostgresAction.Update -> offline.applyServerRecord(Tables.TASK_PHOTOS, change.record)
            is PostgresAction.Delete ->
                offline.applyServerDelete(Tables.TASK_PHOTOS, change.oldRecord)
            is PostgresAction.Select -> Unit
        }
    }
    // --8<-- [end:apply-records]
}

/**
 * What Live updates should be doing: off when switched off; paused with no user, while the network
 * is known to be down, or under Simulate offline; else connecting. The network's return is a new
 * value, so a channel that failed starts again then.
 */
internal fun liveUpdatesWanted(
    switchedOn: Boolean,
    signedIn: Boolean,
    network: NetworkStatus,
): LiveUpdatesStatus =
    when {
        !switchedOn -> LiveUpdatesStatus.Off
        !signedIn || network == NetworkStatus.OFFLINE || network == NetworkStatus.DISABLED ->
            LiveUpdatesStatus.Paused
        else -> LiveUpdatesStatus.Connecting
    }
