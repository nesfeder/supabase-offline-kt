// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import Testing
import os

/// A user switch with the network switched off around it: whatever point the network comes back
/// at, nothing of the first user is sent, fetched again or shown, and the second user's rows follow.
struct SignOutBracketTests {

    /// The steps of the switch, in order; the network comes back just before the one named.
    enum Point: Int, CaseIterable, Sendable, CustomTestStringConvertible {
        case beforeNetworkOff, beforeSignOut, duringSignOut, beforeClear, beforeNextUser, beforeNetworkOn, afterNetworkOn

        var testDescription: String { "network back \(self)" }
    }

    /// What the server saw: each request's method and token.
    struct Seen: Sendable, Equatable {
        let method: String
        let token: String
    }

    @Test(arguments: Point.allCases)
    func nothingOfTheFirstUserLeavesOrStaysWhereverTheNetworkReturns(point: Point) async throws {
        // Given user A online with a live read of A's rows open, then a write A made offline
        let supabase = FakeSupabaseSwiftClient(signedInWith: "user-a-token")
        let reachable = OSAllocatedUnfairLock(initialState: true)
        let seen = RecordingList<Seen>()
        let server = FakeServer { request in
            guard reachable.withLock({ $0 }) else { return .noConnection }
            let token = String((request.headers["Authorization"] ?? "").split(separator: " ").last ?? "")
            seen.append(Seen(method: request.method, token: token))
            guard request.method == "GET" else { return .json("[]", status: 201) }
            switch token {
            case "user-a-token": return .json(#"[{"id":"7f4c8e2a-1b3d-4e5f-8a9b-0c1d2e3f4a5b","title":"A's errand","done":false}]"#)
            case "user-b-token": return .json(#"[{"id":"0b9d6c3e-2a1f-4e7d-9c8b-1a2b3c4d5e6f","title":"B's errand","done":false}]"#)
            default: return .json("[]")
            }
        }
        let network = FakeNetworkAvailability()
        let offline = SupabaseOfflineClient.inMemory(
            config: .init(accessToken: { try? await supabase.auth.session.accessToken }, networkAvailability: network.makeStream),
            server: server
        )
        #expect(await eventually { network.streamCount == 1 })
        network.report(true)
        let shown = RecordingList<[String]>()
        let live = Task {
            for try await snapshot in offline.from("todos").selectAsSequence(as: [Todo].self) {
                shown.append((snapshot.result ?? []).map(\.title))
            }
        }
        #expect(await eventually { shown.values.last == ["A's errand"] })
        reachable.withLock { $0 = false }
        network.report(false)
        _ = try await offline.from("todos").insert(Todo(id: UUID(), title: "A's offline errand", done: false)).execute()
        #expect(try await offline.pendingMutationCount() == 1)
        let comeBack: @Sendable () -> Void = {
            reachable.withLock { $0 = true }
            network.report(true)
        }

        // When the network comes back at the point under test, and A is switched out for B
        if point == .beforeNetworkOff { comeBack() }
        try await offline.disableNetwork()
        if point == .beforeSignOut { comeBack() }
        if point == .duringSignOut {
            async let signedOut: Void = supabase.auth.signOut()
            try await Task.sleep(for: .milliseconds(100))
            comeBack()
            try await signedOut
        } else {
            try await supabase.auth.signOut()
        }
        if point == .beforeClear { comeBack() }
        try await offline.clearLocalData()
        let sentBeforeClear = seen.values.count
        // A snapshot made before the clear may still be on its way to the loop: wait for the
        // cleared state itself, which nothing replaces while the network is off
        #expect(await eventually { shown.values.last == [] })
        let cleared = shown.values.count - 1
        if point == .beforeNextUser { comeBack() }
        supabase.auth.signIn(accessToken: "user-b-token")
        if point == .beforeNetworkOn { comeBack() }
        try await offline.enableNetwork()
        if point == .afterNetworkOn { comeBack() }

        // Then the open live read shows B's rows; A's write went, if at all, with A's own token
        // while A was signed in; nothing went with A's token after the clear; nothing of A's is
        // shown after it or kept
        #expect(await eventually { shown.values.last == ["B's errand"] })
        let sent = seen.values
        #expect(sent.allSatisfy { $0.method == "GET" || $0.token == "user-a-token" }, "\(sent)")
        #expect(sent.dropFirst(sentBeforeClear).allSatisfy { $0.token == "user-b-token" }, "\(sent)")
        #expect(shown.values.dropFirst(cleared).allSatisfy { !$0.contains("A's errand") && !$0.contains("A's offline errand") })
        #expect(try await offline.pendingMutationCount() == 0)
        live.cancel()
        try await offline.disableNetwork()
        let kept = try await offline.from("todos").select(readStrategy: .cacheOnly).execute([Todo].self).value
        #expect(kept.map(\.title) == ["B's errand"])
        try await offline.close()
    }
}
