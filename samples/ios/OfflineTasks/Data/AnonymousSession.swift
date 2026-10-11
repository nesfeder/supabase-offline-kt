// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Auth
import Foundation
import SupabaseOffline

/// The app's user: an anonymous one, through supabase-swift's Auth. The app signs in the first
/// time it reaches the network, and Auth keeps the session in the Keychain, so a returning user is
/// the same user, offline too.
@Observable
final class AnonymousSession: UserSession {

    private(set) var state: SessionState

    /// Auth for the project; it keeps the session and refreshes its token.
    @ObservationIgnored private let auth: AuthClient

    /// Auth for the project at `projectURL`, with its publishable key.
    init(projectURL: URL, publishableKey: String) {
        auth = AuthClient(
            configuration: AuthClient.Configuration(
                url: projectURL.appending(path: "auth/v1"),
                headers: ["apikey": publishableKey],
                localStorage: KeychainLocalStorage(),
                emitLocalSessionAsInitialSession: true
            )
        )
        // A session kept from an earlier launch is this user's, even offline and even expired.
        state = auth.currentUser.map { .signedIn(userID: $0.id.uuidString.lowercased()) } ?? .signingIn
    }

    nonisolated func accessToken() async -> String? {
        // `session` refreshes an expired token first. With no session (or none that refreshes),
        // the request goes with the publishable key alone, which the schema refuses.
        try? await auth.session.accessToken
    }

    func signInWhenReachable(_ statuses: SyncStatusSequence) async {
        var wasReachable = false
        do {
            // The library's view of the network: the system's, or Simulate offline when it is on.
            for try await status in statuses {
                let isReachable = status.network == .online || status.network == .unknown
                if isReachable, !wasReachable { await signInIfSignedOut() }
                wasReachable = isReachable
            }
        } catch {
            // The status ends only when the client closes, which the app never does.
        }
    }

    func signOut() async {
        state = .signingIn
        // Auth drops the session from the device before it calls the server, so the token is gone
        // even when that call fails; for an anonymous user nobody can sign in as again, that is all.
        try? await auth.signOut()
    }

    func signIn() async throws {
        state = .signingIn
        do {
            let session = try await auth.signInAnonymously()
            state = .signedIn(userID: session.user.id.uuidString.lowercased())
        } catch {
            state = .failed(message: "Couldn’t sign in; tries again when online")
            throw error
        }
    }

    /// Signs in anonymously unless a session is already kept.
    private func signInIfSignedOut() async {
        if let user = auth.currentUser {
            state = .signedIn(userID: user.id.uuidString.lowercased())
            return
        }
        state = .signingIn
        do {
            let session = try await auth.signInAnonymously()
            state = .signedIn(userID: session.user.id.uuidString.lowercased())
        } catch {
            state = .failed(message: "Couldn’t sign in; tries again when online")
        }
    }
}
