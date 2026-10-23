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
     * the result is [MutationOutcome.Queued]: no connection or no answer; an answer that says the
     * request did not finish, so the write may not have run or may pass later (a `408`, `429`,
     * `502`, `503`, `504`, `520`, `522` or `524`); a `401` for a token PostgREST refused as expired
     * or unreadable (`PGRST301`, `PGRST303`), as when the device's clock runs behind the server's;
     * an expired token that could not be refreshed; PostgREST's schema cache not knowing the table
     * or a column yet (`PGRST204`, `PGRST205`; at the tenth such refusal, counted at least 30
     * seconds apart, the write is refused instead); earlier writes still pending; network access
     * turned off; or a session still restoring. It is then sent by a later pass, and a refusal is
     * kept on the rejected list instead of being thrown. A write sent at once that the server
     * refuses otherwise, or fails with another `5xx`, such as a `500` from the database, is thrown
     * and does not stay queued. Cached reads show a queued write at once.
     *
     * Some writes cannot be queued faithfully and are sent at once instead, as with [OnlineOnly]: a
     * dry run (a write carrying `Prefer: tx=rollback`, as supabase-kt 3.8's `dryRun()` writes it)
     * or one whose request could not be inspected to tell whether it is one, an UPDATE or DELETE
     * whose filter the queue cannot record exactly, a write whose user cannot be read from the
     * stored session, a write to a schema other than the client's default (the queue replays to the
     * default schema only), and a write that was on its way into the queue while local data was
     * cleared. When one of them cannot be sent for want of a connection, it throws
     * [MutationNotQueueableException] with that failure as its cause.
     *
     * A dry run commits on a server that ignores the rollback, as a hosted Supabase project does
     * unless its PostgREST lets a request choose (`db-tx-end`). The library reads the server's
     * answer: a write it did not roll back is applied like any other, patched into the cache, and
     * logged once per client at WARNING.
     */
    public data object QueueIfOffline : MutationStrategy
}
