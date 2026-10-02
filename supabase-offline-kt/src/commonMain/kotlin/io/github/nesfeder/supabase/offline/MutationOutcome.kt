// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.result.PostgrestResult

/**
 * What became of a write made through [OfflinePostgrestQueryBuilder]: the server applied it, or it
 * waits in the mutation queue.
 *
 * This is the one place the library's verbs differ from supabase-kt's, which return the server's
 * [PostgrestResult]. A queued write has no server response yet, so a caller that reads the written
 * rows back decides what to do without one: build them from what it sent, or wait for a later read.
 * A caller that ignores the result needs no change.
 *
 * The set is closed: these two cases are every outcome a write can have, so a `when` over them
 * stays exhaustive.
 */
public sealed interface MutationOutcome {

    /**
     * The server applied the write.
     *
     * @property result the server's response, to decode as supabase-kt's verbs' result is decoded.
     */
    @ConsistentCopyVisibility
    public data class Applied internal constructor(public val result: PostgrestResult) :
        MutationOutcome

    /**
     * The write waits in the mutation queue and is sent by a later pass. It has no server response
     * yet.
     *
     * @property pendingMutation the queued write, whose [PendingMutation.id] identifies it from now
     *   on.
     */
    @ConsistentCopyVisibility
    public data class Queued internal constructor(public val pendingMutation: PendingMutation) :
        MutationOutcome

    /** Whether the write waits in the mutation queue. */
    public val isQueued: Boolean
        get() = this is Queued

    /**
     * The server's response when the write was [Applied], or `null` when it was [Queued].
     *
     * For a caller that only needs "the result, if there is one", such as `upsert(row) { select()
     * }.resultOrNull()?.decodeSingle<Row>()`.
     *
     * @return the [Applied.result], or `null`.
     */
    public fun resultOrNull(): PostgrestResult? = (this as? Applied)?.result
}
