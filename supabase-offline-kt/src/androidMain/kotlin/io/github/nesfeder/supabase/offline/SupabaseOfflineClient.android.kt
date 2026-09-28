package io.github.nesfeder.supabase.offline

import android.content.Context
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.MIN_CACHE_SIZE_BYTES
import io.github.nesfeder.supabase.offline.internal.db.AndroidDbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.getDatabaseBuilder
import io.github.nesfeder.supabase.offline.internal.db.getRoomDatabase
import io.github.nesfeder.supabase.offline.internal.storage.PlatformBaseDirectory
import io.github.jan.supabase.SupabaseClient

/**
 * Creates the offline client for Android over [supabase], with its database in the app's database
 * directory (`supabase_offline.db`) and downloaded files under `<filesDir>/supabase_offline` unless
 * [SupabaseOfflineConfig.offlineStoragePath] says otherwise.
 *
 * @param context Any context; its application context is used.
 * @param supabase The supabase-kt client to wrap. Install PostgREST, and Storage if you use it.
 * @param config Cache size, storage path, logging and connectivity hint.
 * @return The offline client.
 */
public fun createSupabaseOfflineClient(
    context: Context,
    supabase: SupabaseClient,
    config: SupabaseOfflineConfig = SupabaseOfflineConfig(),
): SupabaseOfflineClient {
    val dbPathProvider = AndroidDbPathProvider(context)
    val dbPath = dbPathProvider.dbPath().toString()
    val builder = getDatabaseBuilder(context, dbPath)
    val db = getRoomDatabase(builder)

    val baseDirPath = config.offlineStoragePath ?: PlatformBaseDirectory(context).path
    val baseDirProvider = { baseDirPath }

    val maxDbSizeBytes = if (config.cacheSizeBytes == CACHE_SIZE_UNLIMITED) {
        null
    } else {
        maxOf(config.cacheSizeBytes, MIN_CACHE_SIZE_BYTES)
    }

    val cacheSettings = OfflineCacheSettings(
        maxDbSizeBytes = maxDbSizeBytes,
        evictionBatchSize = 10,
        minRetainedBytes = 50L * 1024L * 1024L,
    )

    return SupabaseOfflineClientImpl(
        supabase = supabase,
        db = db,
        dbPathProvider = dbPathProvider,
        settings = cacheSettings,
        baseDirectoryProvider = baseDirProvider,
        sdkLogger = SdkLogger(config.logger, config.minLogLevel),
        networkAvailability = config.networkAvailability,
    )
}
