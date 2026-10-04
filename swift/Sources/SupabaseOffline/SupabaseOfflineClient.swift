// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
public import Foundation
package import SupabaseOfflineCore

/// Offline-first access to one Supabase project's tables and files.
///
/// Create one for the app and share it: it is `Sendable`, and every call is safe from any thread
/// or actor. It opens its local database in the background as it is created; a call made before
/// that finishes waits for it, and a client that could not open throws ``OfflineError/Code/configuration``
/// from every call. ``close()`` it when the app is done with it, before opening another client over
/// the same data directory.
public final class SupabaseOfflineClient: Sendable {

    /// The core client, once open; every operation goes through it.
    let connection: CoreConnection

    /// A client for the project at `supabaseURL`, with its publishable (anon) key.
    ///
    /// - Parameters:
    ///   - supabaseURL: The project's URL, such as `https://xyzcompany.supabase.co`.
    ///   - supabaseKey: The project's publishable key. A signed-in user's token comes from
    ///     ``Options/accessToken``.
    ///   - options: Where the local data lives, how large it may grow, and how the client behaves.
    public convenience init(supabaseURL: URL, supabaseKey: String, options: Options = Options()) {
        self.init(supabaseURL: supabaseURL, supabaseKey: supabaseKey, options: options) {
            BridgeClient.companion.open(options: $0)
        }
    }

    /// A client whose core `opening` opens from the options; the testing product's entry point.
    package init(
        supabaseURL: URL,
        supabaseKey: String,
        options: Options,
        opening: @escaping @Sendable (BridgeClientOptions) -> BridgeOpenResult
    ) {
        connection = CoreConnection {
            let coreOptions = try SupabaseOfflineClient.coreOptions(supabaseURL: supabaseURL, supabaseKey: supabaseKey, options: options)
            let opened = opening(coreOptions)
            guard let client = opened.client else {
                let reason = opened.error.map { redactingRuntimeNames($0.message) } ?? "no reason given"
                packageLogger.error("The client could not be opened: \(reason, privacy: .public)")
                throw OfflineError(code: .configuration, message: "The client could not be opened: \(reason)")
            }
            return CoreClientReference(client: client)
        }
    }

    deinit {
        connection.closeInBackground()
    }

    /// Closes the client: calls already made are sent first, then calls still running end with
    /// ``OfflineError/Code/clientClosed``, as does every later call, and live sequences finish.
    ///
    /// The client is closed even when this throws; the error says which step failed. Closing twice
    /// does nothing more.
    public func close() async throws {
        try await connection.close()
    }

    /// The core's options for `options`, with the default data directory filled in.
    static func coreOptions(
        supabaseURL: URL,
        supabaseKey: String,
        options: Options
    ) throws -> BridgeClientOptions {
        let dataDirectory = options.dataDirectory ?? Options.defaultDataDirectory
        guard dataDirectory.isFileURL else {
            throw OfflineError(code: .configuration, message: "The data directory must be a file URL")
        }
        let coreOptions = BridgeClientOptions(
            supabaseUrl: supabaseURL.absoluteString,
            supabaseKey: supabaseKey,
            dataDirectory: dataDirectory.path(percentEncoded: false)
        )
        coreOptions.schema = options.schema
        coreOptions.accessTokenProvider = options.accessToken.map(AccessTokenRelay.init)
        coreOptions.cacheSizeBytes = options.cacheLimit.coreBytes
        coreOptions.fileCacheSizeBytes = options.fileCacheLimit.coreBytes
        coreOptions.autoSync = options.syncsAutomatically
        coreOptions.primaryKeys = options.primaryKeys
        coreOptions.logSink = SystemLogRelay()
        coreOptions.logLevel = options.logLevel.coreLevel
        return coreOptions
    }
}

extension SupabaseOfflineClient.CacheLimit {

    /// The limit as the core reads it: a byte count, or its value for no limit.
    var coreBytes: Int64 {
        switch self {
        case .bytes(let count): return Int64(count)
        case .unlimited: return BridgeClientOptions.companion.CACHE_SIZE_UNLIMITED
        }
    }
}

extension SupabaseOfflineClient.LogLevel {

    /// The level as the core reads it.
    var coreLevel: BridgeLogLevel {
        switch self {
        case .debug: return .debug
        case .info: return .info
        case .warning: return .warning
        case .error: return .error
        case .off: return .none
        }
    }
}
