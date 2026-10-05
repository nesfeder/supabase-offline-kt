// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore
import SupabaseOfflineTesting
import Testing
import os
@testable import SupabaseOffline

/// A Storage server for one test: it records every request and answers as Storage would, until
/// the test takes it offline or scripts another answer.
final class FakeStorageServer: Sendable {

    /// The bytes every authenticated download of an object answers with.
    static let photo = Data("a photo".utf8)

    /// The bytes every public download answers with.
    static let publicPhoto = Data("a public photo".utf8)

    /// Every request so far, in order.
    let requests = RecordingList<FakeServer.Request>()

    /// The answer scripted in place of Storage's, if any.
    private let scripted = OSAllocatedUnfairLock<(@Sendable (FakeServer.Request) async -> FakeServer.Response)?>(
        initialState: nil
    )

    /// The server the in-memory client is given.
    var server: FakeServer {
        FakeServer { request in
            self.requests.append(request)
            if let answer = self.scripted.withLock({ $0 }) { return await answer(request) }
            return Self.storageAnswer(to: request)
        }
    }

    /// Answers every later request with `answer` in place of Storage's.
    func answer(with answer: @escaping @Sendable (FakeServer.Request) async -> FakeServer.Response) {
        scripted.withLock { $0 = answer }
    }

    /// Fails every later request as if the device were offline.
    func goOffline() {
        answer { _ in .noConnection }
    }

    /// The requests so far whose path, below `/storage/v1/`, starts with `prefix`.
    func requests(below prefix: String) -> [FakeServer.Request] {
        requests.values.filter { $0.storagePath.hasPrefix(prefix) }
    }

    /// Storage's answer to `request`, for the bucket `avatars`.
    private static func storageAnswer(to request: FakeServer.Request) -> FakeServer.Response {
        let path = request.storagePath
        switch (request.method, path) {
        case ("GET", _) where path.hasPrefix("object/authenticated/"), ("GET", _) where path.hasPrefix("render/image/"):
            return .status(200, headers: ["Content-Type": "image/png"], body: photo)
        case ("GET", _) where path.hasPrefix("object/public/"):
            return .status(200, headers: ["Content-Type": "image/png"], body: publicPhoto)
        case ("GET", _) where path.hasPrefix("object/info/"):
            return .json(objectDetails)
        case ("HEAD", _):
            return .status(path.hasSuffix("/missing.png") ? 404 : 200)
        case ("POST", _) where path.hasPrefix("object/list/"):
            return .json(listing)
        case ("POST", "object/sign/avatars"):
            return .json(#"[{"error":null,"signedURL":"/object/sign/avatars/a.png?token=a","path":"a.png"},"#
                + #"{"error":null,"signedURL":"/object/sign/avatars/b.png?token=b","path":"b.png"}]"#)
        case ("POST", _) where path.hasPrefix("object/sign/"):
            return .json(#"{"signedURL":"/object/sign/avatars/a.png?token=t"}"#)
        case ("POST", _) where path.hasPrefix("object/upload/sign/"):
            return .json(#"{"url":"/object/upload/sign/avatars/a.png?token=upload-token"}"#)
        case ("POST", "object/move"):
            return .json(#"{"message":"Successfully moved"}"#)
        case ("POST", "object/copy"):
            return .json(#"{"Key":"avatars/b.png"}"#)
        case ("DELETE", _):
            return .json("[]")
        case ("POST", _), ("PUT", _):
            let name = path.split(separator: "/").last.map(String.init) ?? ""
            return .json(#"{"Key":"avatars/\#(name)","Id":"object-1"}"#)
        default:
            return .json(#"{"statusCode":"400","error":"unexpected","message":"No such route"}"#, status: 400)
        }
    }

    /// A listing of one object, as Storage sends it.
    static let listing = #"""
        [{"name":"a.png","id":"object-1","updated_at":"2026-10-04T10:00:00.123Z",
        "created_at":"2026-10-03T09:00:00Z","last_accessed_at":null,
        "metadata":{"size":7,"mimetype":"image/png"}}]
        """#

    /// One object's details, as Storage sends them.
    static let objectDetails = #"""
        {"id":"object-1","version":"v1","name":"a.png","bucket_id":"avatars",
        "updated_at":"2026-10-04T10:00:00Z","created_at":"2026-10-03T09:00:00.5Z",
        "last_accessed_at":"2026-10-04T11:00:00Z","size":7,"cache_control":"max-age=3600",
        "content_type":"image/png","etag":"\"abc\"","last_modified":"2026-10-04T10:00:00Z",
        "metadata":{"owner":"me"}}
        """#

    /// Storage's refusal with `status`, as it words one.
    static func refusal(status: Int) -> FakeServer.Response {
        refusal(ScriptedStorageRefusal(status: status, code: "refused", message: "Refused with \(status)"))
    }

    /// Storage's refusal `refusal`, in Storage's JSON.
    static func refusal(_ refusal: ScriptedStorageRefusal) -> FakeServer.Response {
        .json(
            #"{"statusCode":"\#(refusal.status)","error":"\#(refusal.code)","message":"\#(refusal.message)"}"#,
            status: refusal.status
        )
    }
}

/// One refusal Storage sends: its status, its error code and its message.
struct ScriptedStorageRefusal: Sendable, CustomTestStringConvertible {

    /// The HTTP status.
    let status: Int

    /// Storage's error code, such as `not_found`.
    let code: String

    /// Storage's message.
    let message: String

    /// Refusals as Storage words them.
    static let realistic = [
        ScriptedStorageRefusal(status: 400, code: "InvalidKey", message: "Invalid key: a b"),
        ScriptedStorageRefusal(status: 403, code: "Unauthorized", message: "new row violates row-level security policy"),
        ScriptedStorageRefusal(status: 404, code: "not_found", message: "Object not found"),
        ScriptedStorageRefusal(status: 409, code: "Duplicate", message: "The resource already exists"),
    ]

    var testDescription: String { "\(status) \(code)" }
}

/// Whether `text` names an exception or error type, or a package or word of the runtime and
/// libraries under the package, which a Swift caller must never read.
func namesTheRuntime(_ text: String) -> Bool {
    text.contains(/\b[A-Z][A-Za-z0-9]*(?:Exception|Error)\b/)
        || text.contains(/(?i)\b(?:kotlin|kotlinx|konan|ktor|okio|androidx|bridge|supabase-kt)\b/)
        || text.contains(/io\.github\.|StorageErrorResponse|Json[A-Z]/)
}

/// Every text `error` carries a caller can read: its message, description and the server's fields.
func readableTexts(of error: OfflineError?) -> [String] {
    guard let error else { return [] }
    return [error.message, error.description]
        + [error.serverError?.message, error.serverError?.details, error.serverError?.hint].compactMap { $0 }
}

extension FakeServer.Request {

    /// The URL's path below `/storage/v1/`, such as `object/authenticated/avatars/a.png`.
    var storagePath: String {
        let path = url.path(percentEncoded: false)
        let prefix = "/storage/v1/"
        return path.hasPrefix(prefix) ? String(path.dropFirst(prefix.count)) : path
    }

    /// The value of the header `name`, matched without regard to case.
    func storageHeader(_ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    /// The body read as JSON.
    func storageJSONBody() throws -> OfflineJSON {
        try JSONDecoder().decode(OfflineJSON.self, from: body)
    }
}

/// A date from ISO 8601 `text`, for comparing what a test decoded.
func storageDate(_ text: String) -> Date {
    guard let date = PostgresTimestamp.date(from: text) else { preconditionFailure("Not a date: \(text)") }
    return date
}

/// One answer of the core's download: its bytes, or the kind of its failure.
struct CoreDownloadAnswer: Sendable, Equatable {

    /// The bytes downloaded; `nil` on a failure.
    let bytes: Data?

    /// The failure's kind name, such as `CANCELLED`; `nil` on success.
    let failureKind: String?
}

extension SupabaseOfflineClient {

    /// Starts the core's own download of `path` from `bucket`, past the package's cancellation
    /// handling, and returns its handle, so a test decides when the cancellation reaches the core;
    /// `answers` records the core's completion.
    func coreDownload(bucket: String, path: String, answers: RecordingList<CoreDownloadAnswer>) async throws -> BridgeCancellable {
        let core = try await connection.openedClient().client
        return core.storage.download(bucket: bucket, path: path, isPublic: false, imageTransform: nil) {
            @Sendable bytes, failure in
            answers.append(CoreDownloadAnswer(bytes: bytes.map { $0 as Data }, failureKind: failure?.kind.name))
        }
    }

    /// Whether the device holds no copy of `path`, or exactly `bytes`: never a part of them, nor
    /// another object's. Turns the client's network off to read the copy, so only the cache answers.
    func holdsNothingOrExactly(_ bytes: Data, cachedAt path: String, in bucket: OfflineBucketApi) async throws -> Bool {
        guard let cached = try await bucket.cachedFile(path: path) else { return true }
        guard cached.sizeBytes == bytes.count else { return false }
        try await disableNetwork()
        return try await bucket.download(path: path) == bytes
    }
}
