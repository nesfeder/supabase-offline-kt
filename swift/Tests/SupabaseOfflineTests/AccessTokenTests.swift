// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// The app's access-token closure: where it runs, and what reaches the server.
struct AccessTokenTests {

    @MainActor
    @Test func theClosureRunsOffTheMainActorEvenWhenAskedFromIt() async throws {
        // Given
        let ranOnMain = RecordingList<Bool>()

        // When
        let result = await withCheckedContinuation { (continuation: CheckedContinuation<Result<String?, any Error>, Never>) in
            AccessTokenRelay.fetch(
                using: {
                    ranOnMain.append(isOnMainThread())
                    return "user-token"
                },
                then: { continuation.resume(returning: $0) }
            )
        }

        // Then
        #expect(try result.get() == "user-token")
        #expect(ranOnMain.values == [false])
        MainActor.assertIsolated()
    }

    @Test func aThrowingClosureDeliversItsError() async {
        // Given
        struct SignedOut: Error {}

        // When
        let result = await withCheckedContinuation { (continuation: CheckedContinuation<Result<String?, any Error>, Never>) in
            AccessTokenRelay.fetch(using: { throw SignedOut() }, then: { continuation.resume(returning: $0) })
        }

        // Then
        #expect(throws: SignedOut.self) { try result.get() }
    }

    @Test func theTokenReachesEveryRequest() async throws {
        // Given
        let authorizations = RecordingList<String>()
        let client = SupabaseOfflineClient.inMemory(
            supabaseKey: "publishable-key",
            config: .init(accessToken: {
                try await Task.sleep(for: .milliseconds(5))
                return "user-token"
            }),
            server: FakeServer { request in
                authorizations.append(request.headers["Authorization"] ?? "none")
                return .json("[]")
            }
        )
        defer { Task { try? await client.close() } }

        // When
        _ = try await client.rawSelect(readDescription(strategy: "networkOnly"))

        // Then
        #expect(authorizations.values == ["Bearer user-token"])
    }

    @Test func aClosureThatThrowsFailsTheRequestWithAccessToken() async {
        // Given
        struct SignedOut: Error {}
        let requests = RecordingList<String>()
        let client = SupabaseOfflineClient.inMemory(
            config: .init(accessToken: { throw SignedOut() }),
            server: FakeServer { request in
                requests.append(request.url.path)
                return .json("[]")
            }
        )
        defer { Task { try? await client.close() } }

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.rawSelect(readDescription(strategy: "networkOnly"))
        }

        // Then
        #expect(error?.code == .accessToken)
        #expect(requests.values.isEmpty)
    }
}
