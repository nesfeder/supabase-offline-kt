// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

/**
 * Where the library stands with the current user's queued writes and the network: one value to draw
 * a sync banner from.
 *
 * @property network whether the library believes it can reach the network.
 * @property pendingMutationCount the current user's queued writes.
 * @property rejectedMutationCount the current user's entries on the rejected list.
 * @property isSyncing whether a sync pass is sending.
 * @property lastSyncedAtEpochMillis when a pass last left the queue empty, in milliseconds since
 *   1970; `null` until it happens.
 * @property lastSyncError why the last pass stopped with writes queued, a
 *   [BridgeErrorKind.SYNC_FAILED]; `null` once a later pass runs to the end.
 * @property conflictedMutationCount the current user's entries on the conflicted list.
 */
public class BridgeSyncStatus
internal constructor(
    public val network: BridgeNetworkStatus,
    public val pendingMutationCount: Int,
    public val rejectedMutationCount: Int,
    public val isSyncing: Boolean,
    public val lastSyncedAtEpochMillis: Long?,
    public val lastSyncError: BridgeError?,
    public val conflictedMutationCount: Int,
)

/** Whether the library believes it can reach the network; a hint, never a gate. */
public enum class BridgeNetworkStatus {
    /** Nothing has said yet; treated as online. */
    UNKNOWN,

    /** The monitor, or the app's report, says the network is usable. */
    ONLINE,

    /** The monitor, or the app's report, says there is no usable network. */
    OFFLINE,

    /** The app turned network access off with [BridgeClient.disableNetwork]. */
    DISABLED,
}

/**
 * What one call of [BridgeClient.syncPendingMutations] did with the current user's queued writes.
 *
 * @property syncedCount the writes this call sent and the server accepted.
 * @property rejected the writes refused for good during this call, now on the rejected list.
 * @property remainingCount the writes still queued when the call returned.
 * @property alreadyOnServerCount of [syncedCount], the inserts whose rows the server already held.
 * @property heldBehindOtherTablesCount of [remainingCount], the writes a call for one table held
 *   back behind an earlier write of the user to another table; `0` for a call over every table.
 * @property conflictsResolvedCount of [syncedCount], the guarded writes whose conflict was settled
 *   and that were sent.
 * @property conflictsKeptServerCount the guarded writes dropped unsent because the server's row
 *   stands.
 * @property conflicted the guarded writes parked during this call, now on the conflicted list.
 * @property stopReason why the call returned when it did.
 * @property rejectedUploads the queued uploads Storage refused for good during this call, now on
 *   the rejected list beside [rejected], which holds the row writes.
 */
public class BridgeSyncReport
internal constructor(
    public val syncedCount: Int,
    public val rejected: List<BridgeRejectedMutation>,
    public val remainingCount: Int,
    public val alreadyOnServerCount: Int,
    public val heldBehindOtherTablesCount: Int,
    public val conflictsResolvedCount: Int,
    public val conflictsKeptServerCount: Int,
    public val conflicted: List<BridgeConflictedMutation>,
    public val stopReason: BridgeSyncStopReason,
    public val rejectedUploads: List<BridgeRejectedUpload>,
)

/**
 * Why one call of [BridgeClient.syncPendingMutations] returned when it did.
 *
 * @property kind the reason.
 * @property error for [BridgeSyncStopKind.SERVER_FAILURE] and [BridgeSyncStopKind.LOCAL_FAILURE],
 *   the failure, a [BridgeErrorKind.SYNC_FAILED]; `null` for every other kind.
 */
public class BridgeSyncStopReason
internal constructor(public val kind: BridgeSyncStopKind, public val error: BridgeError?)

/** The reasons a sync call returns when it does, as the library names them. */
public enum class BridgeSyncStopKind {
    /** The call went through every write it found queued. */
    COMPLETED,

    /** A write could not reach the server, or got no answer in time. */
    NETWORK_UNAVAILABLE,

    /** The server answered a write with a failure that may pass. */
    SERVER_FAILURE,

    /** A failure on the device stopped the call. */
    LOCAL_FAILURE,

    /** The session did not settle within the call's wait. */
    SESSION_NOT_READY,

    /** The session changed user while the call ran. */
    SESSION_CHANGED,

    /** The app turned network access off. */
    NETWORK_DISABLED,

    /** A call for one table stopped at a write behind an earlier one to another table. */
    HELD_BEHIND_OTHER_TABLES,

    /** Local data was cleared while the call ran. */
    LOCAL_DATA_CLEARED,

    /** A reason the bridge has no kind for; never sent while every reason has one. */
    UNKNOWN,
}
