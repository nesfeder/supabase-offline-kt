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
/// cached rows and queued writes from another's, so the app clears its local data only while no
/// user is signed in: after ``signOut()``, before ``signIn()`` (``TaskRepository/startOver()``).
protocol UserSession: AnyObject, Observable, Sendable {

    /// Where the sign-in stands.
    var state: SessionState { get }

    /// The signed-in user's access token, or `nil` while there is none; the offline client asks
    /// for it before each request, off the main actor.
    nonisolated func accessToken() async -> String?

    /// Signs in whenever the client finds the network and no one is signed in, until `statuses`
    /// ends.
    func signInWhenReachable(_ statuses: SyncStatusSequence) async

    /// Signs out: from now on ``accessToken()`` returns `nil`. The session leaves the device even
    /// when the server cannot be told.
    func signOut() async

    /// Signs in as a new user.
    func signIn() async throws
}

extension UserSession {

    /// The signed-in user's id, in lower case; `nil` before there is a session.
    var userID: String? {
        guard case .signedIn(let userID) = state else { return nil }
        return userID
    }
}
