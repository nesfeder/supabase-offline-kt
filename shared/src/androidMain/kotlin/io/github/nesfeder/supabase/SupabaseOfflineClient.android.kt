package io.github.nesfeder.supabase

import android.content.Context
import io.github.nesfeder.supabase.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.SupabaseOfflineConfig.Companion.MIN_CACHE_SIZE_BYTES
import io.github.nesfeder.supabase.internal.db.AndroidDbPathProvider
import io.github.nesfeder.supabase.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.internal.db.getDatabaseBuilder
import io.github.nesfeder.supabase.internal.db.getRoomDatabase
import io.github.nesfeder.supabase.internal.storage.PlatformBaseDirectory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.serialization.serializer

fun createSupabaseOfflineClient(
    context: Context,
    supabase: SupabaseClient,
    config: SupabaseOfflineConfig = SupabaseOfflineConfig(),
): SupabaseOfflineClient {
    val dbPathProvider = AndroidDbPathProvider(context)
    val dbPath = dbPathProvider.dbPath().toString()
    val builder = getDatabaseBuilder(context, dbPath)
    val db = getRoomDatabase(builder)

    // Resolve base directory for cached Storage files, preferring
    // configuration over the explicit parameter, then falling back
    // to the original platform default.
    val baseDirPath = config.offlineStoragePath ?: PlatformBaseDirectory(context).path
    val baseDirProvider = { baseDirPath }

    val maxDbSizeBytes = if (config.cacheSizeBytes == CACHE_SIZE_UNLIMITED) {
        // Unlimited cache: disable size-based eviction.
        null
    } else {
        // Enforce a minimum on-disk DB size so callers cannot configure a
        // limit below the typical minimum Room database file size.
        maxOf(config.cacheSizeBytes, MIN_CACHE_SIZE_BYTES)
    }

    val cacheSettings = OfflineCacheSettings(
        maxDbSizeBytes = maxDbSizeBytes,
        evictionBatchSize = 10,
        minRetainedBytes = 50L * 1024L * 1024L,   // ~50 MB
    )

    return SupabaseOfflineClientImpl(
        supabase = supabase,
        db = db,
        dbPathProvider = dbPathProvider,
        settings = cacheSettings,
        baseDirectoryProvider = baseDirProvider,
        sdkLogger = SdkLogger(config.logger, config.minLogLevel),
    )
}

/** Insert (reified) */
suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insert(
    values: List<T>,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: InsertRequestBuilder.() -> Unit = {}
): PostgrestResult = insertWith(serializer<T>(), values, mutationStrategy, request)

suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insert(
    value: T,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: InsertRequestBuilder.() -> Unit = {}
): PostgrestResult = insertWith(serializer(), value, mutationStrategy, request)

/** Upsert (reified) */
suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsert(
    values: List<T>,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: UpsertRequestBuilder.() -> Unit = {}
): PostgrestResult = upsertWith(serializer<T>(), values, mutationStrategy, request)

suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsert(
    value: T,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: UpsertRequestBuilder.() -> Unit = {}
): PostgrestResult = upsertWith(serializer(), value, mutationStrategy, request)

/** Update (reified) */
suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.update(
    value: T,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    noinline request: PostgrestRequestBuilder.() -> Unit = {}
): PostgrestResult = updateWith(serializer(), value, mutationStrategy, request)