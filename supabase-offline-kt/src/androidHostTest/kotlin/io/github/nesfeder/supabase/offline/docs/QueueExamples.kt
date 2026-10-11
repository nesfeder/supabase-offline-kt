// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.offline

// The documentation site's pages on writes and signing out include the sections of this file
// between the `--8<--` markers, so every Kotlin listing there compiles. These are what no sample
// makes: the sample apps never sign a user out but to start over, and never send a dry run.
// Nothing here runs.

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

/** Ending the session on this device after a sign-out that could not reach the server. */
internal suspend fun forgetSessionOffline(supabase: SupabaseClient) {
    // --8<-- [start:forget-session]
    try {
        supabase.auth.signOut()
    } catch (offline: HttpRequestException) {
        supabase.auth.clearSession() // forgets the session here, without asking the server
    }
    // --8<-- [end:forget-session]
}
