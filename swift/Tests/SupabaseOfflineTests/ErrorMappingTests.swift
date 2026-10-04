// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOfflineCore
import SupabaseOfflineTesting
import Testing
@testable import SupabaseOffline

/// Every failure the core reports lands on an error a Swift caller can catch.
struct ErrorMappingTests {

    /// The code each kind the core has today maps to; `nil` for a cancellation.
    private static let expectedCodes: [String: OfflineError.Code?] = [
        "CANCELLED": nil,
        "CLIENT_CLOSED": .clientClosed,
        "CONNECTIVITY": .network,
        "SERVER": .server,
        "HTTP": .server,
        "CACHE_MISS": .cacheMiss,
        "NETWORK_DISABLED": .networkDisabled,
        "MUTATION_NOT_QUEUEABLE": .mutationNotQueueable,
        "SYNC_FAILED": .syncFailed,
        "ACCESS_TOKEN": .accessToken,
        "INVALID_REQUEST": .invalidRequest,
        "UNSUPPORTED": .invalidRequest,
        "INVALID_ARGUMENT": .invalidRequest,
        "DECODING": .decoding,
        "UNEXPECTED": .internal,
    ]

    @Test func everyKindTheCoreHasLandsOnItsCode() {
        // Given every kind, so a kind the core adds fails here until it is mapped
        let kinds = BridgeErrorKind.entries
        #expect(Set(kinds.map(\.name)) == Set(Self.expectedCodes.keys))

        for kind in kinds {
            // When
            let thrown = OfflineError.thrown(
                kind: kind, message: "failed", httpStatus: nil, serverCode: nil, details: nil, hint: nil
            )

            // Then
            switch Self.expectedCodes[kind.name] {
            case .some(.some(let code)): #expect((thrown as? OfflineError)?.code == code, "\(kind.name)")
            case .some(.none): #expect(thrown is CancellationError, "\(kind.name)")
            case .none: Issue.record("\(kind.name) has no expected code")
            }
        }
    }

    @Test func aServerRefusalCarriesTheServersFields() throws {
        // When
        let thrown = OfflineError.thrown(
            kind: .server,
            message: "duplicate key value violates unique constraint",
            httpStatus: 409,
            serverCode: "23505",
            details: "Key (id)=(1) already exists.",
            hint: "Pick another id"
        )

        // Then
        let error = try #require(thrown as? OfflineError)
        #expect(error.code == .server)
        #expect(
            error.serverError
                == OfflineError.ServerError(
                    status: 409,
                    code: "23505",
                    message: "duplicate key value violates unique constraint",
                    details: "Key (id)=(1) already exists.",
                    hint: "Pick another id"
                )
        )
        #expect(error.localizedDescription == "duplicate key value violates unique constraint")
        #expect(error.description == "server (status 409, code 23505): duplicate key value violates unique constraint")
    }

    @Test func aMessageNamesNoRuntimeUnderThePackage() throws {
        // When
        let thrown = OfflineError.thrown(
            kind: .unexpected,
            message: "A bridge call failed unexpectedly (kotlin.IllegalStateException)",
            httpStatus: nil,
            serverCode: nil,
            details: nil,
            hint: nil
        )

        // Then
        let error = try #require(thrown as? OfflineError)
        #expect(error.message == "A core call failed unexpectedly (IllegalStateException)")
    }

    @Test func aCatchClauseMatchesByCode() {
        // Given
        func readFromCache() throws { throw OfflineError(code: .cacheMiss, message: "nothing cached") }

        // When
        var caught: OfflineError.Code?
        do {
            try readFromCache()
        } catch OfflineError.network {
            caught = .network
        } catch OfflineError.cacheMiss {
            caught = .cacheMiss
        } catch {
            caught = nil
        }

        // Then
        #expect(caught == .cacheMiss)
    }

    @Test func aRefusingServerThrowsServer() async throws {
        // Given
        let refusal = #"{"code":"42501","details":null,"hint":"Grant select","message":"permission denied for table todos"}"#
        let client = SupabaseOfflineClient.inMemory(server: FakeServer { _ in .json(refusal, status: 403) })
        defer { Task { try? await client.close() } }

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.rawSelect(readDescription(strategy: "networkOnly"))
        }

        // Then
        #expect(error?.code == .server)
        #expect(error?.serverError?.status == 403)
        #expect(error?.serverError?.code == "42501")
        #expect(error?.serverError?.hint == "Grant select")
        #expect(error?.message == "permission denied for table todos")
    }

    @Test func anUnreachableServerThrowsNetwork() async {
        // Given
        let client = SupabaseOfflineClient.inMemory()
        defer { Task { try? await client.close() } }

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.rawSelect(readDescription(strategy: "networkOnly"))
        }

        // Then
        #expect(error?.code == .network)
    }

    @Test func aCacheOnlyReadOfNothingThrowsCacheMiss() async {
        // Given
        let client = SupabaseOfflineClient.inMemory()
        defer { Task { try? await client.close() } }

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.rawSelect(readDescription(strategy: "cacheOnly"))
        }

        // Then
        #expect(error?.code == .cacheMiss)
    }

    @Test func aDescriptionTheCoreCannotReadThrowsInvalidRequest() async {
        // Given
        let client = SupabaseOfflineClient.inMemory()
        defer { Task { try? await client.close() } }

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.rawSelect(#"{"v":1}"#)
        }

        // Then
        #expect(error?.code == .invalidRequest)
    }

    @Test func aCallAfterCloseThrowsClientClosed() async throws {
        // Given
        let client = SupabaseOfflineClient.inMemory(server: FakeServer { _ in .json("[]") })
        try await client.close()

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.rawSelect(readDescription(strategy: "networkOnly"))
        }

        // Then
        #expect(error?.code == .clientClosed)
    }
}
