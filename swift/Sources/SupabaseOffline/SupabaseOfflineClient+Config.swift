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
        public var accessToken: (@Sendable () async throws -> String?)?

        /// The directory for the local database and cached files, created when missing; `nil` for
        /// `SupabaseOffline` in Application Support.
        public var dataDirectory: URL?

        /// The most cached query results may take on disk; the least recently used go first.
        public var cacheSize: CacheSize

        /// The most cached Storage files may take on disk; the least recently used go first.
        public var fileCacheSize: CacheSize

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
            autoSync: Bool = true,
            primaryKeys: [String: [String]] = [:],
            logLevel: LogLevel = .warning
        ) {
            self.defaultSchema = defaultSchema
            self.accessToken = accessToken
            self.dataDirectory = dataDirectory
            self.cacheSize = cacheSize
            self.fileCacheSize = fileCacheSize
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
