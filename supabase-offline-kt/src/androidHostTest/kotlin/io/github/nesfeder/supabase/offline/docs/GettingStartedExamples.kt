// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.offline
import io.github.nesfeder.supabase.offline.rpc
import kotlinx.serialization.Serializable

// The documentation site's Getting started pages include the sections of this file between the
// `--8<--` markers, so every Kotlin listing there compiles. The sample apps show everything else;
// these are what no sample makes: supabase-kt's side of the migration page's contrasts, with the
// offline side beside it, and a data directory Android never backs up. Nothing here runs.

/** A row of the `todos` table the site's examples read and write. */
@Serializable internal data class Todo(val id: String, val title: String, val done: Boolean = false)

/** The arguments of the `archive_done` database function the migration page calls. */
@Serializable internal data class ArchiveDone(val before: String)

/** The setup page's Android data directory, which Android never backs up. */
internal fun setupNoBackupDirectory(
    context: Context,
    supabaseUrl: String,
    supabaseKey: String,
): SupabaseClient {
    // --8<-- [start:no-backup-directory]
    val supabase =
        createSupabaseClient(supabaseUrl, supabaseKey) {
            install(Auth)
            install(Postgrest)
            install(Offline) {
                // The database and the file cache; Android never backs up noBackupFilesDir
                dataDirectory = context.noBackupFilesDir.resolve("supabase_offline").path
            }
        }
    // --8<-- [end:no-backup-directory]
    return supabase
}

/** The migration page's calls as supabase-kt takes them. */
internal suspend fun migratingBefore(supabase: SupabaseClient, todo: Todo): List<Todo> {
    // --8<-- [start:before]
    val open = supabase.from("todos").select { filter { eq("done", false) } }.decodeList<Todo>()
    supabase.from("todos").insert(todo)
    supabase.from("todos").update({ set("done", true) }) { filter { eq("id", todo.id) } }
    supabase.from("todos").delete { filter { eq("id", todo.id) } }
    // --8<-- [end:before]
    return open
}

/** The same calls through the plugin: only the receiver changes. */
internal suspend fun migratingAfter(supabase: SupabaseClient, todo: Todo): List<Todo> {
    // --8<-- [start:after]
    val open =
        supabase.offline.from("todos").select { filter { eq("done", false) } }.decodeList<Todo>()
    supabase.offline.from("todos").insert(todo)
    supabase.offline.from("todos").update({ set("done", true) }) { filter { eq("id", todo.id) } }
    supabase.offline.from("todos").delete { filter { eq("id", todo.id) } }
    // --8<-- [end:after]
    return open
}

/** A write whose result is read, as supabase-kt takes it. */
internal suspend fun migratingWriteResultBefore(supabase: SupabaseClient, todo: Todo): Todo {
    // --8<-- [start:write-result-before]
    val saved = supabase.from("todos").insert(todo) { select() }.decodeSingle<Todo>()
    // --8<-- [end:write-result-before]
    return saved
}

/** The same write through the plugin, which may have no answer yet. */
internal suspend fun migratingWriteResultAfter(supabase: SupabaseClient, todo: Todo): Todo {
    // --8<-- [start:write-result-after]
    val saved =
        supabase.offline
            .from("todos")
            .insert(todo) { select() }
            .resultOrNull() // null when the write was queued: there is no answer yet
            ?.decodeSingle<Todo>() ?: todo
    // --8<-- [end:write-result-after]
    return saved
}

/** A suspending call that supabase-kt's inline verbs allow inside the request lambda. */
internal suspend fun migratingSuspendingCall(supabase: SupabaseClient) {
    // --8<-- [start:suspend-in-lambda]
    // supabase-kt: the verb is inline, so the lambda may suspend
    supabase.from("todos").delete { filter { eq("id", currentTodoId()) } }

    // supabase-offline-kt: make the call first, then use its result in the lambda
    val id = currentTodoId()
    supabase.offline.from("todos").delete { filter { eq("id", id) } }
    // --8<-- [end:suspend-in-lambda]
}

/** A request lambda passed inside the parentheses, named so it reaches `request`. */
internal suspend fun migratingNamedRequest(supabase: SupabaseClient, todo: Todo) {
    // --8<-- [start:named-request]
    // supabase-kt: the second parameter is the request
    supabase.from("todos").insert(todo, { select() })

    // supabase-offline-kt: the second parameter is mutationStrategy, so name the lambda
    supabase.offline.from("todos").insert(todo, request = { select() })
    // or move it after the parentheses
    supabase.offline.from("todos").insert(todo) { select() }
    // --8<-- [end:named-request]
}

/** The typed `rpc`, through supabase-kt and through the plugin. */
internal suspend fun migratingTypedRpc(supabase: SupabaseClient) {
    // --8<-- [start:typed-rpc]
    supabase.postgrest.rpc("archive_done", ArchiveDone(before = "2026-10-01"))
    supabase.offline.rpc("archive_done", ArchiveDone(before = "2026-10-01"))
    // --8<-- [end:typed-rpc]
}

/** An id the migration example reads before it builds its request. */
private suspend fun currentTodoId(): String = "todo-1"
