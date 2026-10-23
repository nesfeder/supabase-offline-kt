// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation

extension SupabaseOfflineClient {

    /// How a client is set up; every setting has a default.
    public struct Config: Sendable {

        /// The PostgREST schema a query without its own reads and writes.
        public var defaultSchema: String

        /// Returns the signed-in user's access token, or `nil` to send the publishable key alone.
        ///
        /// Called per request, off the main actor; with supabase-swift's Auth it is
        /// `{ try? await supabase.auth.session.accessToken }`. A throw fails that request with
        /// ``OfflineError/Code/accessToken``.
        ///
        /// Unless ``identityFromAccessToken`` is on, the client cannot tell one user's cached rows
        /// and queued writes from another's: a sync that starts once this returns another token
        /// sends the last user's queued writes with it, and a live read fetches again right after
        /// ``SupabaseOfflineClient/clearLocalData()`` with whatever token this returns then.
        /// So at sign-out: sync with ``SupabaseOfflineClient/syncPendingMutations(table:)`` while the
        /// user is signed in, if their queued writes should still be sent; turn the network off with
        /// ``SupabaseOfflineClient/disableNetwork()``; sign out, so this returns `nil`, and clear local
        /// data even if the sign-out throws (supabase-swift ends the session on the device before it
        /// tells the server; with an Auth that keeps a session it failed to end, end it yourself
        /// first); and turn the network back on with ``SupabaseOfflineClient/enableNetwork()`` once
        /// this returns the next user's token, which makes open live reads fetch again with it.
        /// Signing out and back in as the same user needs nothing. The channel for ``realtimeTables``
        /// is joined with the token this returns at the join, and, unless
        /// ``identityFromAccessToken`` is on, is not joined again by itself when the token changes:
        /// bracket a sign-in or sign-out with ``SupabaseOfflineClient/disableRealtime()`` and
        /// ``SupabaseOfflineClient/enableRealtime()``.
        public var accessToken: (@Sendable () async throws -> String?)?

        /// Whether the client tells its users apart by the `sub` claim of the token ``accessToken``
        /// returns, read and never verified; `false` by default.
        ///
        /// With `true`, each user's queued writes are sent only with that user's token, the caches
        /// belong to one user at a time and are dropped when the token names another, and the
        /// channel for ``realtimeTables`` is joined again for the new user: the sign-out routine
        /// under ``accessToken`` is no longer needed. `nil` is nobody signed in. A token that names
        /// no user is no identity: a write made with it is sent at once and never queued, nothing
        /// queued is sent, and the cache is not used. The client asks ``accessToken`` before a
        /// write is queued, before the cache is read or written, before each queued write is sent,
        /// and once when it opens, so a new user is seen at its next call. Return `nil` only when
        /// nobody is signed in: when a refresh fails offline, return the session's expired token
        /// (supabase-swift's `currentSession?.accessToken`), which still names its user, or a write
        /// made then is nobody's. While ``accessToken`` throws, the user it last named keeps the
        /// local data. The claim decides only which local data a user is shown, never what the
        /// server allows. Writes queued before this was turned on are nobody's, and are sent only
        /// while nobody is signed in: sync them first.
        public var identityFromAccessToken: Bool

        /// The directory for the local database and cached files, created when missing; `nil` for
        /// `SupabaseOffline` in Application Support.
        public var dataDirectory: URL?

        /// The most cached query results may take on disk; the least recently used go first.
        public var cacheSize: CacheSize

        /// The most cached Storage files may take on disk; the least recently used go first.
        public var fileCacheSize: CacheSize

        /// Makes the app's own connectivity signal, `true` while online, in place of the system's
        /// path monitor, which the client watches when this is `nil`.
        ///
        /// The closure is called once, off the main actor, after the client has opened (never when
        /// the open fails); keep it quick, and return a new stream on each call, since a stream is
        /// read by one client. The client finishes the stream when it closes. Until the stream's
        /// first value the network reads as ``NetworkStatus/unknown``; once the stream ends by
        /// itself, the network counts as reachable again (``NetworkStatus/online``), so a signal that
        /// stops can never hold the client offline.
        ///
        /// A hint, never a gate: while it says offline the client still tries every request the app
        /// makes, and ``SupabaseOfflineClient/disableNetwork()`` is the switch that stops requests. It
        /// decides when the client tries by itself (a reconnection starts a sync pass with
        /// ``autoSync``), what a waiting refresh reports, and ``SyncStatus/network``. While it says
        /// offline the client's own passes still run until one fails for want of a connection; after
        /// that, queued writes wait for the signal to stop saying offline, or for the app's own
        /// ``SupabaseOfflineClient/syncPendingMutations(table:)``.
        public var networkAvailability: (@Sendable () -> AsyncStream<Bool>)?

        /// Whether queued writes are sent by themselves when the network comes back.
        public var autoSync: Bool

        /// Each table's primary-key columns, by table name; a table not named here has the key `id`.
        public var primaryKeys: [String: [String]]

        /// The tables the client keeps current through Realtime; none by default, and then no
        /// channel is opened.
        ///
        /// The client joins one channel for them while the network is not known down and the
        /// subscription is on (``SupabaseOfflineClient/disableRealtime()`` turns it off), applies
        /// each change to the cached queries with the keys ``primaryKeys`` names, reads the tables
        /// again on every join, and refreshes the channel's token before it expires, through
        /// ``accessToken``. ``SupabaseOfflineClient/realtimeStatus`` reports where the channel
        /// stands. A filter whose operator Realtime does not evaluate fails the open with
        /// ``OfflineError/Code/configuration``.
        public var realtimeTables: [RealtimeTable]

        /// Each table's version column, by table name, keyed as ``primaryKeys`` is: a column a
        /// server-side trigger changes on every update of a row, such as `updated_at` kept by
        /// `moddatetime`, or an integer; none by default.
        ///
        /// Naming it guards the replay of a queued update or delete of one of the table's rows: when
        /// the write's filter pins the row's key and the cache holds the row with the column, the
        /// queue records the version the device saw, and a sync pass sends the write only while the
        /// server's row still has it. A row that moved meanwhile is a conflict, settled by
        /// ``conflictResolver``. A write sent at once, an insert, an upsert, and a write the queue
        /// cannot pin to one cached row stay unguarded: the last write wins. A blank column, or one
        /// of the table's key columns, fails the open with ``OfflineError/Code/configuration``.
        public var versionColumns: [String: String]

        /// Settles the conflicts a sync pass finds when it replays a guarded write
        /// (``versionColumns``); `nil`, the default, settles them as ``ConflictResolution`` says.
        ///
        /// It is asked about an update whose row moved with one of the write's columns changed on
        /// the server too, a delete of a row that moved, and an update whose row is gone. It runs in
        /// a task of its own, off the main actor, while the pass waits for its answer, so it should
        /// answer promptly and never wait for a sync itself. A throw, or no answer within 30
        /// seconds, reads as ``ConflictResolution/hold``: the write is parked on
        /// ``SupabaseOfflineClient/conflictedMutations()``, and a late answer is dropped.
        public var conflictResolver: (@Sendable (MutationConflict) async throws -> ConflictResolution)?

        /// The least severe message written to the unified log, under the subsystem `SupabaseOffline`.
        public var logLevel: LogLevel

        /// A configuration with every value given or defaulted, no Realtime table and no version column.
        public init(
            defaultSchema: String = "public",
            accessToken: (@Sendable () async throws -> String?)? = nil,
            dataDirectory: URL? = nil,
            cacheSize: CacheSize = .bytes(100 * 1_024 * 1_024),
            fileCacheSize: CacheSize = .bytes(256 * 1_024 * 1_024),
            networkAvailability: (@Sendable () -> AsyncStream<Bool>)? = nil,
            autoSync: Bool = true,
            primaryKeys: [String: [String]] = [:],
            logLevel: LogLevel = .warning
        ) {
            self.init(
                defaultSchema: defaultSchema,
                accessToken: accessToken,
                dataDirectory: dataDirectory,
                cacheSize: cacheSize,
                fileCacheSize: fileCacheSize,
                networkAvailability: networkAvailability,
                autoSync: autoSync,
                primaryKeys: primaryKeys,
                realtimeTables: [],
                logLevel: logLevel
            )
        }

        /// A configuration with every value given or defaulted, naming the tables the client keeps
        /// current through Realtime.
        public init(
            defaultSchema: String = "public",
            accessToken: (@Sendable () async throws -> String?)? = nil,
            dataDirectory: URL? = nil,
            cacheSize: CacheSize = .bytes(100 * 1_024 * 1_024),
            fileCacheSize: CacheSize = .bytes(256 * 1_024 * 1_024),
            networkAvailability: (@Sendable () -> AsyncStream<Bool>)? = nil,
            autoSync: Bool = true,
            primaryKeys: [String: [String]] = [:],
            realtimeTables: [RealtimeTable],
            logLevel: LogLevel = .warning
        ) {
            self.init(
                defaultSchema: defaultSchema,
                accessToken: accessToken,
                dataDirectory: dataDirectory,
                cacheSize: cacheSize,
                fileCacheSize: fileCacheSize,
                networkAvailability: networkAvailability,
                autoSync: autoSync,
                primaryKeys: primaryKeys,
                realtimeTables: realtimeTables,
                versionColumns: [:],
                conflictResolver: nil,
                logLevel: logLevel
            )
        }

        /// A configuration with every value given or defaulted, naming the tables whose queued
        /// writes replay against a version column.
        public init(
            defaultSchema: String = "public",
            accessToken: (@Sendable () async throws -> String?)? = nil,
            dataDirectory: URL? = nil,
            cacheSize: CacheSize = .bytes(100 * 1_024 * 1_024),
            fileCacheSize: CacheSize = .bytes(256 * 1_024 * 1_024),
            networkAvailability: (@Sendable () -> AsyncStream<Bool>)? = nil,
            autoSync: Bool = true,
            primaryKeys: [String: [String]] = [:],
            realtimeTables: [RealtimeTable] = [],
            versionColumns: [String: String],
            conflictResolver: (@Sendable (MutationConflict) async throws -> ConflictResolution)? = nil,
            logLevel: LogLevel = .warning
        ) {
            self.init(
                defaultSchema: defaultSchema,
                accessToken: accessToken,
                identityFromAccessToken: false,
                dataDirectory: dataDirectory,
                cacheSize: cacheSize,
                fileCacheSize: fileCacheSize,
                networkAvailability: networkAvailability,
                autoSync: autoSync,
                primaryKeys: primaryKeys,
                realtimeTables: realtimeTables,
                versionColumns: versionColumns,
                conflictResolver: conflictResolver,
                logLevel: logLevel
            )
        }

        /// A configuration with every value given or defaulted, saying whether the client tells
        /// its users apart by their access token.
        public init(
            defaultSchema: String = "public",
            accessToken: (@Sendable () async throws -> String?)? = nil,
            identityFromAccessToken: Bool,
            dataDirectory: URL? = nil,
            cacheSize: CacheSize = .bytes(100 * 1_024 * 1_024),
            fileCacheSize: CacheSize = .bytes(256 * 1_024 * 1_024),
            networkAvailability: (@Sendable () -> AsyncStream<Bool>)? = nil,
            autoSync: Bool = true,
            primaryKeys: [String: [String]] = [:],
            realtimeTables: [RealtimeTable] = [],
            versionColumns: [String: String] = [:],
            conflictResolver: (@Sendable (MutationConflict) async throws -> ConflictResolution)? = nil,
            logLevel: LogLevel = .warning
        ) {
            self.defaultSchema = defaultSchema
            self.accessToken = accessToken
            self.identityFromAccessToken = identityFromAccessToken
            self.dataDirectory = dataDirectory
            self.cacheSize = cacheSize
            self.fileCacheSize = fileCacheSize
            self.networkAvailability = networkAvailability
            self.autoSync = autoSync
            self.primaryKeys = primaryKeys
            self.realtimeTables = realtimeTables
            self.versionColumns = versionColumns
            self.conflictResolver = conflictResolver
            self.logLevel = logLevel
        }

        /// `SupabaseOffline` in the app's Application Support directory.
        static var defaultDataDirectory: URL {
            URL.applicationSupportDirectory.appending(path: "SupabaseOffline", directoryHint: .isDirectory)
        }
    }

    /// How much a local cache may hold.
    public enum CacheSize: Sendable, Hashable {

        /// At most this many bytes.
        case bytes(Int)

        /// No limit: nothing is evicted.
        case unlimited
    }

    /// How severe a log message is, from least to most.
    public enum LogLevel: Sendable, Hashable {

        /// Diagnostics; written private, so they are redacted outside a debugger.
        case debug

        /// A state change worth recording.
        case info

        /// Something recovered from, but unexpected.
        case warning

        /// A failure.
        case error

        /// Nothing is written.
        case off
    }
}
