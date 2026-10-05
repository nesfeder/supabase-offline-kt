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
        /// The client learns who the user is only from the token this returns, and cannot tell one
        /// user's cached rows and queued writes from another's: a sync that starts once this returns
        /// another token sends the last user's queued writes with it, and a live read fetches again
        /// right after ``SupabaseOfflineClient/clearLocalData()`` with whatever token this returns then.
        /// So at sign-out: sync with ``SupabaseOfflineClient/syncPendingMutations(table:)`` while the
        /// user is signed in, if their queued writes should still be sent; turn the network off with
        /// ``SupabaseOfflineClient/disableNetwork()``; sign out, so this returns `nil`, and clear local
        /// data even if the sign-out throws (supabase-swift ends the session on the device before it
        /// tells the server; with an Auth that keeps a session it failed to end, end it yourself
        /// first); and turn the network back on with ``SupabaseOfflineClient/enableNetwork()`` once
        /// this returns the next user's token, which makes open live reads fetch again with it.
        /// Signing out and back in as the same user needs nothing.
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
