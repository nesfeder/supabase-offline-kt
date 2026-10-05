// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import Synchronization

/// The user of demo mode, previews and tests, with a token ``DemoServer`` reads. Signing in makes a
/// new user at once, with no network involved.
@Observable
final class DemoSession: UserSession {

    private(set) var state: SessionState

    /// The current user's token, read off the main actor by the offline client.
    @ObservationIgnored private let token: Mutex<String?>

    /// How long signing out takes before the token is gone, as a real sign-out takes a moment.
    @ObservationIgnored private let signOutDelay: Duration

    /// A session signed in as `userID`, whose sign-out takes `signOutDelay`.
    init(userID: String = DeviceID.make(), signOutDelay: Duration = .zero) {
        state = .signedIn(userID: userID)
        token = Mutex(DemoServer.accessToken(for: userID))
        self.signOutDelay = signOutDelay
    }

    nonisolated func accessToken() async -> String? {
        token.withLock { $0 }
    }

    func signInWhenReachable(_ statuses: SyncStatusSequence) async {
        // Always signed in: there is nothing to wait for.
    }

    func signOut() async {
        try? await Task.sleep(for: signOutDelay)
        token.withLock { $0 = nil }
        state = .signingIn
    }

    func signIn() async throws {
        let userID = DeviceID.make()
        token.withLock { $0 = DemoServer.accessToken(for: userID) }
        state = .signedIn(userID: userID)
    }
}
