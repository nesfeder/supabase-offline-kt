// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

// The mutation queue, the rejected list and sync. "The current user" is the client's one identity:
// every request through the configuration's access-token closure speaks for it, so an app that
// serves several users through that closure clears local data when the user changes.

extension SupabaseOfflineClient {

    /// The current user's queued writes, of `table` or of every table, as the queue changes.
    ///
    /// Iterate it for the list now and each change; ``PendingMutationSequence/first()`` reads it once.
    public func pendingMutations(table: String? = nil) -> PendingMutationSequence {
        PendingMutationSequence(
            live: liveSequence { core, yield, finish in
                let stopper = SubscriptionStopper()
                let handle = core.observePendingMutations(
                    table: table,
                    onMutations: { mutations in
                        do {
                            yield(try mutations.map(PendingMutation.init(core:)))
                        } catch {
                            finish(error)
                            stopper.stop()
                        }
                    },
                    onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
                )
                stopper.attach(handle)
                return handle
            },
            current: { [self] in
                try await connection.call { core, complete in
                    core.pendingMutations(table: table) { mutations, failure in
                        complete(coreResult(mutations, failure) { try requiredValue($0).map(PendingMutation.init(core:)) })
                    }
                }
            }
        )
    }

    /// How many of the current user's writes wait, of `table` or of every table: the writes
    /// ``pendingMutations(table:)`` lists.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func pendingMutationCount(table: String? = nil) async throws -> Int {
        try await connection.call { core, complete in
            core.pendingMutationCount(table: table) { count, failure in
                complete(coreResult(count, failure) { try requiredValue($0).intValue })
            }
        }
    }

    /// ``pendingMutationCount(table:)`` as the queue changes: the count now, then each count that
    /// differs from the last.
    public func pendingMutationCountSequence(table: String? = nil) -> PendingMutationCountSequence {
        PendingMutationCountSequence(
            live: liveSequence { core, yield, finish in
                core.observePendingMutationCount(
                    table: table,
                    onCount: { count in yield(count.intValue) },
                    onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
                )
            }
        )
    }

    /// Removes one of the current user's queued writes without sending it: destructive. Its rows
    /// show again as the server last sent them, and what waited behind it is sent.
    ///
    /// When the write is on the wire, this waits for that send to end, then discards it if it is
    /// still queued. An id that is not one of the current user's queued writes is ignored.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func discardPendingMutation(id: Int64) async throws {
        try await connection.call { core, complete in
            core.discardPendingMutation(id: id) { failure in complete(coreResult(failure)) }
        }
    }

    /// The current user's writes the server refused for good on replay, and those the client could
    /// no longer replay exactly, oldest first, as the list changes.
    ///
    /// Iterate it for the list now and each change; ``RejectedMutationSequence/first()`` and
    /// ``rejectedMutationList()`` read it once. Entries stay on the device across restarts until
    /// each is dismissed or retried.
    public func rejectedMutations() -> RejectedMutationSequence {
        RejectedMutationSequence(
            live: liveSequence { core, yield, finish in
                let stopper = SubscriptionStopper()
                let handle = core.observeRejectedMutations(
                    onMutations: { mutations in
                        do {
                            yield(try mutations.map(RejectedMutation.init(core:)))
                        } catch {
                            finish(error)
                            stopper.stop()
                        }
                    },
                    onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
                )
                stopper.attach(handle)
                return handle
            },
            current: { [self] in try await rejectedMutationList() }
        )
    }

    /// The current user's entries on the rejected list, oldest first, read once: the list
    /// ``rejectedMutations()`` gives first.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed, `decoding` for an
    ///   entry the package cannot read.
    public func rejectedMutationList() async throws -> [RejectedMutation] {
        try await connection.call { core, complete in
            core.rejectedMutations { mutations, failure in
                complete(coreResult(mutations, failure) { try requiredValue($0).map(RejectedMutation.init(core:)) })
            }
        }
    }

    /// Removes an entry from the rejected list for good; its write is lost. An id that is not one of
    /// the current user's entries is ignored.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func dismissRejectedMutation(id: Int64) async throws {
        try await connection.call { core, complete in
            core.dismissRejectedMutation(id: id) { failure in complete(coreResult(failure)) }
        }
    }

    /// Queues a rejected write again, at the end of the current user's queue under a new id, and
    /// asks for a sync pass; the entry leaves the rejected list.
    ///
    /// For when the cause has been fixed, such as a missing parent row or a policy. An id that is
    /// not one of the current user's entries is ignored.
    ///
    /// - Throws: ``OfflineError``: `invalidRequest` when the entry's ``RejectedMutation/isRetryable``
    ///   is `false`; `clientClosed` once the client is closed.
    public func retryRejectedMutation(id: Int64) async throws {
        try await connection.call { core, complete in
            core.retryRejectedMutation(id: id) { failure in complete(coreResult(failure)) }
        }
    }

    /// The current user's writes parked because their row moved on the server and the conflict was
    /// not settled during the replay, oldest first, as the list changes.
    ///
    /// Iterate it for the list now and each change; ``ConflictedMutationSequence/first()`` and
    /// ``conflictedMutationList()`` read it once. Only a write to a table with a version column
    /// (``Config/versionColumns``) is ever parked, and entries stay on the device across restarts
    /// until each is resolved or dismissed.
    public func conflictedMutations() -> ConflictedMutationSequence {
        ConflictedMutationSequence(
            live: liveSequence { core, yield, finish in
                let stopper = SubscriptionStopper()
                let handle = core.observeConflictedMutations(
                    onMutations: { mutations in
                        do {
                            yield(try mutations.map(ConflictedMutation.init(core:)))
                        } catch {
                            finish(error)
                            stopper.stop()
                        }
                    },
                    onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
                )
                stopper.attach(handle)
                return handle
            },
            current: { [self] in try await conflictedMutationList() }
        )
    }

    /// The current user's entries on the conflicted list, oldest first, read once: the list
    /// ``conflictedMutations()`` gives first.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed, `decoding` for an
    ///   entry the package cannot read.
    public func conflictedMutationList() async throws -> [ConflictedMutation] {
        try await connection.call { core, complete in
            core.conflictedMutations { mutations, failure in
                complete(coreResult(mutations, failure) { try requiredValue($0).map(ConflictedMutation.init(core:)) })
            }
        }
    }

    /// Settles an entry of the conflicted list: ``ConflictResolution/keepLocal`` and
    /// ``ConflictResolution/apply(columns:)`` queue the write again at the end of the queue under a
    /// new id, preconditioned on ``ConflictedMutation/serverVersion``, and ask for a sync pass;
    /// ``ConflictResolution/keepServer`` drops it. Either way it leaves the list.
    ///
    /// When the row has moved again since, the replay finds a new conflict. An id that is not one of
    /// the current user's entries is ignored.
    ///
    /// - Throws: ``OfflineError``: `invalidRequest` for ``ConflictResolution/hold``, for an apply
    ///   with no column or on an entry that is not an update of a row the server still held, and
    ///   for a keep-local of a row that was gone or a body too large to keep; `encoding` for
    ///   columns that cannot be encoded; `clientClosed` once the client is closed.
    public func resolveConflictedMutation(id: Int64, resolution: ConflictResolution) async throws {
        let columnsJSON = try resolution.coreColumnsJSON()
        try await connection.call { core, complete in
            core.resolveConflictedMutation(id: id, decision: resolution.coreDecision, columnsJson: columnsJSON) { failure in
                complete(coreResult(failure))
            }
        }
    }

    /// Removes an entry from the conflicted list for good, without sending its write. An id that is
    /// not one of the current user's entries is ignored.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func dismissConflictedMutation(id: Int64) async throws {
        try await connection.call { core, complete in
            core.dismissConflictedMutation(id: id) { failure in complete(coreResult(failure)) }
        }
    }

    /// Sends the current user's queued writes now, of `table` or of every table, in the order they
    /// were made, and says what happened.
    ///
    /// A write the server refuses for good moves to the rejected list and is reported in
    /// ``SyncReport/rejected``; it is never thrown. A failure that may pass (no connection, a `401`,
    /// `408`, `429` or `5xx`) stops the pass with the rest still queued, and
    /// ``SyncStatus/lastSyncError`` says why. It returns promptly offline, and sends nothing while
    /// network access is off. When a pass is already sending the same writes, it waits for that pass
    /// and runs again, so it returns once nothing more can be sent now.
    ///
    /// No write is sent ahead of an earlier one, whichever table it is in: a sync of one table stops,
    /// without sending it, at the first write that has an earlier write of the user still queued in
    /// another table (which may be the parent row it needs), and counts what it held back in
    /// ``SyncReport/heldBehindOtherTablesCount``. The report's counts are this call's own; a pass
    /// the client ran by itself just before is counted nowhere in it.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    @discardableResult
    public func syncPendingMutations(table: String? = nil) async throws -> SyncReport {
        try await connection.call { core, complete in
            core.syncPendingMutations(table: table) { report, failure in
                complete(coreResult(report, failure) { try SyncReport(core: try requiredValue($0)) })
            }
        }
    }
}
