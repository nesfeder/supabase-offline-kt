// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import SwiftUI
import Testing

// The listings of the Handling errors article. scripts/check-swift-doc-listings.py keeps the
// article's copies equal to the marked sections.

/// The article's calls, made through the client an app keeps.
struct HandlingErrorsListings {

    /// The app's client.
    let offline: SupabaseOfflineClient

    /// A read whose failures the app tells apart, and the message it shows for each.
    func load() async -> (todos: [Todo], message: String?) {
        var todos: [Todo] = []
        var message: String?
        // --8<-- [start:catch-codes]
        do {
            todos = try await offline.from("todos").select().execute().value
        } catch let error as OfflineError where error.isNetworkOrConnectivityFailure {
            message = "You're offline, and this list has not been loaded on this device yet."
        } catch let error as OfflineError where error.code == .server {
            message = error.serverError?.message ?? error.message  // the server's refusal
        } catch is CancellationError {
            // The task was canceled: nothing to show.
        } catch {
            message = error.localizedDescription
        }
        // --8<-- [end:catch-codes]
        return (todos, message)
    }

    /// A read that may use only the device's copy.
    func savedOnly() async throws -> [Todo]? {
        // --8<-- [start:cache-miss]
        do {
            return try await offline.from("todos").select(readStrategy: .cacheOnly).execute().value
        } catch OfflineError.cacheMiss {
            return nil  // nothing on the device yet for this query
        }
        // --8<-- [end:cache-miss]
    }

    /// Writes the server refused when the queue was sent: retried when the cause is gone,
    /// otherwise dropped.
    func handleRejected(parentsNowExist: Bool) async throws {
        // --8<-- [start:rejected]
        for rejected in try await offline.rejectedMutations().first() {
            if rejected.isRetryable, rejected.serverError?.code == "23503", parentsNowExist {
                try await offline.retryRejectedMutation(id: rejected.id)  // its parent row exists now
            } else {
                try await offline.dismissRejectedMutation(id: rejected.id)  // after showing it
            }
        }
        // --8<-- [end:rejected]
    }
}

// --8<-- [start:status]
struct SyncBanner: View {
    let offline: SupabaseOfflineClient
    @State private var status: SyncStatus?

    var body: some View {
        Group {
            if let status, status.network == .offline || status.network == .disabled {
                Text("Offline: \(status.pendingMutationCount) changes waiting")
            } else if let status, status.rejectedMutationCount > 0 {
                Text("\(status.rejectedMutationCount) changes were refused")
            } else if status?.lastSyncError != nil {
                Text("Some changes could not be sent yet")
            }
        }
        .task {
            do {
                for try await next in offline.syncStatus {
                    status = next
                }
            } catch {
                // Iterating a closed client's status throws clientClosed.
            }
        }
    }
}
// --8<-- [end:status]

/// Runs the Handling errors article's calls against an in-memory client.
struct HandlingErrorsListingsTests {

    @Test func eachFailureIsToldApartAndARefusedWriteIsRetried() async throws {
        // Given a server that refuses the first write for a missing parent row and accepts the
        // next, and a client whose server is out of reach
        let writes = RecordingList<String>()
        let server = FakeServer { request in
            writes.append(request.method)
            return writes.values.count == 1
                ? .json(#"{"code":"23503","message":"insert or update on table violates foreign key constraint"}"#, status: 409)
                : .json("[]", status: 201)
        }
        let offline = SupabaseOfflineClient.inMemory(config: .init(autoSync: false), server: server)
        let unreachable = SupabaseOfflineClient.inMemory(config: .init(autoSync: false))
        try await offline.disableNetwork()
        try await offline.from("todos").insert(Todo(id: UUID(), title: "Buy milk", done: false)).execute()
        try await offline.enableNetwork()
        let report = try await offline.syncPendingMutations()

        // When the app reads with nothing cached, and retries the refused write
        let (_, message) = await HandlingErrorsListings(offline: unreachable).load()
        let saved = try await HandlingErrorsListings(offline: unreachable).savedOnly()
        try await HandlingErrorsListings(offline: offline).handleRejected(parentsNowExist: true)
        try await offline.syncPendingMutations()

        // Then the read reports the network, the cache-only read finds nothing, and the retried
        // write is sent
        #expect(message?.hasPrefix("You're offline") == true)
        #expect(saved == nil)
        #expect(report.rejected.count == 1)
        #expect(try await offline.rejectedMutations().first().isEmpty)
        #expect(try await offline.pendingMutationCount() == 0)
        #expect(writes.values.count == 2)
        try await unreachable.close()
        try await offline.close()
    }
}
