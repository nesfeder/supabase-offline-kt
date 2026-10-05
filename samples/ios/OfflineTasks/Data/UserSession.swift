// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// Where the app's sign-in stands.
enum SessionState: Equatable, Sendable {

    /// Restoring a saved session, or signing in.
    case signingIn

    /// Signed in: every row and file belongs to this user.
    case signedIn(userID: String)

    /// Signing in failed; it is tried again when the network comes back.
    case failed(message: String)
}

/// Who the app is signed in as. The schema refuses every request without a signed-in user, and
/// every row and photo path carries this user's id.
///
/// The offline client learns who the user is only from ``accessToken()``. It cannot tell one user's
/// queued writes from another's, so the app clears its local data before this can return another
/// user's token (``TaskRepository/startOver()``).
protocol UserSession: AnyObject, Observable, Sendable {

    /// Where the sign-in stands.
    var state: SessionState { get }

    /// The signed-in user's access token, or `nil` while there is none; the offline client asks
    /// for it before each request, off the main actor.
    nonisolated func accessToken() async -> String?

    /// Signs in whenever the client finds the network and no one is signed in, until `statuses`
    /// ends.
    func signInWhenReachable(_ statuses: SyncStatusSequence) async

    /// Signs out, and signs in again as a new user with nothing of the last one's.
    func signInAsNewUser() async throws
}

extension UserSession {

    /// The signed-in user's id, in lower case; `nil` before there is a session.
    var userID: String? {
        guard case .signedIn(let userID) = state else { return nil }
        return userID
    }
}
