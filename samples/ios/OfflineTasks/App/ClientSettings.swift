// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import Network
import SupabaseOffline

/// The offline client's settings, every one written out. Each is the library's default but the
/// access token, the network signal, the Realtime tables, the version columns with their resolver
/// and a debug build's log level, so removing any other leaves the app as it is.
nonisolated enum ClientSettings {

    // --8<-- [start:sizes]
    /// The most cached query results may take on disk before the ones fetched longest ago are
    /// evicted: 100 MiB, the default. Rows a live read shows are never evicted.
    static let queryCacheSize = SupabaseOfflineClient.CacheSize.bytes(100 * 1_024 * 1_024)

    /// The most downloaded photos may take on disk before the least recently used are deleted:
    /// 256 MiB, the default. Photos are the large part; `.unlimited` would keep every one.
    static let photoCacheSize = SupabaseOfflineClient.CacheSize.bytes(256 * 1_024 * 1_024)
    // --8<-- [end:sizes]

    // --8<-- [start:data-directory]
    /// Where the database and the photo cache live: `SupabaseOffline` in Application Support, the
    /// default. The library leaves both out of the device's backups.
    static let dataDirectory = URL.applicationSupportDirectory.appending(path: "SupabaseOffline", directoryHint: .isDirectory)
    // --8<-- [end:data-directory]

    // --8<-- [start:primary-keys]
    /// How the cache tells rows apart: each table's key columns. `id`, the default for a table not
    /// named, for both.
    static let primaryKeys = [Tables.tasks: ["id"], Tables.taskPhotos: ["id"]]
    // --8<-- [end:primary-keys]

    // --8<-- [start:conflict-resolver]
    /// Holds a conflict on a task's title for the user to settle on the Sync tab, and settles every
    /// other one as the library would without a resolver: this device's change wins a row that
    /// moved, and an edit of a row deleted elsewhere is dropped.
    static let holdTitleConflicts: @Sendable (MutationConflict) async throws -> ConflictResolution = { conflict in
        if conflict.conflictingColumns.contains("title") { return .hold }  // both sides changed it
        return conflict.server == nil ? .keepServer : .keepLocal
    }
    // --8<-- [end:conflict-resolver]

    // --8<-- [start:log-level]
    /// What the library writes to the unified log, under the subsystem `SupabaseOffline`: from
    /// `.info` in a debug build, to follow each sync; from `.warning`, the default, otherwise.
    static var logLevel: SupabaseOfflineClient.LogLevel {
        #if DEBUG
            .info
        #else
            .warning
        #endif
    }
    // --8<-- [end:log-level]

    // --8<-- [start:own-signal]
    /// The device's connectivity from Network's path monitor, `true` while a path is usable: the
    /// app's own signal, which the client follows in place of its built-in monitor. Each call
    /// makes a new monitor, which stops when the client finishes the stream.
    static func pathMonitorSignal() -> AsyncStream<Bool> {
        AsyncStream { continuation in
            let monitor = NWPathMonitor()
            monitor.pathUpdateHandler = { path in continuation.yield(path.status == .satisfied) }
            continuation.onTermination = { _ in monitor.cancel() }
            monitor.start(queue: DispatchQueue(label: "OfflineTasks.network-signal"))
        }
    }
    // --8<-- [end:own-signal]

    // --8<-- [start:every-setting]
    /// The settings for a client whose user's token `accessToken` returns, following
    /// `networkAvailability`.
    static func config(
        accessToken: @escaping @Sendable () async -> String?,
        networkAvailability: @escaping @Sendable () -> AsyncStream<Bool>
    ) -> SupabaseOfflineClient.Config {
        SupabaseOfflineClient.Config(
            // The schema a table is read from and written to: public, the default.
            defaultSchema: "public",
            // The signed-in user's token, asked for before each request; nil (the default) sends the publishable key alone.
            accessToken: { await accessToken() },
            // Where the database and the photo cache live: the default, spelled out.
            dataDirectory: dataDirectory,
            // How much cached query results may take: the default.
            cacheSize: queryCacheSize,
            // How much downloaded photos may take: the default.
            fileCacheSize: photoCacheSize,
            // The app's own connectivity signal, in place of the library's built-in path monitor (nil, the default).
            networkAvailability: networkAvailability,
            // Queued writes are sent by themselves when the network comes back: true, the default.
            autoSync: true,
            // The columns that tell one row from another, per table: id, the default for a table not named.
            primaryKeys: primaryKeys,
            // --8<-- [start:realtime-tables]
            // The tables the client keeps current through Realtime (Live updates): one channel, each
            // change applied to the cached lists, both tables read again on every join. None, the
            // default, opens no channel.
            realtimeTables: [RealtimeTable(Tables.tasks), RealtimeTable(Tables.taskPhotos)],
            // --8<-- [end:realtime-tables]
            // --8<-- [start:version-column]
            // The column the server moves on every change (moddatetime): a queued update or delete
            // of a row is sent only while the server's row still has the version this device saw,
            // so a change made elsewhere meanwhile is found, not overwritten. None is the default:
            // the last write wins.
            versionColumns: [Tables.tasks: "updated_at", Tables.taskPhotos: "updated_at"],
            // Settles what it finds; nil, the default, keeps this device's change of a row that moved.
            conflictResolver: holdTitleConflicts,
            // --8<-- [end:version-column]
            // What the library logs: more in a debug build than .warning, the default.
            logLevel: logLevel
        )
    }
    // --8<-- [end:every-setting]
}
