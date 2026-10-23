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

/// The project's Storage buckets, with downloads kept on the device.
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

    /// Deletes every downloaded file the client keeps on the device, in every bucket.
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
}
