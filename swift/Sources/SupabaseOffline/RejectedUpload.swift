// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// A queued Storage upload that Storage refused for good on replay, or one the client could no
/// longer send, kept on the rejected list with its bytes.
///
/// It is an entry of the one rejected list: ``SupabaseOfflineClient/dismissRejectedMutation(id:)``
/// and ``SupabaseOfflineClient/retryRejectedMutation(id:)`` take its id, ``SyncStatus/rejectedMutationCount``
/// counts it, and it shares the list's bound of 100 entries.
public struct RejectedUpload: Sendable, Hashable, Identifiable {

    /// The upload's id, as it had in the queue and in ``UploadOutcome/queued(pendingUpload:)``.
    public let id: Int64

    /// The bucket the file was going to; empty for an entry this release cannot read, which can only
    /// be dismissed.
    public let bucketId: String

    /// The object path within the bucket; empty as ``bucketId`` is.
    public let path: String

    /// When it was queued, by the device's clock.
    public let createdAt: Date

    /// The file's size, in bytes.
    public let sizeBytes: Int

    /// The content type it was sent with; `nil` when the upload set none.
    public let contentType: String?

    /// Whether it overwrites an object already at the path.
    public let upsert: Bool

    /// What Storage answered: its status, its error code (such as `Duplicate`) and its message;
    /// `nil` when the client refused the upload itself, as when its file is no longer on the device.
    public let serverError: OfflineError.ServerError?

    /// Why it was refused, in words.
    public let message: String

    /// When it was refused, by the device's clock.
    public let rejectedAt: Date

    /// Whether ``SupabaseOfflineClient/retryRejectedMutation(id:)`` can queue it again: whether its
    /// file is still on the device, whole.
    public let isRetryable: Bool

    /// The refused bytes on this device, as a file-system path, so the app can show what was
    /// refused: ``OfflineBucketApi/cachedFile(path:)`` never answers them, as Storage may hold other
    /// bytes at ``path``. The file is the client's: read it, never move or change it. It is deleted
    /// once the entry is dismissed, its retry is sent or discarded, or local data is cleared. `nil`
    /// when the file is no longer on the device, whole.
    public let localPath: String?

    /// Built by the package, and by the testing product's factories.
    package init(
        id: Int64,
        bucketId: String,
        path: String,
        createdAt: Date,
        sizeBytes: Int,
        contentType: String?,
        upsert: Bool,
        serverError: OfflineError.ServerError?,
        message: String,
        rejectedAt: Date,
        isRetryable: Bool,
        localPath: String?
    ) {
        self.id = id
        self.bucketId = bucketId
        self.path = path
        self.createdAt = createdAt
        self.sizeBytes = sizeBytes
        self.contentType = contentType
        self.upsert = upsert
        self.serverError = serverError
        self.message = message
        self.rejectedAt = rejectedAt
        self.isRetryable = isRetryable
        self.localPath = localPath
    }
}

extension RejectedUpload {

    /// The core's refused `upload`; runs on the core's thread. Storage's text is kept as sent.
    init(core upload: BridgeRejectedUpload) {
        self.init(
            id: upload.id,
            bucketId: upload.bucketId,
            path: upload.path,
            createdAt: Date(epochMilliseconds: upload.createdAtEpochMillis),
            sizeBytes: Int(upload.sizeBytes),
            contentType: upload.contentType,
            upsert: upload.upsert,
            serverError: upload.serverError.map(OfflineError.ServerError.init(core:)),
            message: upload.message,
            rejectedAt: Date(epochMilliseconds: upload.rejectedAtEpochMillis),
            isRetryable: upload.isRetryable,
            localPath: upload.localPath
        )
    }
}
