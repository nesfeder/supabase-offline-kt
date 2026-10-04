// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

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
}
