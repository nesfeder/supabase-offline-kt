// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
public import SupabaseOffline
import SupabaseOfflineCore

extension SupabaseOfflineClient {

    /// A client for tests and previews: the real cache and mutation queue, kept in memory, with
    /// every request answered by `server`.
    ///
    /// Every option applies but `SupabaseOfflineClient.Config.dataDirectory`, which nothing
    /// reads. Failed reads are not retried, so a failure the server scripts arrives at once. It
    /// watches no path monitor: the network reads as `NetworkStatus.unknown`, which counts as
    /// online, until a test reports it through `SupabaseOfflineClient.Config.networkAvailability`
    /// or calls `SupabaseOfflineClient.disableNetwork()`. The tables
    /// `SupabaseOfflineClient.Config.realtimeTables` names are followed by nothing: hand a
    /// ``FakeRealtime`` to ``inMemory(supabaseURL:supabaseKey:config:server:clock:realtime:)`` to
    /// report changes to them. Each client starts empty: build clients over one ``InMemoryStore``
    /// with ``inMemory(supabaseURL:supabaseKey:config:server:clock:store:)`` to have one find what
    /// another left.
    ///
    /// - Parameters:
    ///   - supabaseURL: The project URL the requests carry.
    ///   - supabaseKey: The publishable key the requests carry.
    ///   - config: The client's configuration.
    ///   - server: Answers each request; by default, none ever reaches it.
    ///   - clock: The time the client sees; `nil` for the device's.
    public static func inMemory(
        supabaseURL: URL = URL(string: "https://in-memory.supabase.co")!,
        supabaseKey: String = "in-memory-publishable-key",
        config: Config = Config(),
        server: FakeServer = FakeServer(),
        clock: ManualClock? = nil
    ) -> SupabaseOfflineClient {
        let serverRelay = FakeServerRelay(server)
        let clockRelay = clock.map(ManualClockRelay.init)
        return SupabaseOfflineClient(supabaseURL: supabaseURL, supabaseKey: supabaseKey, config: config) {
            BridgeTesting.shared.openInMemory(options: $0, server: serverRelay, clock: clockRelay, realtime: nil, store: nil)
        }
    }

    /// A client for tests and previews whose Realtime tables a ``FakeRealtime`` stands in for: as
    /// ``inMemory(supabaseURL:supabaseKey:config:server:clock:)``, with the tables
    /// `SupabaseOfflineClient.Config.realtimeTables` names followed through `realtime`, which the
    /// test reports changes to, in place of a Realtime channel.
    ///
    /// - Parameters:
    ///   - supabaseURL: The project URL the requests carry.
    ///   - supabaseKey: The publishable key the requests carry.
    ///   - config: The client's configuration, naming the tables to follow.
    ///   - server: Answers each request; by default, none ever reaches it.
    ///   - clock: The time the client sees; `nil` for the device's.
    ///   - realtime: Where the named tables' channel is joined and changes are reported.
    public static func inMemory(
        supabaseURL: URL = URL(string: "https://in-memory.supabase.co")!,
        supabaseKey: String = "in-memory-publishable-key",
        config: Config = Config(),
        server: FakeServer = FakeServer(),
        clock: ManualClock? = nil,
        realtime: FakeRealtime
    ) -> SupabaseOfflineClient {
        let serverRelay = FakeServerRelay(server)
        let clockRelay = clock.map(ManualClockRelay.init)
        return SupabaseOfflineClient(supabaseURL: supabaseURL, supabaseKey: supabaseKey, config: config) {
            BridgeTesting.shared.openInMemory(
                options: $0, server: serverRelay, clock: clockRelay, realtime: realtime.relay, store: nil
            )
        }
    }

    /// A client for tests whose local data `store` keeps: as
    /// ``inMemory(supabaseURL:supabaseKey:config:server:clock:)``, starting with what an earlier
    /// client over `store` left there and leaving its own there when it closes, so a test closes one
    /// client and builds the next to see what the app does after a relaunch.
    ///
    /// A client built while another holds `store`, open or still closing, throws `OfflineError`
    /// `configuration` from every call: close that one first.
    ///
    /// - Parameters:
    ///   - supabaseURL: The project URL the requests carry.
    ///   - supabaseKey: The publishable key the requests carry.
    ///   - config: The client's configuration.
    ///   - server: Answers each request; by default, none ever reaches it.
    ///   - clock: The time the client sees; `nil` for the device's.
    ///   - store: The local data the client keeps, shared with the clients before and after it.
    public static func inMemory(
        supabaseURL: URL = URL(string: "https://in-memory.supabase.co")!,
        supabaseKey: String = "in-memory-publishable-key",
        config: Config = Config(),
        server: FakeServer = FakeServer(),
        clock: ManualClock? = nil,
        store: InMemoryStore
    ) -> SupabaseOfflineClient {
        let serverRelay = FakeServerRelay(server)
        let clockRelay = clock.map(ManualClockRelay.init)
        return SupabaseOfflineClient(supabaseURL: supabaseURL, supabaseKey: supabaseKey, config: config) {
            BridgeTesting.shared.openInMemory(
                options: $0, server: serverRelay, clock: clockRelay, realtime: nil, store: store.relay
            )
        }
    }

    /// A client for tests whose local data `store` keeps and whose Realtime tables a
    /// ``FakeRealtime`` stands in for: as
    /// ``inMemory(supabaseURL:supabaseKey:config:server:clock:store:)``, with the tables
    /// `SupabaseOfflineClient.Config.realtimeTables` names followed through `realtime`.
    ///
    /// - Parameters:
    ///   - supabaseURL: The project URL the requests carry.
    ///   - supabaseKey: The publishable key the requests carry.
    ///   - config: The client's configuration, naming the tables to follow.
    ///   - server: Answers each request; by default, none ever reaches it.
    ///   - clock: The time the client sees; `nil` for the device's.
    ///   - realtime: Where the named tables' channel is joined and changes are reported.
    ///   - store: The local data the client keeps, shared with the clients before and after it.
    public static func inMemory(
        supabaseURL: URL = URL(string: "https://in-memory.supabase.co")!,
        supabaseKey: String = "in-memory-publishable-key",
        config: Config = Config(),
        server: FakeServer = FakeServer(),
        clock: ManualClock? = nil,
        realtime: FakeRealtime,
        store: InMemoryStore
    ) -> SupabaseOfflineClient {
        let serverRelay = FakeServerRelay(server)
        let clockRelay = clock.map(ManualClockRelay.init)
        return SupabaseOfflineClient(supabaseURL: supabaseURL, supabaseKey: supabaseKey, config: config) {
            BridgeTesting.shared.openInMemory(
                options: $0, server: serverRelay, clock: clockRelay, realtime: realtime.relay, store: store.relay
            )
        }
    }
}
