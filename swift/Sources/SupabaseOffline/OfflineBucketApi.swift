// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
import SupabaseOfflineCore

/// One Storage bucket: supabase-swift's object operations, with downloads kept on the device.
///
/// A download goes to the network first and is kept in the client's file cache; when the network
/// fails, the cached copy answers. Every other operation reaches the server: uploads, updates,
/// moves, copies and removals are never queued, fail at once offline, and drop the cached copies
/// they make stale (a move carries its source's copy to the destination). The one exception is
/// ``upload(_:data:mutationStrategy:options:)``, which can wait in the mutation queue with the row
/// writes around it. Signed URLs, listings,
/// ``info(path:)`` and ``exists(path:)`` always ask the server.
///
/// An object name that cannot be mapped onto a file safely (a leading `/`, an empty, `.` or `..`
/// segment, a `\` or a control character) downloads uncached, and so does one holding `%`, `?` or
/// `#`. The URL builders, signing and ``uploadToSignedURL(_:token:data:options:)`` encode those
/// three, but every other request can mistake such a name for another object: keep them out of
/// names.
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
    ///
    /// - Throws: ``OfflineError``: `invalidRequest` for empty or oversized data; `connectivity`
    ///   offline, `networkDisabled` with the network turned off, or `server` with Storage's status
    ///   when Storage refuses it.
    @discardableResult
    public func upload(_ path: String, data: Data, options: UploadOptions = UploadOptions()) async throws -> FileUploadResponse {
        let optionsText = try options.coreText()
        return try await client.connection.call { core, complete in
            core.storage.upload(bucket: bucketId, path: path, data: data, options: optionsText) { answer, failure in
                complete(coreResult(answer, failure) { try FileUploadResponse(coreText: requiredValue($0)) })
            }
        }
    }

    /// Uploads `data` as a new object at `path` under `mutationStrategy`, as a write is made:
    /// ``MutationStrategy/queueIfOffline`` lets it wait in the mutation queue, in the order it was
    /// made among the current user's writes, so a row written after it that names the file is sent
    /// after the file.
    ///
    /// Under ``MutationStrategy/queueIfOffline`` the bytes are first kept on the device and the
    /// upload queued; it is sent at once when nothing of the user waits ahead of it, and the outcome
    /// is ``UploadOutcome/applied(response:)``. Otherwise it waits, ``UploadOutcome/queued(pendingUpload:)``:
    /// no connection or no answer, an answer that says the request did not finish (a `408`, `429`,
    /// `502`, `503`, `504`, `520`, `522` or `524`), a token Storage refused, earlier writes still
    /// queued, or the network turned off. A later sync sends it, and a refusal is kept on
    /// ``OfflineStorage/rejectedUploads()`` with its bytes instead of being thrown. Sent at once and
    /// refused, or failing with another `5xx`, it throws and does not stay queued. While it waits it
    /// is listed by ``OfflineStorage/pendingUploads()``, ``cachedFile(path:)`` and
    /// ``ensureCachedFile(path:isPublic:)`` return its bytes on the device, and
    /// ``SupabaseOfflineClient/discardPendingMutation(id:)`` drops it. Once Storage holds the file,
    /// its bytes become the object's cached copy. The queued upload keeps the options' `contentType`,
    /// `upsert` and `userMetadata`.
    ///
    /// ``MutationStrategy/onlineOnly`` is ``upload(_:data:options:)``, its response wrapped in
    /// ``UploadOutcome/applied(response:)``: sent now, throwing when it cannot be, nothing kept.
    /// Empty data, and data of 2 GiB or more, is refused with ``OfflineError/Code/invalidRequest``
    /// before anything is kept or sent.
    ///
    /// - Throws: ``OfflineError``: `invalidRequest` for empty or oversized data;
    ///   `mutationNotQueueable` for an upload the queue cannot keep that could not be sent; under
    ///   ``MutationStrategy/onlineOnly``, as ``upload(_:data:options:)`` throws.
    @discardableResult
    public func upload(
        _ path: String,
        data: Data,
        mutationStrategy: MutationStrategy,
        options: UploadOptions = UploadOptions()
    ) async throws -> UploadOutcome {
        let optionsText = try options.coreText()
        let strategy = mutationStrategy.coreName
        return try await client.connection.call { core, complete in
            core.storage.upload(
                bucket: bucketId,
                path: path,
                data: data,
                mutationStrategy: strategy,
                options: optionsText
            ) { outcome, failure in
                complete(coreResult(outcome, failure) { try UploadOutcome(core: requiredValue($0)) })
            }
        }
    }

    /// Replaces the object at `path` with `data`, dropping its cached copy.
    ///
    /// Online only, and refuses empty data and data of 2 GiB or more, as
    /// ``upload(_:data:options:)`` does.
    ///
    /// - Throws: As ``upload(_:data:options:)``.
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
    ///
    /// - Throws: As ``upload(_:data:options:)``.
    @discardableResult
    public func uploadToSignedURL(
        _ path: String,
        token: String,
        data: Data,
        options: UploadOptions = UploadOptions()
    ) async throws -> FileUploadResponse {
        let optionsText = try options.coreText()
        return try await client.connection.call { core, complete in
            core.storage.uploadToSignedUrl(
                bucket: bucketId,
                path: escapingURLDelimiters(in: path),
                token: token,
                data: data,
                options: optionsText
            ) {
                answer, failure in
                complete(coreResult(answer, failure) { try FileUploadResponse(coreText: requiredValue($0)) })
            }
        }
    }

    /// Deletes the objects at `paths` and their cached copies; online only.
    ///
    /// - Throws: ``OfflineError``: `connectivity` offline, `networkDisabled` with the network turned
    ///   off, or `server` with Storage's status when Storage refuses it.
    public func remove(paths: [String]) async throws {
        try await client.connection.call { core, complete in
            core.storage.delete(bucket: bucketId, paths: paths) { failure in complete(coreResult(failure)) }
        }
    }

    /// Moves the object at `source` to `destination`, in this bucket or `destinationBucket`;
    /// online only. The source's cached copy moves with it, replacing any copy at the destination.
    ///
    /// - Throws: ``OfflineError``: `connectivity` offline, `networkDisabled` with the network turned
    ///   off, or `server` with Storage's status when Storage refuses it.
    public func move(from source: String, to destination: String, destinationBucket: String? = nil) async throws {
        try await client.connection.call { core, complete in
            core.storage.moveObject(bucket: bucketId, from: source, to: destination, destinationBucket: destinationBucket) {
                failure in complete(coreResult(failure))
            }
        }
    }

    /// Copies the object at `source` to `destination`, in this bucket or `destinationBucket`;
    /// online only. The destination's cached copy is dropped; the source's stays.
    ///
    /// - Throws: ``OfflineError``: `connectivity` offline, `networkDisabled` with the network turned
    ///   off, or `server` with Storage's status when Storage refuses it.
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
    /// - Parameters:
    ///   - path: Where the upload goes in the bucket.
    ///   - upsert: Whether the upload may replace an object already at `path`.
    /// - Throws: ``OfflineError``: `connectivity` offline, `networkDisabled` with the network turned
    ///   off, or `server` with Storage's status when Storage refuses it.
    public func createSignedUploadURL(path: String, upsert: Bool = false) async throws -> UploadSignedURL {
        try await client.connection.call { core, complete in
            core.storage.createSignedUploadUrl(bucket: bucketId, path: escapingURLDelimiters(in: path), upsert: upsert) {
                answer, failure in
                complete(coreResult(answer, failure) { try UploadSignedURL(coreText: requiredValue($0), path: path) })
            }
        }
    }

    /// A URL that downloads the object at `path` for `expiresIn` seconds, transformed first when
    /// `transform` is given.
    ///
    /// - Throws: ``OfflineError``: `connectivity` offline, `networkDisabled` with the network turned
    ///   off, or `server` with Storage's status when Storage refuses it.
    public func createSignedURL(path: String, expiresIn: Int, transform: ImageTransformation? = nil) async throws -> URL {
        let transformText = try transform?.coreText()
        return try await client.connection.call { core, complete in
            core.storage.createSignedUrl(
                bucket: bucketId,
                path: escapingURLDelimiters(in: path),
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
    ///
    /// - Throws: ``OfflineError``: `decoding` as above; `connectivity` offline, `networkDisabled`
    ///   with the network turned off, or `server` with Storage's status when Storage refuses it.
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
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func publicURL(path: String) async throws -> URL {
        let answer = try await client.connection.openedClient().client.storage
            .publicUrl(bucket: bucketId, path: escapingURLDelimiters(in: path))
        return try storageURL(answer)
    }

    /// The URL that downloads the object at `path` with the user's session; nothing is sent.
    ///
    /// The URL comes from the opened client: the call waits for the client to finish opening, and
    /// after ``SupabaseOfflineClient/close()`` it throws ``OfflineError/Code/clientClosed``.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func authenticatedURL(path: String) async throws -> URL {
        let answer = try await client.connection.openedClient().client.storage
            .authenticatedUrl(bucket: bucketId, path: escapingURLDelimiters(in: path))
        return try storageURL(answer)
    }

    // MARK: - Reads

    /// The object at `path`: from the network first, kept in the file cache on success, and from
    /// the cached copy when the network fails.
    ///
    /// A "not found" from Storage drops the cached copy; another refusal is answered from the
    /// cached copy when there is one, and otherwise thrown as ``OfflineError/Code/server`` with
    /// Storage's status. A transformed download is cached apart from the original. Canceling the
    /// task throws `CancellationError` at once, and the download may still finish and be kept on
    /// the device, whole, when its bytes arrive before the cancellation reaches it.
    ///
    /// - Parameters:
    ///   - path: The object's path in the bucket.
    ///   - options: An image transformation Storage applies first.
    ///   - isPublic: Whether to download through the public URL rather than with the user's session.
    /// - Throws: ``OfflineError``: `server` for a refusal with no cached copy to answer it;
    ///   `connectivity` offline, or `networkDisabled` with the network turned off, with no cached
    ///   copy.
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
    ///
    /// - Throws: ``OfflineError``: `connectivity` offline, `networkDisabled` with the network turned
    ///   off, or `server` with Storage's status when Storage refuses it.
    public func list(path: String? = nil, options: StorageListFilter? = nil) async throws -> [FileObject] {
        let optionsText = try options?.coreText()
        return try await client.connection.call { core, complete in
            core.storage.list(bucket: bucketId, prefix: path ?? "", options: optionsText) { answer, failure in
                complete(coreResult(answer, failure) { try FileObject.list(coreText: requiredValue($0)) })
            }
        }
    }

    /// The details of the object at `path`.
    ///
    /// - Throws: ``OfflineError``: `connectivity` offline, `networkDisabled` with the network turned
    ///   off, or `server` with Storage's status when Storage refuses it.
    public func info(path: String) async throws -> FileObjectV2 {
        try await client.connection.call { core, complete in
            core.storage.info(bucket: bucketId, path: path) { answer, failure in
                complete(coreResult(answer, failure) { try FileObjectV2(coreText: requiredValue($0)) })
            }
        }
    }

    /// Whether an object exists at `path`.
    ///
    /// - Throws: ``OfflineError``: `connectivity` offline, `networkDisabled` with the network turned
    ///   off, or `server` with Storage's status when Storage refuses it.
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
    /// Only an untransformed ``download(path:options:isPublic:)``, ``ensureCachedFile(path:isPublic:)``
    /// or a queued upload Storage has stored caches the copy this returns; a transformed download's
    /// copy is found with ``cachedFile(path:transform:)``. While the current user has an upload of
    /// the object waiting in the mutation queue, it returns that upload's bytes on the device
    /// instead, the newest when there are several: its ``CachedFile/fetchedAt`` is when the upload
    /// was queued, and the file goes once the upload is sent, discarded or cleared. A refused
    /// upload's bytes are never answered here, since Storage may hold other bytes at the path:
    /// ``RejectedUpload/localPath`` names them.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func cachedFile(path: String) async throws -> CachedFile? {
        try await cachedFile(path: path, transformText: nil)
    }

    /// The cached copy of one image transformation of the object at `path`, or `nil` when there is
    /// none; never touches the network.
    ///
    /// It is the copy a ``download(path:options:isPublic:)`` with the same transformation keeps,
    /// and answers from offline: each transformation is a copy of its own, so a thumbnail can be
    /// handed to an image view as a file. A transformation that sets nothing is the untransformed
    /// object, as it is for a download.
    ///
    /// - Parameters:
    ///   - path: The object's path in the bucket.
    ///   - transform: The image transformation, as a download's `options:` sets it.
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func cachedFile(path: String, transform: ImageTransformation) async throws -> CachedFile? {
        try await cachedFile(path: path, transformText: transform.coreText())
    }

    /// The cached copy of `path` with the transformation the core reads from `transformText`.
    private func cachedFile(path: String, transformText: String?) async throws -> CachedFile? {
        try await client.connection.call { core, complete in
            core.storage.cachedFile(bucket: bucketId, path: path, imageTransform: transformText) { file, failure in
                complete(coreResult(file, failure) { $0.map(CachedFile.init(coreFile:)) })
            }
        }
    }

    /// Deletes every cached copy of the object at `path` from the device: the untransformed copy
    /// and each image transformation. Nothing is sent, and the object stays in the bucket; the next
    /// download fetches it again. Other objects' copies stay.
    ///
    /// A download of the object still running answers its caller but is not kept, as when the
    /// object is uploaded again: an ``ensureCachedFile(path:isPublic:)`` running meanwhile
    /// downloads it once more.
    ///
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func evictCachedFile(path: String) async throws {
        try await evictCachedFile(path: path, transformText: nil)
    }

    /// Deletes the cached copy of one image transformation of the object at `path` from the device:
    /// the copy ``cachedFile(path:transform:)`` with the same transformation returns.
    ///
    /// The untransformed copy and the other transformations stay; a transformation that sets
    /// nothing is the untransformed copy, which goes alone. Nothing is sent.
    ///
    /// - Parameters:
    ///   - path: The object's path in the bucket.
    ///   - transform: The image transformation, as a download's `options:` sets it.
    /// - Throws: ``OfflineError``: `clientClosed` once the client is closed.
    public func evictCachedFile(path: String, transform: ImageTransformation) async throws {
        try await evictCachedFile(path: path, transformText: transform.coreText())
    }

    /// Deletes the copies of `path` the core reads from `transformText`: every copy for `nil`.
    private func evictCachedFile(path: String, transformText: String?) async throws {
        try await client.connection.call { core, complete in
            core.storage.evictCachedFile(bucket: bucketId, path: path, imageTransform: transformText) { failure in
                complete(coreResult(failure))
            }
        }
    }

    /// The cached copy of the object at `path`, downloading it into the cache first when there is
    /// none.
    ///
    /// A cached copy is returned however old it is, and an upload of the object still queued for the
    /// current user is returned as ``cachedFile(path:)`` returns it, with no request; a refused one
    /// is not, and the object is downloaded as when nothing is cached. Throws
    /// ``OfflineError/Code/invalidRequest``
    /// for a name the cache cannot hold (see ``OfflineBucketApi``), sending nothing; one holding
    /// `%`, `?` or `#` is refused before the client is asked. Canceling the task throws
    /// `CancellationError` at once, and the download may still finish and be kept, as with
    /// ``download(path:options:isPublic:)``.
    ///
    /// - Parameters:
    ///   - path: The object's path in the bucket.
    ///   - isPublic: Whether a download goes through the public URL rather than with the user's
    ///     session.
    /// - Throws: ``OfflineError``: `invalidRequest` as above; with nothing cached, `server` when
    ///   Storage refuses the download, `connectivity` offline, or `networkDisabled` with the network
    ///   turned off.
    public func ensureCachedFile(path: String, isPublic: Bool = false) async throws -> CachedFile {
        // A copy of the core's rule, kept only for the wording: when the test
        // `theCoreStillRefusesANameHoldingEachDelimiter` fails, the core caches these names, and
        // this check and `escapingURLDelimiters` must go.
        guard !holdsURLDelimiters(path) else {
            throw OfflineError(
                code: .invalidRequest,
                message: "This object's name holds '%', '?' or '#', so it cannot be kept on the device: "
                    + "keep those characters out of the names of objects the app keeps on the device"
            )
        }
        return try await client.connection.call { core, complete in
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

/// `name` as a URL path for a call that writes it into a URL: percent-encoded whole when it holds
/// `%`, `?` or `#`, which would start an escape, a query or a fragment, and otherwise as it is.
///
/// The core writes the name into the URL unencoded today; a core that encodes it would encode it
/// twice. When the test `theCoreStillRefusesANameHoldingEachDelimiter` fails, the core has started
/// to: remove this helper, `holdsURLDelimiters` and the refusal in `ensureCachedFile`.
func escapingURLDelimiters(in name: String) -> String {
    guard holdsURLDelimiters(name) else { return name }
    return name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
}

/// Whether `name` holds `%`, `?` or `#` anywhere, as a scalar of its own or inside a character
/// (the `#` of a keycap emoji), as the core sees them: it never caches such an object.
func holdsURLDelimiters(_ name: String) -> Bool {
    name.unicodeScalars.contains { "%?#".unicodeScalars.contains($0) }
}

/// The URL in a synchronous answer of the core, or the failure it carries.
private func storageURL(_ answer: BridgeStringResult) throws -> URL {
    if let failure = answer.error { throw OfflineError.thrown(for: failure) }
    return try CoreURL.parse(requiredValue(answer.value))
}
