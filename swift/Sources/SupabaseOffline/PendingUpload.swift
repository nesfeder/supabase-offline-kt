// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// One Storage upload waiting in the mutation queue, its bytes kept on the device meanwhile.
///
/// It is a write of the one queue: it is sent in the order it was made among the user's row writes,
/// ``SupabaseOfflineClient/discardPendingMutation(id:)`` takes its id, and
/// ``SyncStatus/pendingMutationCount`` counts it.
public struct PendingUpload: Sendable, Hashable, Identifiable {

    /// The upload's id in the queue, from the sequence ``PendingMutation/id`` comes from: it survives
    /// a restart, and a refusal on replay keeps it.
    public let id: Int64

    /// The bucket the file goes to.
    public let bucketId: String

    /// The object path within the bucket.
    public let path: String

    /// When it was queued, by the device's clock.
    public let createdAt: Date

    /// The file's size, in bytes.
    public let sizeBytes: Int

    /// The content type it is sent with; `nil` when the upload set none, and Storage infers it from
    /// the path.
    public let contentType: String?

    /// Whether it overwrites an object already at the path.
    public let upsert: Bool

    /// Built by the package, and by the testing product's factories.
    package init(
        id: Int64,
        bucketId: String,
        path: String,
        createdAt: Date,
        sizeBytes: Int,
        contentType: String?,
        upsert: Bool
    ) {
        self.id = id
        self.bucketId = bucketId
        self.path = path
        self.createdAt = createdAt
        self.sizeBytes = sizeBytes
        self.contentType = contentType
        self.upsert = upsert
    }
}

extension PendingUpload {

    /// The core's queued `upload`; runs on the core's thread.
    init(core upload: BridgePendingUpload) {
        self.init(
            id: upload.id,
            bucketId: upload.bucketId,
            path: upload.path,
            createdAt: Date(epochMilliseconds: upload.createdAtEpochMillis),
            sizeBytes: Int(upload.sizeBytes),
            contentType: upload.contentType,
            upsert: upload.upsert
        )
    }
}
