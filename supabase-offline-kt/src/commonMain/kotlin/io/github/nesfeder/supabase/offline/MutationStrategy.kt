// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/** Whether a write may wait in the mutation queue, or must reach the server now. */
public sealed interface MutationStrategy {

    /**
     * Sends the write now, and throws when it cannot be sent. Nothing is queued.
     *
     * The write can reach the server before writes still queued from earlier, to the same rows or
     * to a parent row it depends on. Use [QueueIfOffline] for writes that must keep their order.
     */
    public data object OnlineOnly : MutationStrategy

    /**
     * Saves the write to the mutation queue, so it keeps its place among the current user's writes,
     * and keeps it there until the server has accepted or refused it.
     *
     * The write is sent at once when nothing of the same user is pending ahead of it, in any table;
     * the result is [MutationOutcome.Applied] with the server's response. Otherwise it waits and
     * the result is [MutationOutcome.Queued]: no connection or no answer, a `503` or `520` after
     * which it may not have run, earlier writes still pending, or a session still restoring. It is
     * then sent by a later pass, and a refusal is kept on the rejected list instead of being
     * thrown. A write sent at once that the server refuses, or fails with another `5xx`, is thrown
     * and does not stay queued. Cached reads show a queued write at once.
     *
     * Some writes cannot be queued faithfully and are sent at once instead, as with [OnlineOnly]: a
     * `dryRun()` write, an UPDATE or DELETE whose filter the queue cannot record exactly, a write
     * whose user cannot be read from the stored session, and a write to a schema other than the
     * client's default (the queue replays to the default schema only). When one of them cannot be
     * sent for want of a connection, it throws [MutationNotQueueableException] with that failure as
     * its cause.
     */
    public data object QueueIfOffline : MutationStrategy
}
