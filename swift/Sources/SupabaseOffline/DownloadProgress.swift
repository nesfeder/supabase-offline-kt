// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// How far a download into the file cache has come, as
/// ``OfflineBucketApi/ensureCachedFile(path:isPublic:onProgress:)`` reports it.
public struct DownloadProgress: Sendable, Hashable {

    /// The bytes of the object received so far.
    public let bytesReceived: Int

    /// The object's size in bytes, as the server's answer gave it; `nil` when it gave none.
    public let totalBytes: Int?

    /// Built by the package, and by the testing product's factory.
    package init(bytesReceived: Int, totalBytes: Int?) {
        self.bytesReceived = bytesReceived
        self.totalBytes = totalBytes
    }
}
