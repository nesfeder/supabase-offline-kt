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
        "CONNECTIVITY": .connectivity,
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
        "UNEXPECTED": .unexpected,
    ]

    @Test func everyKindTheCoreHasLandsOnItsCode() {
        // Given every kind, so a kind the core adds fails here until it is mapped
        let kinds = BridgeErrorKind.entries
        #expect(Set(kinds.map(\.name)) == Set(Self.expectedCodes.keys))

        for kind in kinds {
            // When
            let thrown = OfflineError.thrown(
                kind: kind, message: "failed", httpStatus: nil, serverCode: nil, details: nil, hint: nil,
                isConnectivityFailure: false
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
            hint: "Pick another id",
            isConnectivityFailure: false
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
        #expect(error.httpStatus == 409)
        #expect(!error.isNetworkOrConnectivityFailure)
        #expect(error.localizedDescription == "duplicate key value violates unique constraint")
        #expect(error.description == "server (status 409, code 23505): duplicate key value violates unique constraint")
    }

    @Test func aNetworkFailureSaysSoAndCarriesNoStatus() throws {
        // When
        let offline = OfflineError.thrown(
            kind: .connectivity, message: "offline", httpStatus: nil, serverCode: nil, details: nil, hint: nil,
            isConnectivityFailure: false
        )
        let stalledSync = OfflineError.thrown(
            kind: .syncFailed, message: "offline", httpStatus: nil, serverCode: nil, details: nil, hint: nil,
            isConnectivityFailure: true
        )
        let refusedSync = OfflineError.thrown(
            kind: .syncFailed, message: "refused", httpStatus: 403, serverCode: "42501", details: nil, hint: nil,
            isConnectivityFailure: false
        )

        // Then
        #expect((offline as? OfflineError)?.isNetworkOrConnectivityFailure == true)
        #expect((offline as? OfflineError)?.httpStatus == nil)
        #expect((stalledSync as? OfflineError)?.isNetworkOrConnectivityFailure == true)
        #expect((refusedSync as? OfflineError)?.isNetworkOrConnectivityFailure == false)
        #expect((refusedSync as? OfflineError)?.httpStatus == 403)
        #expect((refusedSync as? OfflineError)?.serverError?.code == "42501")
    }

    @Test func aSyncStoppedOnTheDeviceKeepsTheLibrarysOwnSentence() throws {
        // Given passes stopped by neither the server nor the network: one by a request header the
        // HTTP client refuses, whose sentence says what to check, and one by any other failure
        let raws = [
            "A request header holds a character a header cannot carry, such as a line break; check the project key and the access token; the queued writes stay queued for a later pass",
            "A failure on this device stopped the sync pass; the queued writes stay queued for a later pass",
        ]

        for raw in raws {
            // When
            let thrown = OfflineError.thrown(
                kind: .syncFailed, message: raw, httpStatus: nil, serverCode: nil, details: nil, hint: nil,
                isConnectivityFailure: false
            )

            // Then the caller reads the sentence as the library wrote it
            let error = try #require(thrown as? OfflineError)
            #expect(error.code == .syncFailed)
            #expect(error.message == raw)
            #expect(error.serverError == nil)
            #expect(!error.isNetworkOrConnectivityFailure)
        }
    }

    @Test func aMessageNamesNoRuntimeUnderThePackage() throws {
        // When
        let thrown = OfflineError.thrown(
            kind: .unexpected,
            message: "A bridge call failed unexpectedly (kotlin.IllegalStateException)",
            httpStatus: nil,
            serverCode: nil,
            details: nil,
            hint: nil,
            isConnectivityFailure: false
        )

        // Then
        let error = try #require(thrown as? OfflineError)
        #expect(error.message == "The call failed unexpectedly")
        #expect(!error.localizedDescription.contains("IllegalStateException"))
    }

    @Test(arguments: ["SERVER", "HTTP", "SYNC_FAILED", "ACCESS_TOKEN", "INVALID_REQUEST"])
    func textTheCoreDidNotWriteReachesTheCallerVerbatim(kindName: String) throws {
        // Given a message naming a table called "bridge", which is the app's own data
        let kind = try #require(BridgeErrorKind.entries.first { $0.name == kindName })
        let serverText = #"relation "bridge" does not exist"#

        // When
        let thrown = OfflineError.thrown(
            kind: kind, message: serverText, httpStatus: 404, serverCode: "42P01", details: nil, hint: nil,
            isConnectivityFailure: false
        )

        // Then
        let error = try #require(thrown as? OfflineError)
        #expect(error.message == serverText, "\(kind.name)")
        #expect(error.serverError?.message == serverText, "\(kind.name)")
    }

    @Test func aCatchClauseMatchesByCode() {
        // Given
        func readFromCache() throws { throw OfflineError(code: .cacheMiss, message: "nothing cached") }

        // When
        var caught: OfflineError.Code?
        do {
            try readFromCache()
        } catch OfflineError.connectivity {
            caught = .connectivity
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
        #expect(error?.httpStatus == 403)
        #expect(error?.isNetworkOrConnectivityFailure == false)
        #expect(error?.serverError?.status == 403)
        #expect(error?.serverError?.code == "42501")
        #expect(error?.serverError?.hint == "Grant select")
        #expect(error?.message == "permission denied for table todos")
    }

    @Test func aServersOwnTextReachesTheAppAsSent() async throws {
        // Given
        let refusal = #"{"code":"42P01","details":null,"hint":"Kotlin clients qualify the relation","message":"relation \"bridge\" does not exist"}"#
        let client = SupabaseOfflineClient.inMemory(server: FakeServer { _ in .json(refusal, status: 404) })
        defer { Task { try? await client.close() } }

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.rawSelect(readDescription(table: "bridge", strategy: "networkOnly"))
        }

        // Then
        #expect(error?.message == #"relation "bridge" does not exist"#)
        #expect(error?.serverError?.message == #"relation "bridge" does not exist"#)
        #expect(error?.serverError?.hint == "Kotlin clients qualify the relation")
    }

    @Test func aRefusedConnectionNamesNeitherTheRequestNorItsValues() async throws {
        // Given a client of a local port nothing listens on
        let directory = FileManager.default.temporaryDirectory.appending(path: "refused-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let client = SupabaseOfflineClient(
            supabaseURL: URL(string: "http://127.0.0.1:9")!,
            supabaseKey: "key",
            config: .init(dataDirectory: directory)
        )

        // When a read of a table is filtered on a value nobody should see in an error
        let error = await #expect(throws: OfflineError.self) {
            try await client.rawSelect(
                #"{"v":1,"table":"patients","filters":[{"kind":"filter","column":"email","operator":"eq","value":"secret-filter-value"}],"strategy":{"kind":"networkOnly"}}"#
            )
        }
        try await client.close()

        // Then
        let thrown = try #require(error)
        #expect(thrown.code == .connectivity)
        #expect(thrown.isNetworkOrConnectivityFailure)
        for text in [thrown.message, thrown.localizedDescription, thrown.description] {
            for leak in ["secret-filter-value", "patients", "127.0.0.1", "http"] {
                #expect(!text.contains(leak), "\(text)")
            }
        }
    }

    @Test func anUnreachableServerThrowsConnectivity() async {
        // Given
        let client = SupabaseOfflineClient.inMemory()
        defer { Task { try? await client.close() } }

        // When
        let error = await #expect(throws: OfflineError.self) {
            try await client.rawSelect(readDescription(strategy: "networkOnly"))
        }

        // Then
        #expect(error?.code == .connectivity)
        #expect(error?.isNetworkOrConnectivityFailure == true)
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
