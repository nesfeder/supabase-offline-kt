// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

/// Storage calls written the way an app moving from supabase-swift writes them, with only the
/// receiver changed: this file must compile against the public API alone (no `@testable` import),
/// and the test sends every call to a fake Storage server.
struct StorageParityTests {

    /// Answers as Storage would: bytes for a download, `true` for a `HEAD`, and a plausible JSON
    /// answer for everything else.
    private static let server = FakeServer { request in
        let path = request.url.path(percentEncoded: false)
        switch request.method {
        case "GET" where path.contains("/object/info/"):
            return .json(#"{"id":"1","version":"v1","name":"a.jpg","created_at":"2026-10-04T10:00:00Z","size":4,"content_type":"image/jpeg"}"#)
        case "GET": return .status(200, headers: ["Content-Type": "image/jpeg"], body: Data("jpeg".utf8))
        case "HEAD": return .status(200)
        case "DELETE": return .json("[]")
        case "POST" where path.contains("/object/list/"):
            return .json(#"[{"name":"a.jpg","id":"1","updated_at":null,"created_at":null,"last_accessed_at":null,"metadata":null}]"#)
        case "POST" where path.contains("/object/sign/"): return .json(#"{"signedURL":"/object/sign/photos/a.jpg?token=t"}"#)
        case "POST" where path.hasSuffix("/object/move"): return .json(#"{"message":"moved"}"#)
        case "POST" where path.hasSuffix("/object/copy"): return .json(#"{"Key":"photos/b.jpg"}"#)
        default: return .json(#"{"Key":"photos/a.jpg","Id":"1"}"#)
        }
    }

    @Test func supabaseSwiftStorageCallsCompileAndRun() async throws {
        let db = SupabaseOfflineClient.inMemory(server: Self.server)
        let path = "a.jpg"

        // The calls as supabase-swift writes them.
        let jpeg = try await db.storage.from("photos").download(path: path)
        try await db.storage.from("photos").upload(path, data: jpeg, options: .init(contentType: "image/jpeg"))
        try await db.storage.from("photos").upload(path, data: jpeg)
        try await db.storage.from("photos").update(path, data: jpeg, options: .init(contentType: "image/jpeg", upsert: true))
        let files = try await db.storage.from("photos").list(path: "folder")
        let info = try await db.storage.from("photos").info(path: path)
        let exists = try await db.storage.from("photos").exists(path: path)
        let signed: URL = try await db.storage.from("photos").createSignedURL(path: path, expiresIn: 3600)
        try await db.storage.from("photos").move(from: path, to: "b.jpg")
        try await db.storage.from("photos").copy(from: "b.jpg", to: path)
        try await db.storage.from("photos").remove(paths: ["b.jpg"])

        // The offline additions.
        let ensured: CachedFile = try await db.storage.from("photos").ensureCachedFile(path: path)
        let cached: CachedFile? = try await db.storage.from("photos").cachedFile(path: path)

        #expect(jpeg == Data("jpeg".utf8))
        #expect(files.map(\.name) == ["a.jpg"])
        #expect(info.size == 4)
        #expect(exists)
        #expect(signed.query()?.contains("token=t") == true)
        #expect(cached?.path == ensured.path)
        try await db.close()
    }
}
