// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// One Storage bucket: supabase-swift's object operations, with downloads kept on the device.
///
/// A download goes to the network first and is kept in the client's file cache; when the network
/// fails, the cached copy answers. Every other operation reaches the server: uploads, updates,
/// moves, copies and removals are never queued, fail at once offline, and drop the cached copies
/// they make stale (a move carries its source's copy to the destination). Signed URLs, listings,
/// ``info(path:)`` and ``exists(path:)`` always ask the server.
///
/// An object name that cannot be mapped onto a file safely (a leading `/`, an empty, `.` or `..`
/// segment, a `\` or a control character) downloads uncached, and so does one holding `%`, `?` or
/// `#`, which the request can mistake for another object: keep those characters out of names.
public struct OfflineBucketApi: Sendable {

    /// The client the requests go through.
    let client: SupabaseOfflineClient

    /// The bucket's id.
    public let bucketId: String

    init(client: SupabaseOfflineClient, bucketId: String) {
        self.client = client
        self.bucketId = bucketId
    }

    // MARK: - Writes

    /// Uploads `data` as a new object at `path`.
    ///
    /// Online only: offline it fails with ``OfflineError/Code/connectivity`` and is never queued.
    /// The bytes are not cached, and an upload over a cached object drops its copy; the next
    /// download fetches them. Empty data, and data of 2 GiB or more, is refused with
    /// ``OfflineError/Code/invalidRequest`` before anything is sent.
    @discardableResult
    public func upload(_ path: String, data: Data, options: UploadOptions = UploadOptions()) async throws -> FileUploadResponse {
        let optionsText = try options.coreText()
        return try await client.connection.call { core, complete in
            core.storage.upload(bucket: bucketId, path: path, data: data, options: optionsText) { answer, failure in
                complete(coreResult(answer, failure) { try FileUploadResponse(coreText: requiredValue($0)) })
            }
        }
    }

    /// Replaces the object at `path` with `data`, dropping its cached copy.
    ///
    /// Online only, and refuses empty data and data of 2 GiB or more, as
    /// ``upload(_:data:options:)`` does.
    @discardableResult
    public func update(_ path: String, data: Data, options: UploadOptions = UploadOptions()) async throws -> FileUploadResponse {
        let optionsText = try options.coreText()
        return try await client.connection.call { core, complete in
            core.storage.update(bucket: bucketId, path: path, data: data, options: optionsText) { answer, failure in
                complete(coreResult(answer, failure) { try FileUploadResponse(coreText: requiredValue($0)) })
            }
        }
    }

    /// Uploads `data` to `path` through a signed upload URL's `token`, from
    /// ``createSignedUploadURL(path:upsert:)``.
    ///
    /// Online only, and refuses empty data and data of 2 GiB or more, as
    /// ``upload(_:data:options:)`` does.
    @discardableResult
    public func uploadToSignedURL(
        _ path: String,
        token: String,
        data: Data,
        options: UploadOptions = UploadOptions()
    ) async throws -> FileUploadResponse {
        let optionsText = try options.coreText()
        return try await client.connection.call { core, complete in
            core.storage.uploadToSignedUrl(bucket: bucketId, path: path, token: token, data: data, options: optionsText) {
                answer, failure in
                complete(coreResult(answer, failure) { try FileUploadResponse(coreText: requiredValue($0)) })
            }
        }
    }

    /// Deletes the objects at `paths` and their cached copies; online only.
    public func remove(paths: [String]) async throws {
        try await client.connection.call { core, complete in
            core.storage.delete(bucket: bucketId, paths: paths) { failure in complete(coreResult(failure)) }
        }
    }

    /// Moves the object at `source` to `destination`, in this bucket or `destinationBucket`;
    /// online only. The source's cached copy moves with it, replacing any copy at the destination.
    public func move(from source: String, to destination: String, destinationBucket: String? = nil) async throws {
        try await client.connection.call { core, complete in
            core.storage.moveObject(bucket: bucketId, from: source, to: destination, destinationBucket: destinationBucket) {
                failure in complete(coreResult(failure))
            }
        }
    }

    /// Copies the object at `source` to `destination`, in this bucket or `destinationBucket`;
    /// online only. The destination's cached copy is dropped; the source's stays.
    public func copy(from source: String, to destination: String, destinationBucket: String? = nil) async throws {
        try await client.connection.call { core, complete in
            core.storage.doCopyObject(bucket: bucketId, from: source, to: destination, destinationBucket: destinationBucket) {
                failure in complete(coreResult(failure))
            }
        }
    }

    // MARK: - Signed URLs

    /// A URL that uploads to `path` without a session, for ``uploadToSignedURL(_:token:data:options:)``.
    ///
    /// - Parameter upsert: Whether the upload may replace an object already at `path`.
    public func createSignedUploadURL(path: String, upsert: Bool = false) async throws -> UploadSignedURL {
        try await client.connection.call { core, complete in
            core.storage.createSignedUploadUrl(bucket: bucketId, path: path, upsert: upsert) { answer, failure in
                complete(coreResult(answer, failure) { try UploadSignedURL(coreText: requiredValue($0)) })
            }
        }
    }

    /// A URL that downloads the object at `path` for `expiresIn` seconds, transformed first when
    /// `transform` is given.
    public func createSignedURL(path: String, expiresIn: Int, transform: ImageTransformation? = nil) async throws -> URL {
        let transformText = try transform?.coreText()
        return try await client.connection.call { core, complete in
            core.storage.createSignedUrl(
                bucket: bucketId,
                path: path,
                expiresInSeconds: Int64(expiresIn),
                imageTransform: transformText
            ) { answer, failure in
                complete(coreResult(answer, failure) { try CoreURL.parse(requiredValue($0)) })
            }
        }
    }

    /// URLs that download the objects at `paths` for `expiresIn` seconds, one per path.
    ///
    /// A path Storage cannot sign (an object that does not exist, say) fails the whole call with
    /// ``OfflineError/Code/decoding``, whose message names the path's position in `paths`
    /// (`$[1]` for the second).
    public func createSignedURLs(paths: [String], expiresIn: Int) async throws -> [SignedURL] {
        try await client.connection.call { core, complete in
            core.storage.createSignedUrls(bucket: bucketId, paths: paths, expiresInSeconds: Int64(expiresIn)) {
                answer, failure in
                complete(coreResult(answer, failure) { try SignedURL.list(coreText: requiredValue($0)) })
            }
        }
    }

    /// The URL anyone can download the object at `path` from, when the bucket is public; nothing
    /// is sent.
    ///
    /// The URL comes from the opened client: the call waits for the client to finish opening, and
    /// after ``SupabaseOfflineClient/close()`` it throws ``OfflineError/Code/clientClosed``.
    public func publicURL(path: String) async throws -> URL {
        let answer = try await client.connection.openedClient().client.storage.publicUrl(bucket: bucketId, path: path)
        return try storageURL(answer)
    }

    /// The URL that downloads the object at `path` with the user's session; nothing is sent.
    ///
    /// The URL comes from the opened client: the call waits for the client to finish opening, and
    /// after ``SupabaseOfflineClient/close()`` it throws ``OfflineError/Code/clientClosed``.
    public func authenticatedURL(path: String) async throws -> URL {
        let answer = try await client.connection.openedClient().client.storage.authenticatedUrl(bucket: bucketId, path: path)
        return try storageURL(answer)
    }

    // MARK: - Reads

    /// The object at `path`: from the network first, kept in the file cache on success, and from
    /// the cached copy when the network fails.
    ///
    /// A "not found" from Storage drops the cached copy; another refusal is answered from the
    /// cached copy when there is one, and otherwise thrown as ``OfflineError/Code/server`` with
    /// Storage's status. A transformed download is cached apart from the original.
    ///
    /// - Parameters:
    ///   - options: An image transformation Storage applies first.
    ///   - isPublic: Whether to download through the public URL rather than with the user's session.
    public func download(path: String, options: ImageTransformation? = nil, isPublic: Bool = false) async throws -> Data {
        let transformText = try options?.coreText()
        return try await client.connection.call { core, complete in
            core.storage.download(bucket: bucketId, path: path, isPublic: isPublic, imageTransform: transformText) {
                bytes, failure in
                complete(coreResult(bytes, failure) { try requiredValue($0) })
            }
        }
    }

    /// The objects and folders under `path` (the bucket's top level by default).
    public func list(path: String? = nil, options: StorageListFilter? = nil) async throws -> [FileObject] {
        let optionsText = try options?.coreText()
        return try await client.connection.call { core, complete in
            core.storage.list(bucket: bucketId, prefix: path ?? "", options: optionsText) { answer, failure in
                complete(coreResult(answer, failure) { try FileObject.list(coreText: requiredValue($0)) })
            }
        }
    }

    /// The details of the object at `path`.
    public func info(path: String) async throws -> FileObjectV2 {
        try await client.connection.call { core, complete in
            core.storage.info(bucket: bucketId, path: path) { answer, failure in
                complete(coreResult(answer, failure) { try FileObjectV2(coreText: requiredValue($0)) })
            }
        }
    }

    /// Whether an object exists at `path`.
    public func exists(path: String) async throws -> Bool {
        try await client.connection.call { core, complete in
            core.storage.exists(bucket: bucketId, path: path) { answer, failure in
                complete(coreResult(answer, failure) { try requiredValue($0).boolValue })
            }
        }
    }

    // MARK: - The file cache

    /// The cached copy of the object at `path`, or `nil` when there is none; never touches the
    /// network.
    ///
    /// Only an untransformed ``download(path:options:isPublic:)`` or ``ensureCachedFile(path:isPublic:)``
    /// caches the copy this returns.
    public func cachedFile(path: String) async throws -> CachedFile? {
        try await client.connection.call { core, complete in
            core.storage.cachedFile(bucket: bucketId, path: path) { file, failure in
                complete(coreResult(file, failure) { $0.map(CachedFile.init(coreFile:)) })
            }
        }
    }

    /// The cached copy of the object at `path`, downloading it into the cache first when there is
    /// none.
    ///
    /// A cached copy is returned however old it is. Throws ``OfflineError/Code/invalidRequest``
    /// for a name the cache cannot hold (see ``OfflineBucketApi``), sending nothing.
    ///
    /// - Parameter isPublic: Whether a download goes through the public URL rather than with the
    ///   user's session.
    public func ensureCachedFile(path: String, isPublic: Bool = false) async throws -> CachedFile {
        try await client.connection.call { core, complete in
            core.storage.ensureCachedFile(bucket: bucketId, path: path, isPublic: isPublic) { file, failure in
                complete(coreResult(file, failure) { try CachedFile(coreFile: requiredValue($0)) })
            }
        }
    }
}

extension CachedFile {

    /// The core's record of a cached copy.
    init(coreFile: BridgeCachedFile) {
        self.init(
            path: coreFile.path,
            fetchedAt: Date(timeIntervalSince1970: TimeInterval(coreFile.fetchedAtEpochMillis) / 1_000),
            sizeBytes: Int(coreFile.sizeBytes)
        )
    }
}

/// The URL in a synchronous answer of the core, or the failure it carries.
private func storageURL(_ answer: BridgeStringResult) throws -> URL {
    if let failure = answer.error { throw OfflineError.thrown(for: failure) }
    return try CoreURL.parse(requiredValue(answer.value))
}
