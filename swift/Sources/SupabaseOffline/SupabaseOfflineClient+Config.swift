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
        /// Signing in and out needs no call: the next request carries whatever this returns. The
        /// client keeps one identity, though, so its cache and its queued writes are not kept apart
        /// by user: when a different user signs in, call ``SupabaseOfflineClient/clearLocalData()``
        /// first, or the new user sees the last one's cached rows and their queued writes are sent
        /// with the new token.
        public var accessToken: (@Sendable () async throws -> String?)?

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
        /// The closure is called once when the client opens. The stream it returns is that client's
        /// alone, and is finished when the client closes; when it ends by itself, its last value
        /// stays in force. Until its first value the network reads as ``NetworkStatus/unknown``.
        ///
        /// A hint, never a gate: while it says offline the client still tries every request the app
        /// makes, and ``SupabaseOfflineClient/disableNetwork()`` is the switch that stops requests. It
        /// decides when the client tries by itself (a reconnection starts a sync pass with
        /// ``autoSync``), what a waiting refresh reports, and ``SyncStatus/network``.
        public var networkAvailability: (@Sendable () -> AsyncStream<Bool>)?

        /// Whether queued writes are sent by themselves when the network comes back.
        public var autoSync: Bool

        /// Each table's primary-key columns, by table name; a table not named here has the key `id`.
        public var primaryKeys: [String: [String]]

        /// The least severe message written to the unified log, under the subsystem `SupabaseOffline`.
        public var logLevel: LogLevel

        /// A configuration with every value given or defaulted.
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
            self.defaultSchema = defaultSchema
            self.accessToken = accessToken
            self.dataDirectory = dataDirectory
            self.cacheSize = cacheSize
            self.fileCacheSize = fileCacheSize
            self.networkAvailability = networkAvailability
            self.autoSync = autoSync
            self.primaryKeys = primaryKeys
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
