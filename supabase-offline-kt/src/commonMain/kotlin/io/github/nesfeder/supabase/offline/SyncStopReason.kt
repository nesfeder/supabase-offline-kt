// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * Why a call of [Offline.syncPendingMutations] returned when it did, and so why writes may still be
 * queued after it: [SyncReport.stopReason].
 *
 * The set is open: a later release may add a reason, so a `when` over it keeps an `else` branch. An
 * app may rely on this much:
 * - every report has exactly one reason, and a reason keeps the meaning described here;
 * - [Completed] is the only reason a call ran to the end for; every other one stopped it short;
 * - after any reason but [Completed] and [LocalDataCleared], the writes the call did not send are
 *   still queued, and a later pass sends them;
 * - [NetworkUnavailable], [ServerFailure] and [LocalFailure] are failures, and the call records the
 *   failure as [SyncStatus.lastSyncError]; no other reason is a failure, and none of them records
 *   one there.
 */
public abstract class SyncStopReason internal constructor() {

    /**
     * The call went through every write it found queued: each was sent, refused, parked on the
     * conflicted list or dropped for the server's row. [SyncReport.remainingCount] counts only the
     * writes queued while it ran.
     */
    public data object Completed : SyncStopReason()

    /**
     * A write could not reach the server, or got no answer in time, so the call stopped there. It
     * and the writes after it stay queued, and a later pass sends them once the network is back.
     */
    public data object NetworkUnavailable : SyncStopReason()

    /**
     * The server answered a write with a failure that may pass, so the call stopped there: a `5xx`,
     * `408`, `429` or `401`, PostgREST's schema cache not yet knowing the table or a column, or a
     * `403` or `409` while an earlier write of the user, which may be the row it needs, is still
     * queued. It and the writes after it stay queued for a later pass. A refusal for good is not a
     * stop: it goes to [SyncReport.rejected], and the call goes on.
     *
     * @property error the failure, as [SyncStatus.lastSyncError] holds it: the server's
     *   [SyncFailedException.httpStatus] and [SyncFailedException.code], and nothing of its
     *   message.
     */
    @ConsistentCopyVisibility
    public data class ServerFailure internal constructor(public val error: SyncFailedException) :
        SyncStopReason()

    /**
     * A failure on this device, neither the network's nor the server's, stopped the call: the local
     * database failing, a request header the HTTP client refuses (a line break in the project key
     * or the access token), or the pass canceled by something other than the caller or a clear. The
     * writes it did not send stay queued for a later pass.
     *
     * @property error the failure, as [SyncStatus.lastSyncError] holds it, with no HTTP status.
     */
    @ConsistentCopyVisibility
    public data class LocalFailure internal constructor(public val error: SyncFailedException) :
        SyncStopReason()

    /**
     * The session did not settle (signed in, or signed out) within the call's wait, or could not be
     * told while it restored or refreshed, so nothing more was sent: a write sent then could go out
     * as the wrong user. The writes are sent by a pass once the session settles.
     */
    public data object SessionNotReady : SyncStopReason()

    /**
     * The session changed user while the call ran (a sign-out, a sign-in or another account), so it
     * stopped before its next request. The new user's writes go in a later pass.
     */
    public data object SessionChanged : SyncStopReason()

    /**
     * The app turned the client's network access off with [Offline.disableNetwork], so nothing more
     * was sent. The writes are sent once [Offline.enableNetwork] turns it back on.
     */
    public data object NetworkDisabled : SyncStopReason()

    /**
     * A call for one table stopped at a write that has an earlier write of the user still queued in
     * another table: no write is sent ahead of an earlier one.
     * [SyncReport.heldBehindOtherTablesCount] counts what it held back, and a call over every table
     * sends them in order. Never the reason of a call over every table.
     */
    public data object HeldBehindOtherTables : SyncStopReason()

    /**
     * Local data was cleared ([Offline.clearLocalData]) while the call ran: nothing was sent after
     * that, and the writes the clear removed are gone.
     */
    public data object LocalDataCleared : SyncStopReason()
}
