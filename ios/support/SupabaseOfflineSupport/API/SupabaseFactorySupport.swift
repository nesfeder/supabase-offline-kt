import SupabaseOffline

/// Bridges a Swift closure into the Kotlin `OfflineLogger` protocol.
private class ClosureOfflineLogger: OfflineLogger {
    private let handler: (OfflineLogLevel, String) -> Void

    init(_ handler: @escaping (OfflineLogLevel, String) -> Void) {
        self.handler = handler
    }

    func log(level: OfflineLogLevel, message: String) {
        handler(level, message)
    }
}

extension SupabaseFactory {

    /// Creates an offline-first Supabase client with the given configuration.
    ///
    /// The client caches reads locally and can queue writes when offline. Sync is manual:
    /// call `syncAllPendingMutations()` (or per-table sync) when the app is back online.
    ///
    /// - Parameters:
    ///   - supabaseUrl: Your Supabase project URL (e.g. `https://xxx.supabase.co`).
    ///   - supabaseAnonKey: The project's anonymous (public) API key.
    ///   - accessTokenProvider: Optional closure that supplies the current auth token for requests. Called when the client needs to add an `Authorization` header.
    ///   - offlineStoragePath: Optional path for the offline database and cache. If `nil`, a default location is used.
    ///   - cacheSizeBytes: Optional maximum size in bytes for the read cache. If `nil`, cache size is unbounded (subject to framework defaults).
    ///   - logger: Optional closure for receiving SDK log messages. Pass `nil` (default) to disable logging.
    ///   - minLogLevel: Minimum log level to emit. Defaults to `.info` (production-safe). Set to `.debug` for full request/response visibility during development.
    /// - Returns: A configured `SupabaseOfflineClient` instance for PostgREST and Storage operations.
    public static func offlineClient(
        supabaseUrl: String,
        supabaseAnonKey: String,
        accessTokenProvider: (() async -> String?)? = nil,
        offlineStoragePath: OkioPath? = nil,
        cacheSizeBytes: Int64? = nil,
        logger: ((OfflineLogLevel, String) -> Void)? = nil,
        minLogLevel: OfflineLogLevel = .info
    ) -> SupabaseOfflineClient {
        return SupabaseFactory.shared.offlineClientKt(
            supabaseUrl: supabaseUrl,
            supabaseAnonKey: supabaseAnonKey,
            accessTokenProvider: accessTokenProvider.map { AsyncToKotlinSuspendString(block: $0) },
            offlineStoragePath: offlineStoragePath,
            cacheSizeBytes: cacheSizeBytes.map(KotlinLong.init(value:)),
            logger: logger.map { ClosureOfflineLogger($0) },
            minLogLevel: minLogLevel
        )
    }
}
