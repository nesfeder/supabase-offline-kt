// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore

extension SupabaseOfflineClient {

    /// The project's Storage buckets, with downloads kept on the device.
    ///
    /// Managing buckets (creating, listing, emptying them) stays with supabase-swift's client.
    public var storage: OfflineStorage {
        OfflineStorage(client: self)
    }
}

/// The project's Storage buckets, with downloads kept on the device, and the uploads the mutation
/// queue holds.
public struct OfflineStorage: Sendable {

    /// The client the requests go through.
    let client: SupabaseOfflineClient

    init(client: SupabaseOfflineClient) {
        self.client = client
    }

    /// The bucket `bucketId`: every object operation, plus the file cache.
    public func from(_ bucketId: String) -> OfflineBucketApi {
        OfflineBucketApi(client: client, bucketId: bucketId)
    }

    /// Deletes every downloaded file the client keeps on the device, in every bucket, and every pin
    /// (``OfflineBucketApi/pinCachedFile(path:)``).
    ///
    /// The queued writes, the cached query results and the rejected and conflicted lists stay, and
    /// nothing is sent: call it to free the device's storage without dropping writes waiting to
    /// sync. The next download of each object fetches it again. At sign-out, call
    /// ``SupabaseOfflineClient/clearLocalData()`` instead, which deletes these files too.
    ///
    /// A download still running answers its caller, or fails, but is not kept; an
    /// ``OfflineBucketApi/ensureCachedFile(path:isPublic:)`` running meanwhile fails.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func clearFileCache() async throws {
        try await client.connection.call { core, complete in
            core.storage.clearFileCache { failure in complete(coreResult(failure)) }
        }
    }

    /// The current user's uploads waiting in the mutation queue, in every bucket, in the order they
    /// will be sent, as the queue changes: the uploads a bucket's
    /// ``OfflineBucketApi/upload(_:data:mutationStrategy:options:)`` queued.
    ///
    /// Each is a write of the queue: ``SupabaseOfflineClient/discardPendingMutation(id:)`` drops one
    /// with its bytes, and ``SyncStatus/pendingMutationCount`` counts them;
    /// ``SupabaseOfflineClient/pendingMutations(table:)`` lists the row writes around them. Iterate
    /// it for the list now and each change; ``PendingUploadSequence/first()`` reads it once.
    public func pendingUploads() -> PendingUploadSequence {
        PendingUploadSequence(
            live: client.liveSequence { core, yield, finish in
                core.storage.observePendingUploads(
                    onUploads: { uploads in yield(uploads.map(PendingUpload.init(core:))) },
                    onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
                )
            },
            current: { [client] in
                try await client.connection.call { core, complete in
                    core.storage.pendingUploads { uploads, failure in
                        complete(coreResult(uploads, failure) { try requiredValue($0).map(PendingUpload.init(core:)) })
                    }
                }
            }
        )
    }

    /// The current user's queued uploads Storage refused for good on replay, oldest first, as the
    /// list changes. Each keeps its bytes on the device until it is dismissed
    /// (``SupabaseOfflineClient/dismissRejectedMutation(id:)``) or retried
    /// (``SupabaseOfflineClient/retryRejectedMutation(id:)``, which queues it again at the end of the
    /// user's queue).
    ///
    /// They are entries of the one rejected list: ``SyncStatus/rejectedMutationCount`` counts them,
    /// and ``SupabaseOfflineClient/rejectedMutations()`` lists the refused row writes beside them.
    /// Iterate it for the list now and each change; ``RejectedUploadSequence/first()`` and
    /// ``rejectedUploadList()`` read it once.
    public func rejectedUploads() -> RejectedUploadSequence {
        RejectedUploadSequence(
            live: client.liveSequence { core, yield, finish in
                core.storage.observeRejectedUploads(
                    onUploads: { uploads in yield(uploads.map(RejectedUpload.init(core:))) },
                    onCompletion: { failure in finish(failure.map(OfflineError.thrown(for:))) }
                )
            },
            current: { [self] in try await rejectedUploadList() }
        )
    }

    /// The current user's refused uploads on the rejected list, oldest first, read once: the list
    /// ``rejectedUploads()`` gives first.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func rejectedUploadList() async throws -> [RejectedUpload] {
        try await client.connection.call { core, complete in
            core.storage.rejectedUploads { uploads, failure in
                complete(coreResult(uploads, failure) { try requiredValue($0).map(RejectedUpload.init(core:)) })
            }
        }
    }
}
