// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import os

/// supabase-swift's `SupabaseClient` as far as the documentation's listings use it, so they compile
/// without that package: a listing names it `supabase`, as an app does, and never names this type.
final class FakeSupabaseSwiftClient: Sendable {

    /// supabase-swift's Auth client.
    let auth: FakeAuthClient

    /// A client whose user holds `accessToken`, or no user when it is `nil`.
    init(signedInWith accessToken: String? = "an-access-token") {
        auth = FakeAuthClient(accessToken: accessToken)
    }
}

/// supabase-swift's `AuthClient`: the signed-in session, and signing out.
final class FakeAuthClient: Sendable {

    /// The signed-in user's token; `nil` when nobody is signed in.
    private let accessToken: OSAllocatedUnfairLock<String?>

    init(accessToken: String?) {
        self.accessToken = OSAllocatedUnfairLock(initialState: accessToken)
    }

    /// The signed-in session; it throws when nobody is signed in, as supabase-swift's does.
    var session: FakeSession {
        get async throws {
            guard let token = accessToken.withLock({ $0 }) else { throw FakeAuthError.sessionMissing }
            return FakeSession(accessToken: token)
        }
    }

    /// Signs the user out, taking a moment before the session is gone, as a sign-out that waits for
    /// the server does: a sequence that is right does not depend on how quickly it goes.
    func signOut() async throws {
        try await Task.sleep(for: .milliseconds(300))
        accessToken.withLock { $0 = nil }
    }

    /// Signs a user in with `token`; for the tests, which stand in for Auth's sign-in calls.
    func signIn(accessToken token: String) {
        accessToken.withLock { $0 = token }
    }
}

/// supabase-swift's `Session`, as far as the listings read it.
struct FakeSession: Sendable {

    /// The JSON Web Token the client sends for the signed-in user.
    let accessToken: String
}

/// supabase-swift's `AuthError.sessionMissing`.
enum FakeAuthError: Error {

    /// Nobody is signed in.
    case sessionMissing
}
