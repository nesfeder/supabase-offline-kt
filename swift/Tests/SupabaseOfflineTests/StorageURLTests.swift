// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// Storage's URLs hold the object's name unencoded; every one the package returns parses on any OS,
/// iOS 16's strict parser included, without encoding an escape twice.
struct StorageURLTests {

    /// The project's Storage objects, below which every URL here sits.
    private static let objects = "https://project.supabase.co/storage/v1/object"

    @Test(arguments: [
        ("/public/avatars/my folder/a b.png", "/public/avatars/my%20folder/a%20b.png"),
        ("/public/avatars/café ☕ 写真.png", "/public/avatars/caf%C3%A9%20%E2%98%95%20%E5%86%99%E7%9C%9F.png"),
        ("/public/avatars/100% done.png", "/public/avatars/100%25%20done.png"),
        ("/sign/avatars/a b.png?token=t.x", "/sign/avatars/a%20b.png?token=t.x"),
    ])
    func aURLTheStrictParserRefusesIsReadOnceEncoded(written: String, encoded: String) throws {
        // Given text no strict parser takes as written, on any OS
        let text = Self.objects + written
        #expect(CoreURL.strictlyParsed(text) == nil)

        // When
        let url = try CoreURL.parse(text)

        // Then
        #expect(url.absoluteString == Self.objects + encoded)
    }

    @Test func anEscapeAlreadyInTheURLIsKeptAsItIs() throws {
        // Given a name encoded once already, beside a space that is not
        let text = Self.objects + "/public/avatars/my%20folder/caf%C3%A9 b.png"

        // When
        let url = try CoreURL.parse(text)

        // Then
        #expect(url.absoluteString == Self.objects + "/public/avatars/my%20folder/caf%C3%A9%20b.png")
        #expect(try CoreURL.parse(Self.objects + "/public/avatars/a%20b.png").absoluteString == Self.objects + "/public/avatars/a%20b.png")
    }

    @Test func theSchemeAndHostAreLeftAsTheyAre() throws {
        // When
        let url = try CoreURL.parse("http://[::1]:54321/storage/v1/object/public/avatars/a b.png")

        // Then
        #expect(url.absoluteString == "http://[::1]:54321/storage/v1/object/public/avatars/a%20b.png")
    }

    @Test func textThatIsNoURLEvenEncodedIsADecodingFailure() {
        // When
        let error = #expect(throws: OfflineError.self) { try CoreURL.parse("https://[not a host/a.png") }

        // Then
        #expect(error?.code == .decoding)
    }

    @Test func everyURLTheBucketReturnsIsEncoded() async throws {
        // Given a Storage that signs a name with a space and accents, as it sends them
        let storage = FakeStorageServer()
        storage.answer { request in
            switch request.storagePath {
            case "object/sign/avatars":
                return .json(#"[{"error":null,"signedURL":"/object/sign/avatars/my folder/café.png?token=a","path":"my folder/café.png"}]"#)
            case let path where path.hasPrefix("object/upload/sign/"):
                return .json(#"{"url":"/object/upload/sign/avatars/my folder/café.png?token=up"}"#)
            default:
                return .json(#"{"signedURL":"/object/sign/avatars/my folder/café.png?token=t"}"#)
            }
        }
        let client = SupabaseOfflineClient.inMemory(supabaseURL: URL(string: "https://project.supabase.co")!, server: storage.server)
        let bucket = client.storage.from("avatars")
        let name = "my folder/café.png"

        // When
        let publicURL = try await bucket.publicURL(path: name)
        let authenticatedURL = try await bucket.authenticatedURL(path: name)
        let signed = try await bucket.createSignedURL(path: name, expiresIn: 60)
        let several = try await bucket.createSignedURLs(paths: [name], expiresIn: 60)
        let upload = try await bucket.createSignedUploadURL(path: name)

        // Then
        let encodedName = "my%20folder/caf%C3%A9.png"
        #expect(publicURL.absoluteString == Self.objects + "/public/avatars/" + encodedName)
        #expect(authenticatedURL.absoluteString == Self.objects + "/authenticated/avatars/" + encodedName)
        #expect(signed.absoluteString == Self.objects + "/sign/avatars/" + encodedName + "?token=t")
        #expect(several.map(\.signedURL.absoluteString) == [Self.objects + "/sign/avatars/" + encodedName + "?token=a"])
        #expect(upload.url.absoluteString.hasPrefix(Self.objects + "/upload/sign/avatars/" + encodedName))
        #expect(upload.token == "up")
        try await client.close()
    }

    @Test(arguments: ["why?.png", "a#b.png", "why? now.png", "notes/what?/x.png", "100%.png"])
    func everyURLNamesTheObjectItWasGivenWhateverItsDelimiters(name: String) async throws {
        // Given a Storage that signs the object the request names, as Storage does: in the URL's
        // path as the request wrote it
        let storage = FakeStorageServer()
        storage.answer { request in
            let sentPath = String(request.url.path(percentEncoded: true).dropFirst("/storage/v1".count))
            if request.storagePath.hasPrefix("object/upload/sign/") {
                return request.method == "POST"
                    ? .json(#"{"url":"\#(sentPath)?token=up"}"#)
                    : .json(#"{"Key":"avatars/x","Id":"object-1"}"#)
            }
            return .json(#"{"signedURL":"\#(sentPath)?token=t"}"#)
        }
        let client = SupabaseOfflineClient.inMemory(supabaseURL: URL(string: "https://project.supabase.co")!, server: storage.server)
        let bucket = client.storage.from("avatars")

        // When
        let publicURL = try await bucket.publicURL(path: name)
        let authenticatedURL = try await bucket.authenticatedURL(path: name)
        let signed = try await bucket.createSignedURL(path: name, expiresIn: 60)
        let upload = try await bucket.createSignedUploadURL(path: name)
        try await bucket.uploadToSignedURL(name, token: upload.token, data: FakeStorageServer.photo)

        // Then each URL's path decodes back to the name, with no query or fragment of its own
        let base = "/storage/v1/object"
        #expect(publicURL.path(percentEncoded: false) == "\(base)/public/avatars/\(name)")
        #expect(publicURL.query() == nil && publicURL.fragment() == nil)
        #expect(authenticatedURL.path(percentEncoded: false) == "\(base)/authenticated/avatars/\(name)")
        #expect(authenticatedURL.query() == nil && authenticatedURL.fragment() == nil)
        #expect(signed.path(percentEncoded: false) == "\(base)/sign/avatars/\(name)")
        #expect(signed.query() == "token=t" && signed.fragment() == nil)
        #expect(upload.url.path(percentEncoded: false) == "\(base)/upload/sign/avatars/\(name)")
        #expect(upload.url.query() == "token=up")
        #expect(upload.path == name)
        let requests = storage.requests.values
        #expect(requests.map(\.storagePath) == [
            "object/sign/avatars/\(name)", "object/upload/sign/avatars/\(name)", "object/upload/sign/avatars/\(name)",
        ])
        #expect(requests.map { $0.url.query() } == [nil, nil, "token=up"])
        try await client.close()
    }
}
