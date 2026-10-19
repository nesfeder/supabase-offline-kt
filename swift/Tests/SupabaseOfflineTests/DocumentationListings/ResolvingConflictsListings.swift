// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing

// The listings of the Resolving conflicts article. scripts/check-swift-doc-listings.py keeps the
// article's copies equal to the marked sections.

/// The article's configuration and calls.
enum ResolvingConflictsListings {

    /// A client whose `todos` replay against `updated_at`, with a resolver that keeps the server's
    /// title, and the configuration it was given.
    static func client(
        supabaseURL: URL,
        supabaseKey: String
    ) -> (offline: SupabaseOfflineClient, config: SupabaseOfflineClient.Config) {
        // --8<-- [start:configure]
        let config = SupabaseOfflineClient.Config(
            versionColumns: ["todos": "updated_at"],
            conflictResolver: { conflict in
                // Another device renamed the task: keep its title, send the rest.
                guard conflict.conflictingColumns == ["title"], var columns = conflict.local else {
                    return .keepLocal
                }
                columns.removeValue(forKey: "title")
                return columns.isEmpty ? .keepServer : .apply(columns: columns)
            }
        )
        let offline = SupabaseOfflineClient(supabaseURL: supabaseURL, supabaseKey: supabaseKey, config: config)
        // --8<-- [end:configure]
        return (offline, config)
    }

    /// Settles every parked write by keeping the device's change.
    static func keepMine(offline: SupabaseOfflineClient) async throws {
        // --8<-- [start:settle]
        for parked in try await offline.conflictedMutations().first() {
            try await offline.resolveConflictedMutation(id: parked.id, resolution: .keepLocal)
        }
        // --8<-- [end:settle]
    }
}

/// Runs the article's calls against an in-memory client.
struct ResolvingConflictsListingsTests {

    @Test func theResolverKeepsTheServersTitleAndAnEmptyListSettlesNothing() async throws {
        // Given the article's client and resolver
        let (listed, config) = ResolvingConflictsListings.client(
            supabaseURL: URL(string: "https://in-memory.supabase.co")!,
            supabaseKey: "key"
        )
        try await listed.close()
        let resolve = try #require(config.conflictResolver)
        let offline = SupabaseOfflineClient.inMemory(config: config)

        // When it is asked about a renamed task, a rename alone, and another clash
        let both = try await resolve(
            mutationConflict(mutationId: 1, table: "todos", operation: .update, rowKey: ["id": "1"],
                             local: ["title": "Mine", "done": true], conflictingColumns: ["title"])
        )
        let titleOnly = try await resolve(
            mutationConflict(mutationId: 2, table: "todos", operation: .update, rowKey: ["id": "1"],
                             local: ["title": "Mine"], conflictingColumns: ["title"])
        )
        let other = try await resolve(
            mutationConflict(mutationId: 3, table: "todos", operation: .update, rowKey: ["id": "1"],
                             local: ["done": true], conflictingColumns: ["done"])
        )
        try await ResolvingConflictsListings.keepMine(offline: offline)

        // Then the server's title stands, the rest is sent, and nothing was parked to settle
        #expect(both == .apply(columns: ["done": true]))
        #expect(titleOnly == .keepServer)
        #expect(other == .keepLocal)
        #expect(try await offline.pendingMutationCount() == 0)
        try await offline.close()
    }
}
