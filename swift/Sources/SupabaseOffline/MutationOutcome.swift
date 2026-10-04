// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// What became of a write: the server applied it, or it waits in the mutation queue. The set is
/// closed: no release adds a case.
public enum MutationOutcome<Value: Sendable>: Sendable {

    /// The server applied the write; `result` is its response, the written rows when the write
    /// asked for them with ``OfflineMutationRequestBuilder/select(_:)``.
    case applied(result: OfflineResponse<Value>)

    /// The write waits in the mutation queue and is sent by a later sync; `pendingMutation.id`
    /// identifies it from now on, and a refusal on replay keeps it.
    case queued(pendingMutation: PendingMutation)

    /// Whether the write waits in the mutation queue.
    public var isQueued: Bool {
        if case .queued = self { return true }
        return false
    }

    /// The server's response when it applied the write; `nil` when the write is queued.
    public var result: OfflineResponse<Value>? {
        guard case .applied(let result) = self else { return nil }
        return result
    }
}

extension MutationOutcome {

    /// The core's `outcome`, an applied body decoded by `decode`; runs on the core's thread.
    init(core outcome: BridgeMutationResult, decode: (String) throws -> Value) throws {
        if outcome.isQueued {
            self = .queued(pendingMutation: try PendingMutation(core: try requiredValue(outcome.queued)))
        } else {
            self = .applied(result: try OfflineResponse(core: try requiredValue(outcome.applied), decode: decode))
        }
    }
}
