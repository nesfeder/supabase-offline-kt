// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOfflineCore

/// What became of an upload made with a ``MutationStrategy``: Storage stored the file, or the upload
/// waits in the mutation queue with its bytes kept on the device. The set is closed: no release adds
/// a case.
public enum UploadOutcome: Sendable, Hashable {

    /// Storage stored the file; `response` is where.
    case applied(response: OfflineBucketApi.FileUploadResponse)

    /// The upload waits in the mutation queue and is sent by a later sync, in the order it was made
    /// among the user's writes; `pendingUpload.id` identifies it from now on, and a refusal on
    /// replay keeps it.
    case queued(pendingUpload: PendingUpload)

    /// Whether the upload waits in the mutation queue.
    public var isQueued: Bool {
        if case .queued = self { return true }
        return false
    }

    /// Storage's response when it stored the file; `nil` when the upload is queued.
    public var response: OfflineBucketApi.FileUploadResponse? {
        guard case .applied(let response) = self else { return nil }
        return response
    }
}

extension UploadOutcome {

    /// The core's `outcome`; runs on the core's thread.
    init(core outcome: BridgeUploadResult) throws {
        if outcome.isQueued {
            self = .queued(pendingUpload: PendingUpload(core: try requiredValue(outcome.queued)))
        } else {
            self = .applied(response: try OfflineBucketApi.FileUploadResponse(coreText: requiredValue(outcome.applied)))
        }
    }
}
