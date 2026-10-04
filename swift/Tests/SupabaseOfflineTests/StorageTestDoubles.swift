// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
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
        .json(#"{"statusCode":"\#(status)","error":"refused","message":"Refused with \#(status)"}"#, status: status)
    }
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
