// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// supabase-swift's `SupabaseClient` as far as the documentation's listings use it, so they compile
/// without that package: a listing names it `supabase`, as an app does, and never names this type.
final class FakeSupabaseSwiftClient: Sendable {

    /// supabase-swift's Auth client.
    let auth = FakeAuthClient()
}

/// supabase-swift's `AuthClient`: the signed-in session, and signing out.
final class FakeAuthClient: Sendable {

    /// The signed-in session; supabase-swift's throws when there is none, as this one never does.
    var session: FakeSession {
        get async throws { FakeSession(accessToken: "an-access-token") }
    }

    /// Signs the user out.
    func signOut() async throws {}
}

/// supabase-swift's `Session`, as far as the listings read it.
struct FakeSession: Sendable {

    /// The JSON Web Token the client sends for the signed-in user.
    let accessToken: String
}
