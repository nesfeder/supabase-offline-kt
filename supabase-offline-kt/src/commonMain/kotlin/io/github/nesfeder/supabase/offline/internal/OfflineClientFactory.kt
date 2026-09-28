package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.MIN_CACHE_SIZE_BYTES
import io.github.nesfeder.supabase.offline.internal.db.FixedDbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * What both platform factories do once they have resolved where the data lives: claim the database
 * file for this client, open it, and build the client over it, with [SupabaseOfflineClient.close]
 * wired to close the database and release the claim.
 *
 * @param supabase the supabase-kt client to wrap.
 * @param config the caller's configuration.
 * @param databasePath the database file.
 * @param dataDirectory the directory the Storage file cache lives under.
 * @param openDatabase opens the platform's Room database at a path.
 * @return the client.
 * @throws IllegalStateException when another open client already uses [databasePath].
 */
internal fun openOfflineClient(
    supabase: SupabaseClient,
    config: SupabaseOfflineConfig,
    databasePath: Path,
    dataDirectory: Path,
    openDatabase: (databasePath: Path) -> OfflineDatabase,
): SupabaseOfflineClient {
    val registration = OpenDatabaseRegistry.register(databasePath)
    try {
        databasePath.parent?.let { directory -> FileSystem.SYSTEM.createDirectories(directory) }
        val database = openDatabase(databasePath)
        val store = OfflineStore(
            objectDao = database.objectDao(),
            storageDao = database.storageDao(),
            mutationDao = database.mutationDao(),
            dbPathProvider = FixedDbPathProvider(databasePath),
            settings = config.toCacheSettings(),
        )
        return SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { dataDirectory },
            sdkLogger = SdkLogger(config.logger, config.minLogLevel),
            networkAvailability = config.networkAvailability,
            fileCacheMaxBytes = config.fileCacheBoundOrNull(),
            prepareFileCacheDirectories = true,
            onClose = {
                database.close()
                registration.release()
            },
        )
    } catch (failure: Throwable) {
        registration.release()
        throw failure
    }
}

/** The object-cache eviction settings [SupabaseOfflineConfig.cacheSizeBytes] asks for. */
internal fun SupabaseOfflineConfig.toCacheSettings(): OfflineCacheSettings = OfflineCacheSettings(
    // Unlimited disables size-based eviction; anything else is held above the minimum Room file.
    maxDbSizeBytes = if (cacheSizeBytes == CACHE_SIZE_UNLIMITED) null else maxOf(cacheSizeBytes, MIN_CACHE_SIZE_BYTES),
    // Evict a small batch of rows per pass; the size reduction per batch depends on payload sizes.
    evictionBatchSize = 10,
    // Keep ~50 MB during a cleanup pass so a burst across the limit doesn't wipe everything.
    minRetainedBytes = 50L * 1024L * 1024L,
)

/** The Storage file cache bound [SupabaseOfflineConfig.fileCacheSizeBytes] asks for, or `null` for none. */
internal fun SupabaseOfflineConfig.fileCacheBoundOrNull(): Long? =
    if (fileCacheSizeBytes == CACHE_SIZE_UNLIMITED) null else fileCacheSizeBytes.coerceAtLeast(0L)
