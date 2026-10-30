// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.docs

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.Order
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.offline
import kotlinx.serialization.Serializable

// The documentation site's "Android apps in Compose" page includes the sections of this file
// between the `--8<--` markers, so every listing there compiles, on the sample's own Compose and
// lifecycle dependencies. The sample's screens collect through a ViewModel; these are what no
// screen of the sample makes: the plugin's flows collected in a composable itself. Nothing here
// runs.

/** A row of the `todos` table the page's example reads. */
@Serializable private data class Todo(val id: String, val title: String, val done: Boolean = false)

// --8<-- [start:live-read]
/** The open or the done todos, read live for as long as this is on screen. */
@Composable
fun TodoList(supabase: SupabaseClient, done: Boolean) {
    // One flow per query, kept across recompositions: another `done` starts another read, and
    // the read ends when the composable leaves the screen.
    val snapshots =
        remember(supabase, done) {
            supabase.offline.from("todos").selectAsFlow {
                filter { eq("done", done) }
                order("title", Order.ASCENDING)
            }
        }
    // Collected while the screen is started, not in the background; null until the first value.
    val snapshot = snapshots.collectAsStateWithLifecycle(initialValue = null).value
    val result = snapshot?.result
    if (snapshot == null || result == null) {
        Text("Loading…") // nothing known yet: nothing cached, and no queued write to show
    } else {
        val todos = remember(result) { result.decodeList<Todo>() }
        LazyColumn {
            if (snapshot.isFromCache) item { Text("Showing this device's copy") }
            items(todos, key = { it.id }) { todo -> Text(todo.title) }
        }
    }
}

// --8<-- [end:live-read]

// --8<-- [start:status]
/** One line about syncing, straight from the plugin's status. */
@Composable
fun SyncLine(supabase: SupabaseClient) {
    // A StateFlow always has a value, so nothing is drawn before the first one.
    val status by supabase.offline.syncStatus.collectAsStateWithLifecycle()
    val waiting = status.pendingMutationCount
    val offline =
        status.network == NetworkStatus.OFFLINE || status.network == NetworkStatus.DISABLED
    Text(
        when {
            offline -> "Offline: $waiting changes are saved on this device"
            status.isSyncing -> "Sending $waiting changes"
            waiting > 0 -> "$waiting changes waiting"
            else -> "Up to date"
        }
    )
}

// --8<-- [end:status]
