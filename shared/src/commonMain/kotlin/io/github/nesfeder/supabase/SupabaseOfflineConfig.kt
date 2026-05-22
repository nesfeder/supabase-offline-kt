package io.github.nesfeder.supabase

import io.github.nesfeder.supabase.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.SupabaseOfflineConfig.Companion.MIN_CACHE_SIZE_BYTES
import okio.Path

/**
 * Public configuration for the Supabase offline client.
 *
 * Mirrors Firebase Firestore's cache size configuration in spirit by
 * allowing callers to control the maximum on-disk size of the offline
 * database file.
 */
data class SupabaseOfflineConfig(
    /**
     * Maximum size in bytes for the offline database file.
     *
     * If set to [CACHE_SIZE_UNLIMITED], no size-based eviction will run.
     *
     * Values lower than [MIN_CACHE_SIZE_BYTES] are coerced to
     * [MIN_CACHE_SIZE_BYTES] to avoid setting a limit below the typical
     * minimum on-disk size of a Room database file.
     */
    val cacheSizeBytes: Long = DEFAULT_CACHE_SIZE_BYTES,

    /**
     * Optional explicit path for where offline data (database and cached
     * files) should be stored.
     *
     * If left as `null`, a platform-specific sensible default is used by
     * the corresponding `createSupabaseOfflineClient` factory. If both the
     * factory parameter and this property are provided, this property wins.
     */
    val offlineStoragePath: Path? = null,

    /**
     * Optional logger for receiving SDK operational events.
     *
     * When `null` (the default), no logging occurs and there is zero overhead.
     * Provide an [OfflineLogger] implementation to bridge SDK logs into your
     * preferred framework (e.g. Timber on Android, os.Logger on iOS).
     */
    val logger: OfflineLogger? = null,

    /**
     * Minimum log level that will be emitted to [logger].
     *
     * Defaults to [OfflineLogLevel.INFO], which is production-safe: only
     * operational events (cache fallbacks, mutation queuing, sync outcomes)
     * are surfaced, with **no** JSON payloads or user data.
     *
     * Set to [OfflineLogLevel.DEBUG] during development to include
     * request/response body previews and detailed operation tracing.
     */
    val minLogLevel: OfflineLogLevel = OfflineLogLevel.INFO,
) {

    companion object {
        /**
         * Unlimited cache: no size-based eviction is performed.
         */
        const val CACHE_SIZE_UNLIMITED: Long = -1L

        /**
         * Default cache size, e.g. 100 MB.
         */
        const val DEFAULT_CACHE_SIZE_BYTES: Long = 100L * 1024L * 1024L // e.g. 100 MB

        /**
         * Minimum allowed cache size in bytes for the underlying Room database.
         *
         * Room databases are typically at least one page (4 KiB) on disk even
         * when nearly empty, so values below this offer no real benefit and are
         * coerced to this minimum.
         */
        const val MIN_CACHE_SIZE_BYTES: Long = 4_096L
    }
}