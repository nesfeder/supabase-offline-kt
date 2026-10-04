// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation

/// A Storage object's copy in the client's file cache: read it, open it or hand it to an image
/// loader, but never move or write it.
///
/// The cache may delete the copy when the object is written, moved or deleted through the client,
/// when the server reports it gone, when the cache passes its size and the copy has not been used
/// for 30 seconds, and when local data is cleared.
public struct CachedFile: Sendable, Hashable {

    /// The copy's absolute path on this device; from the testing product's in-memory client, a
    /// path in that client's memory, which is not on disk.
    public let path: String

    /// When the copy was downloaded. A cached copy is served however old it is: compare this with
    /// your own rule.
    public let fetchedAt: Date

    /// The copy's size in bytes.
    public let sizeBytes: Int

    /// The copy as a file URL, for APIs that take one; from the testing product's in-memory client,
    /// a URL of no file on disk.
    public var url: URL {
        URL(filePath: path, directoryHint: .notDirectory)
    }

    init(path: String, fetchedAt: Date, sizeBytes: Int) {
        self.path = path
        self.fetchedAt = fetchedAt
        self.sizeBytes = sizeBytes
    }
}
