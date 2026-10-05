// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import Synchronization

/// The user of demo mode, previews and tests: always signed in, with a token ``DemoServer`` reads.
/// Starting over makes a new user at once, with no network involved.
@Observable
final class DemoSession: UserSession {

    private(set) var state: SessionState

    /// The current user's token, read off the main actor by the offline client.
    @ObservationIgnored private let token: Mutex<String?>

    /// A session signed in as `userID`.
    init(userID: String = DeviceID.make()) {
        state = .signedIn(userID: userID)
        token = Mutex(DemoServer.accessToken(for: userID))
    }

    nonisolated func accessToken() async -> String? {
        token.withLock { $0 }
    }

    func signInWhenReachable(_ statuses: SyncStatusSequence) async {
        // Always signed in: there is nothing to wait for.
    }

    func signInAsNewUser() async throws {
        let userID = DeviceID.make()
        token.withLock { $0 = DemoServer.accessToken(for: userID) }
        state = .signedIn(userID: userID)
    }
}
