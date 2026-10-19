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
    /// title.
    static func client(supabaseURL: URL, supabaseKey: String) -> SupabaseOfflineClient {
        // --8<-- [start:configure]
        let offline = SupabaseOfflineClient(
            supabaseURL: supabaseURL,
            supabaseKey: supabaseKey,
            config: .init(
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
        )
        // --8<-- [end:configure]
        return offline
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

    @Test func theConfiguredClientOpensAndAnEmptyListSettlesNothing() async throws {
        // Given
        let listed = ResolvingConflictsListings.client(
            supabaseURL: URL(string: "https://in-memory.supabase.co")!,
            supabaseKey: "key"
        )
        try await listed.close()
        let offline = SupabaseOfflineClient.inMemory(config: .init(versionColumns: ["todos": "updated_at"]))

        // When
        try await ResolvingConflictsListings.keepMine(offline: offline)

        // Then
        #expect(try await offline.pendingMutationCount() == 0)
        try await offline.close()
    }
}
