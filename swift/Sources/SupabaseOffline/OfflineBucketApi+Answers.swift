// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation

extension OfflineBucketApi {

    /// Where an upload or an update stored its object.
    public struct FileUploadResponse: Sendable, Hashable {

        /// The object's id.
        public let id: String?

        /// The object's path in the bucket, as the upload named it.
        public let path: String

        /// The object's key: the bucket and the path.
        public let key: String?

        init(id: String?, path: String, key: String?) {
            self.id = id
            self.path = path
            self.key = key
        }
    }

    /// A URL that uploads one object without a session, for ``OfflineBucketApi/uploadToSignedURL(_:token:data:options:)``.
    public struct UploadSignedURL: Sendable, Hashable {

        /// The URL to upload to.
        public let url: URL

        /// The object's path in the bucket.
        public let path: String

        /// The token the upload presents.
        public let token: String

        init(url: URL, path: String, token: String) {
            self.url = url
            self.path = path
            self.token = token
        }
    }

    /// One object's signed download URL, from ``OfflineBucketApi/createSignedURLs(paths:expiresIn:)``.
    public struct SignedURL: Sendable, Hashable {

        /// A note Storage sent with this path's URL, when it sent one. A path Storage cannot sign
        /// at all fails the whole call instead (see ``OfflineBucketApi/createSignedURLs(paths:expiresIn:)``).
        public let error: String?

        /// The URL, which downloads the object until it expires.
        public let signedURL: URL

        /// The object's path in the bucket.
        public let path: String

        init(error: String?, signedURL: URL, path: String) {
            self.error = error
            self.signedURL = signedURL
            self.path = path
        }
    }

    /// One object or folder of a listing.
    public struct FileObject: Sendable, Hashable {

        /// The name, relative to the listed path.
        public let name: String

        /// The object's id; `nil` for a folder.
        public let id: String?

        /// When the object was last changed.
        public let updatedAt: Date?

        /// When the object was created.
        public let createdAt: Date?

        /// When the object was last read.
        public let lastAccessedAt: Date?

        /// What Storage records about the object (its size, content type and so on).
        public let metadata: [String: OfflineJSON]?

        init(
            name: String,
            id: String?,
            updatedAt: Date?,
            createdAt: Date?,
            lastAccessedAt: Date?,
            metadata: [String: OfflineJSON]?
        ) {
            self.name = name
            self.id = id
            self.updatedAt = updatedAt
            self.createdAt = createdAt
            self.lastAccessedAt = lastAccessedAt
            self.metadata = metadata
        }
    }

    /// One object's details, from ``OfflineBucketApi/info(path:)``.
    public struct FileObjectV2: Sendable, Hashable {

        /// The object's name: its path in the bucket.
        public let name: String

        /// The object's id.
        public let id: String?

        /// The object's version.
        public let version: String

        /// The bucket the object is in.
        public let bucketId: String?

        /// When the object was last changed.
        public let updatedAt: Date?

        /// When the object was created.
        public let createdAt: Date

        /// When the object was last read.
        public let lastAccessedAt: Date?

        /// The metadata the upload stored with the object (``UploadOptions/userMetadata``).
        public let metadata: [String: OfflineJSON]?

        /// The size in bytes.
        public let size: Int

        /// The content type, such as `image/png`.
        public let contentType: String

        /// The object's entity tag.
        public let etag: String?

        /// When the object's content last changed.
        public let lastModified: Date?

        /// The `Cache-Control` value the object is served with.
        public let cacheControl: String?
    }
}

// MARK: - The core's answers

extension OfflineBucketApi.FileUploadResponse {

    /// The core's answer `text`, `{"id", "path", "key"}`.
    init(coreText text: String) throws {
        let answer = try RowCoding.decode(Answer.self, from: text)
        self.init(id: answer.id, path: answer.path, key: answer.key)
    }

    /// The answer as the core writes it.
    private struct Answer: Decodable {
        let id: String?
        let path: String
        let key: String?
    }
}

extension OfflineBucketApi.UploadSignedURL {

    /// The core's answer `text`, `{"url", "path", "token"}`, for the object at `path` as the caller
    /// named it (the core's `path` is the name as it went into the URL).
    init(coreText text: String, path: String) throws {
        let answer = try RowCoding.decode(Answer.self, from: text)
        try self.init(url: CoreURL.parse(answer.url), path: path, token: answer.token)
    }

    /// The answer as the core writes it.
    private struct Answer: Decodable {
        let url: String
        let token: String
    }
}

extension OfflineBucketApi.SignedURL {

    /// The core's answer `text`, a JSON array of `{"error", "signedURL", "path"}`.
    static func list(coreText text: String) throws -> [Self] {
        try RowCoding.decode([Answer].self, from: text).map {
            try Self(error: $0.error, signedURL: CoreURL.parse($0.signedURL), path: $0.path)
        }
    }

    /// One URL as the core writes it: the object's name in it unencoded.
    private struct Answer: Decodable {
        let error: String?
        let signedURL: String
        let path: String
    }
}

extension OfflineBucketApi.FileObject {

    /// The core's answer `text`, a JSON array of Storage's file objects.
    static func list(coreText text: String) throws -> [Self] {
        try RowCoding.decode([Answer].self, from: text).map {
            Self(
                name: $0.name,
                id: $0.id,
                updatedAt: $0.updatedAt,
                createdAt: $0.createdAt,
                lastAccessedAt: $0.lastAccessedAt,
                metadata: $0.metadata
            )
        }
    }

    /// One object as the core writes it.
    private struct Answer: Decodable {
        let name: String
        let id: String?
        let updatedAt: Date?
        let createdAt: Date?
        let lastAccessedAt: Date?
        let metadata: [String: OfflineJSON]?

        enum CodingKeys: String, CodingKey {
            case name, id, metadata
            case updatedAt = "updated_at"
            case createdAt = "created_at"
            case lastAccessedAt = "last_accessed_at"
        }
    }
}

extension OfflineBucketApi.FileObjectV2 {

    /// The core's answer `text`, Storage's object details; a field the core leaves out is `nil`.
    init(coreText text: String) throws {
        let answer = try RowCoding.decode(Answer.self, from: text)
        name = answer.name
        id = answer.id
        version = answer.version
        bucketId = answer.bucketId
        updatedAt = answer.updatedAt
        createdAt = answer.createdAt
        lastAccessedAt = answer.lastAccessedAt
        metadata = answer.metadata
        size = answer.size
        contentType = answer.contentType
        etag = answer.etag
        lastModified = answer.lastModified
        cacheControl = answer.cacheControl
    }

    /// The details as the core writes them.
    private struct Answer: Decodable {
        let name: String
        let id: String?
        let version: String
        let bucketId: String?
        let updatedAt: Date?
        let createdAt: Date
        let lastAccessedAt: Date?
        let metadata: [String: OfflineJSON]?
        let size: Int
        let contentType: String
        let etag: String?
        let lastModified: Date?
        let cacheControl: String?

        enum CodingKeys: String, CodingKey {
            case name, id, version, metadata, size, etag
            case bucketId = "bucket_id"
            case updatedAt = "updated_at"
            case createdAt = "created_at"
            case lastAccessedAt = "last_accessed_at"
            case contentType = "content_type"
            case lastModified = "last_modified"
            case cacheControl = "cache_control"
        }
    }
}
