// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
public import SupabaseOffline
import SupabaseOfflineCore

extension SupabaseOfflineClient {

    /// A client for tests and previews: the real cache and mutation queue, kept in memory, with
    /// every request answered by `server`.
    ///
    /// Every option applies but ``SupabaseOfflineClient/Options/dataDirectory``, which nothing
    /// reads. Failed reads are not retried, so a failure the server scripts arrives at once. The
    /// network reads as online until a test turns it off.
    ///
    /// - Parameters:
    ///   - supabaseURL: The project URL the requests carry.
    ///   - supabaseKey: The publishable key the requests carry.
    ///   - options: The client's options.
    ///   - server: Answers each request; by default, none ever reaches it.
    ///   - clock: The time the client sees; `nil` for the device's.
    public static func inMemory(
        supabaseURL: URL = URL(string: "https://in-memory.supabase.co")!,
        supabaseKey: String = "in-memory-publishable-key",
        options: Options = Options(),
        server: FakeServer = FakeServer(),
        clock: ManualClock? = nil
    ) -> SupabaseOfflineClient {
        let serverRelay = FakeServerRelay(server)
        let clockRelay = clock.map(ManualClockRelay.init)
        return SupabaseOfflineClient(supabaseURL: supabaseURL, supabaseKey: supabaseKey, options: options) {
            BridgeTesting.shared.openInMemory(options: $0, server: serverRelay, clock: clockRelay)
        }
    }
}
