// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// Storage through the in-memory client: the file cache, failures, and what each operation sends.
struct StorageTests {

    /// The project URL every request here carries.
    private static let projectURL = URL(string: "https://project.supabase.co")!

    /// An in-memory client over `storage`, with a clock a test can read.
    private static func makeClient(_ storage: FakeStorageServer, clock: ManualClock? = nil) -> SupabaseOfflineClient {
        .inMemory(supabaseURL: projectURL, server: storage.server, clock: clock)
    }

    // MARK: - The file cache

    @MainActor
    @Test func aDownloadIsCachedAndThenServedFromTheCacheOffline() async throws {
        // Given a download made online, from the main actor
        let storage = FakeStorageServer()
        let clock = ManualClock(now: storageDate("2026-10-04T12:00:00.250Z"))
        let client = Self.makeClient(storage, clock: clock)
        let bucket = client.storage.from("avatars")
        let online = try await bucket.download(path: "a.png")

        // When the network drops
        storage.goOffline()
        let offline = try await bucket.download(path: "a.png")
        let cached = try #require(try await bucket.cachedFile(path: "a.png"))
        let ensured = try await bucket.ensureCachedFile(path: "a.png")
        let neverDownloaded = try await bucket.cachedFile(path: "b.png")

        // Then
        #expect(online == FakeStorageServer.photo)
        #expect(offline == FakeStorageServer.photo)
        #expect(cached.sizeBytes == FakeStorageServer.photo.count)
        #expect(abs(cached.fetchedAt.timeIntervalSince(clock.now)) < 0.000_5)
        #expect(cached.url.isFileURL)
        #expect(cached.url.path(percentEncoded: false) == cached.path)
        #expect(ensured == cached)
        #expect(neverDownloaded == nil)
        #expect(storage.requests(below: "object/authenticated/avatars/a.png").count == 2)
        try await client.close()
    }

    @Test func ensureCachedFileDownloadsOnAMissThenAnswersOffline() async throws {
        // Given nothing cached
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")

        // When
        let downloaded = try await bucket.ensureCachedFile(path: "b.png", isPublic: true)
        storage.goOffline()
        let offline = try await bucket.ensureCachedFile(path: "b.png")
        let missing = await #expect(throws: OfflineError.self) { try await bucket.ensureCachedFile(path: "c.png") }

        // Then
        #expect(downloaded.sizeBytes == FakeStorageServer.publicPhoto.count)
        #expect(offline == downloaded)
        #expect(storage.requests(below: "object/public/avatars/b.png").count == 1)
        #expect(missing?.code == .connectivity)
        #expect(missing?.isNetworkOrConnectivityFailure == true)
        try await client.close()
    }

    @Test(arguments: ["photo?v=1.png", "100%.png", "a#b.png", "../a.png"])
    func ensureCachedFileRefusesANameTheCacheCannotHoldAndSendsNothing(name: String) async throws {
        // Given
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.storage.from("avatars").ensureCachedFile(path: name)
        }

        // Then
        #expect(error?.code == .invalidRequest)
        #expect(storage.requests.values.isEmpty)
        try await client.close()
    }

    @Test func aNameWithAQuestionMarkDownloadsUncached() async throws {
        // Given
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")

        // When
        let bytes = try await bucket.download(path: "photo?v=1.png")
        let cached = try await bucket.cachedFile(path: "photo?v=1.png")
        storage.goOffline()
        let offline = await #expect(throws: OfflineError.self) { try await bucket.download(path: "photo?v=1.png") }

        // Then the request named another object, and nothing was kept
        #expect(bytes == FakeStorageServer.photo)
        #expect(storage.requests.values.first?.storagePath == "object/authenticated/avatars/photo")
        #expect(cached == nil)
        #expect(offline?.code == .connectivity)
        try await client.close()
    }

    @Test(arguments: [400, 403, 404, 500])
    func aDownloadStorageRefusesIsThrownWithItsStatusAndNeverCached(status: Int) async throws {
        // Given
        let storage = FakeStorageServer()
        storage.answer { _ in FakeStorageServer.refusal(status: status) }
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")

        // When
        let error = await #expect(throws: OfflineError.self) { try await bucket.download(path: "a.png") }
        let cached = try await bucket.cachedFile(path: "a.png")

        // Then
        #expect(error?.code == .server)
        #expect(error?.httpStatus == status)
        #expect(error?.serverError?.message.isEmpty == false)
        #expect(error?.isNetworkOrConnectivityFailure == false)
        #expect(cached == nil)
        try await client.close()
    }

    @Test(arguments: [400, 403, 404, 500])
    func ensureCachedFileThrowsAStorageRefusalWithItsStatusAndCachesNothing(status: Int) async throws {
        // Given
        let storage = FakeStorageServer()
        storage.answer { _ in FakeStorageServer.refusal(status: status) }
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")

        // When, then
        await withKnownIssue("The core keeps a refused channel download's answer as the file until its checked-download fix is merged") {
            let error = await #expect(throws: OfflineError.self) { try await bucket.ensureCachedFile(path: "a.png") }
            let cached = try await bucket.cachedFile(path: "a.png")
            #expect(error?.code == .server)
            #expect(error?.httpStatus == status)
            #expect(cached == nil)
        }
        try await client.close()
    }

    @Test func aNotFoundDropsTheCachedCopyAndAnotherRefusalIsAnsweredFromIt() async throws {
        // Given a cached copy
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")
        _ = try await bucket.download(path: "a.png")

        // When Storage refuses, first with a server failure, then with "not found"
        storage.answer { _ in FakeStorageServer.refusal(status: 500) }
        let afterFailure = try await bucket.download(path: "a.png")
        storage.answer { _ in FakeStorageServer.refusal(status: 404) }
        let notFound = await #expect(throws: OfflineError.self) { try await bucket.download(path: "a.png") }
        let cached = try await bucket.cachedFile(path: "a.png")

        // Then
        #expect(afterFailure == FakeStorageServer.photo)
        #expect(notFound?.httpStatus == 404)
        #expect(cached == nil)
        try await client.close()
    }

    @Test func aTransformedDownloadIsCachedApartFromTheOriginal() async throws {
        // Given
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")

        // When
        let bytes = try await bucket.download(path: "a.png", options: .init(width: 100, resize: .cover))
        let cached = try await bucket.cachedFile(path: "a.png")

        // Then
        let request = try #require(storage.requests.values.first)
        #expect(bytes == FakeStorageServer.photo)
        #expect(request.storagePath == "render/image/authenticated/avatars/a.png")
        #expect(request.url.query()?.contains("width=100") == true)
        #expect(request.url.query()?.contains("resize=cover") == true)
        #expect(cached == nil)
        try await client.close()
    }

    // MARK: - Writes

    @Test func everyWriteOfflineFailsAtOnceAndIsNeverQueued() async throws {
        // Given
        let storage = FakeStorageServer()
        storage.goOffline()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")
        let writes: [@Sendable () async throws -> Void] = [
            { _ = try await bucket.upload("a.png", data: FakeStorageServer.photo) },
            { _ = try await bucket.update("a.png", data: FakeStorageServer.photo) },
            { try await bucket.remove(paths: ["a.png"]) },
            { try await bucket.move(from: "a.png", to: "b.png") },
            { try await bucket.copy(from: "a.png", to: "b.png") },
        ]

        // When
        var errors: [OfflineError?] = []
        for write in writes {
            errors.append(await #expect(throws: OfflineError.self) { try await write() })
        }

        // Then each was tried once, failed as a connectivity failure and was not queued
        for error in errors {
            #expect(error?.code == .connectivity)
            #expect(error?.isNetworkOrConnectivityFailure == true)
        }
        #expect(storage.requests.values.count == writes.count)
        #expect(try await client.rawPendingMutationCount() == 0)
        try await client.close()
    }

    @Test func anUploadSendsTheBytesAndItsOptions() async throws {
        // Given
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let options = OfflineBucketApi.UploadOptions(contentType: "image/png", upsert: true, userMetadata: ["owner": "me"])

        // When
        let uploaded = try await client.storage.from("avatars").upload("folder/a.png", data: FakeStorageServer.photo, options: options)
        let updated = try await client.storage.from("avatars").update("folder/a.png", data: Data("new".utf8))

        // Then
        let upload = try #require(storage.requests.values.first)
        #expect(upload.method == "POST")
        #expect(upload.storagePath == "object/avatars/folder/a.png")
        #expect(upload.body == FakeStorageServer.photo)
        #expect(upload.storageHeader("Content-Type")?.hasPrefix("image/png") == true)
        #expect(upload.storageHeader("x-upsert") == "true")
        let metadata = try #require(upload.storageHeader("x-metadata").flatMap { Data(base64Encoded: $0) })
        #expect(try JSONDecoder().decode(OfflineJSON.self, from: metadata) == ["owner": "me"])
        #expect(uploaded == OfflineBucketApi.FileUploadResponse(id: "object-1", path: "folder/a.png", key: "avatars/a.png"))
        let update = try #require(storage.requests.values.last)
        #expect(update.method == "PUT")
        #expect(update.storageHeader("x-upsert") == "false")
        #expect(updated.path == "folder/a.png")
        try await client.close()
    }

    @Test func removeMoveAndCopySendWhatStorageReads() async throws {
        // Given
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")

        // When
        try await bucket.remove(paths: ["a.png", "b.png"])
        try await bucket.move(from: "a.png", to: "b.png")
        try await bucket.copy(from: "b.png", to: "c.png", destinationBucket: "archive")

        // Then
        let requests = storage.requests.values
        #expect(requests.map(\.method) == ["DELETE", "POST", "POST"])
        #expect(try requests[0].storageJSONBody() == ["prefixes": ["a.png", "b.png"]])
        #expect(requests[1].storagePath == "object/move")
        #expect(try requests[1].storageJSONBody() == ["bucketId": "avatars", "sourceKey": "a.png", "destinationKey": "b.png"])
        #expect(requests[2].storagePath == "object/copy")
        #expect(
            try requests[2].storageJSONBody()
                == ["bucketId": "avatars", "sourceKey": "b.png", "destinationKey": "c.png", "destinationBucket": "archive"]
        )
        try await client.close()
    }

    @Test func dataOverTwoGibibytesIsRefusedBeforeAnythingIsSent() async throws {
        // Given bytes the core cannot take in one call, which are never touched
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let tooLarge = Data(count: Int(Int32.max) + 1)

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.storage.from("avatars").upload("big.bin", data: tooLarge)
        }

        // Then
        #expect(error?.code == .invalidRequest)
        #expect(storage.requests.values.isEmpty)
        try await client.close()
    }

    // MARK: - URLs

    @Test func signedURLsAreStoragesAbsoluteURLs() async throws {
        // Given
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")

        // When
        let signed = try await bucket.createSignedURL(
            path: "a.png",
            expiresIn: 60,
            transform: .init(width: 100, height: 50, resize: .contain, quality: 70, format: "origin")
        )
        let several = try await bucket.createSignedURLs(paths: ["a.png", "b.png"], expiresIn: 120)
        let uploadURL = try await bucket.createSignedUploadURL(path: "a.png", upsert: true)
        let uploaded = try await bucket.uploadToSignedURL("a.png", token: uploadURL.token, data: FakeStorageServer.photo)

        // Then
        #expect(signed.absoluteString == "https://project.supabase.co/storage/v1/object/sign/avatars/a.png?token=t")
        let signing = try #require(storage.requests(below: "object/sign/avatars/a.png").first)
        #expect(
            try signing.storageJSONBody()
                == ["expiresIn": 60, "transform": ["width": 100, "height": 50, "resize": "contain", "quality": 70, "format": "origin"]]
        )
        #expect(several.map(\.path) == ["a.png", "b.png"])
        #expect(several.map(\.signedURL.absoluteString) == [
            "https://project.supabase.co/storage/v1/object/sign/avatars/a.png?token=a",
            "https://project.supabase.co/storage/v1/object/sign/avatars/b.png?token=b",
        ])
        #expect(several.allSatisfy { $0.error == nil })
        let signingSeveral = try #require(storage.requests.values.first { $0.storagePath == "object/sign/avatars" })
        #expect(try signingSeveral.storageJSONBody() == ["paths": ["a.png", "b.png"], "expiresIn": 120])
        #expect(uploadURL.token == "upload-token")
        #expect(uploadURL.path == "a.png")
        #expect(uploadURL.url.absoluteString.hasPrefix("https://project.supabase.co/storage/v1/object/upload/sign/avatars/a.png"))
        let signingUpload = try #require(storage.requests(below: "object/upload/sign/").first)
        #expect(signingUpload.method == "POST")
        #expect(signingUpload.storageHeader("x-upsert") == "true")
        let upload = try #require(storage.requests.values.last)
        #expect(upload.method == "PUT")
        #expect(upload.url.query()?.contains("token=upload-token") == true)
        #expect(upload.body == FakeStorageServer.photo)
        #expect(uploaded.path == "a.png")
        try await client.close()
    }

    @Test func publicAndAuthenticatedURLsAreBuiltWithoutARequest() async throws {
        // Given
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")

        // When
        let publicURL = try await bucket.publicURL(path: "folder/a.png")
        let authenticatedURL = try await bucket.authenticatedURL(path: "folder/a.png")

        // Then
        #expect(publicURL.absoluteString == "https://project.supabase.co/storage/v1/object/public/avatars/folder/a.png")
        #expect(
            authenticatedURL.absoluteString == "https://project.supabase.co/storage/v1/object/authenticated/avatars/folder/a.png"
        )
        #expect(storage.requests.values.isEmpty)
        try await client.close()
    }

    // MARK: - Listings and details

    @Test func listingsDetailsAndExistenceReadStoragesAnswers() async throws {
        // Given
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")
        let filter = OfflineBucketApi.StorageListFilter(
            limit: 10,
            offset: 20,
            search: "a",
            sortBy: .init(column: "name", order: .desc)
        )

        // When
        let listing = try await bucket.list(path: "folder", options: filter)
        let details = try await bucket.info(path: "a.png")
        let exists = try await bucket.exists(path: "a.png")
        let missing = try await bucket.exists(path: "missing.png")

        // Then
        #expect(listing == [
            OfflineBucketApi.FileObject(
                name: "a.png",
                id: "object-1",
                updatedAt: storageDate("2026-10-04T10:00:00.123Z"),
                createdAt: storageDate("2026-10-03T09:00:00Z"),
                lastAccessedAt: nil,
                metadata: ["size": 7, "mimetype": "image/png"]
            )
        ])
        let listRequest = try #require(storage.requests(below: "object/list/avatars").first)
        #expect(
            try listRequest.storageJSONBody()
                == ["prefix": "folder", "limit": 10, "offset": 20, "search": "a", "sortBy": ["column": "name", "order": "desc"]]
        )
        #expect(details.name == "a.png")
        #expect(details.id == "object-1")
        #expect(details.version == "v1")
        #expect(details.bucketId == "avatars")
        #expect(details.createdAt == storageDate("2026-10-03T09:00:00.5Z"))
        #expect(details.updatedAt == storageDate("2026-10-04T10:00:00Z"))
        #expect(details.lastAccessedAt == storageDate("2026-10-04T11:00:00Z"))
        #expect(details.lastModified == storageDate("2026-10-04T10:00:00Z"))
        #expect(details.size == 7)
        #expect(details.contentType == "image/png")
        #expect(details.cacheControl == "max-age=3600")
        #expect(details.etag == "\"abc\"")
        #expect(details.metadata == ["owner": "me"])
        #expect(exists)
        #expect(!missing)
        try await client.close()
    }

    @Test func aListingStorageCannotReadThrows() async throws {
        // Given a listing whose object has no name
        let storage = FakeStorageServer()
        storage.answer { _ in .json(#"[{"id":"object-1"}]"#) }
        let client = Self.makeClient(storage)

        // When
        let error = await #expect(throws: OfflineError.self) { try await client.storage.from("avatars").list() }

        // Then
        withKnownIssue("The core reports a Storage answer it cannot read as unexpected rather than decoding") {
            #expect(error?.code == .decoding)
        }
        try await client.close()
    }

    // MARK: - Cancellation and closing

    @Test func aCancelledDownloadThrowsCancellationErrorAtOnce() async throws {
        // Given a download Storage has not answered
        let storage = FakeStorageServer()
        let answered = Gate()
        storage.answer { _ in
            await answered.wait()
            return .status(200, body: FakeStorageServer.photo)
        }
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")
        let download = Task { try await bucket.download(path: "a.png") }
        #expect(await eventually { !storage.requests.values.isEmpty })

        // When
        download.cancel()
        let result = await download.result

        // Then
        #expect(throws: CancellationError.self) { try result.get() }
        await answered.open()
        #expect(try await bucket.cachedFile(path: "a.png") == nil)
        try await client.close()
    }

    @Test func aBucketOfAClosedClientThrowsClientClosed() async throws {
        // Given
        let storage = FakeStorageServer()
        let client = Self.makeClient(storage)
        let bucket = client.storage.from("avatars")
        try await client.close()

        // When
        let download = await #expect(throws: OfflineError.self) { try await bucket.download(path: "a.png") }
        let cached = await #expect(throws: OfflineError.self) { try await bucket.cachedFile(path: "a.png") }

        // Then
        #expect(download?.code == .clientClosed)
        #expect(cached?.code == .clientClosed)
        #expect(storage.requests.values.isEmpty)
    }
}
