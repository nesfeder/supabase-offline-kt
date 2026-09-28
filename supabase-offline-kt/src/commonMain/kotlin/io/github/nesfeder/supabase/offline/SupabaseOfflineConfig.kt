package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.MIN_CACHE_SIZE_BYTES
import kotlinx.coroutines.flow.Flow
import okio.Path

/**
 * Public configuration for the Supabase offline client.
 *
 * Mirrors Firebase Firestore's cache size configuration in spirit by allowing callers to
 * bound how much cached data the offline client retains. The bound is applied to the logical
 * size of cached SELECT payloads, not the on-disk size of the Room database file (see
 * [cacheSizeBytes]).
 */
public data class SupabaseOfflineConfig(
    /**
     * Maximum size, in bytes, of the cached SELECT payloads the offline client retains.
     *
     * The limit is measured against the **logical size of cached payloads** — the summed length
     * of every cached PostgREST response — not the on-disk size of the Room database file.
     * SQLite returns pages freed by `DELETE` to its internal freelist rather than to the OS, so
     * the file never shrinks after eviction and can't be used as a termination signal; payload
     * accounting can. Reclaiming those freed on-disk pages (`VACUUM`) is intentionally out of
     * scope — cache correctness doesn't depend on returning bytes to the filesystem.
     *
     * If set to [CACHE_SIZE_UNLIMITED], no size-based eviction will run.
     *
     * Values lower than [MIN_CACHE_SIZE_BYTES] are coerced to [MIN_CACHE_SIZE_BYTES].
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

    /**
     * Optional platform-agnostic connectivity hint (`true` = online).
     *
     * The SDK owns its refresh recovery: a `CacheFirstThenNetwork` refresh self-heals with capped
     * backoff and, additionally, wakes early on the false→true edges of this flow (merged with the
     * SDK's own auth-session-restored edges). It is a **hint only** — leaving it `null` still
     * recovers within the capped schedule (≤60s after conditions improve); wiring it in just makes
     * recovery near-instant on reconnect.
     *
     * It is a plain `Flow<Boolean>` rather than any particular connectivity type: map your own
     * connectivity monitor into it (e.g. `connectivityMonitor.status.map { it == Status.Online }`)
     * so the SDK takes no dependency on a connectivity library.
     */
    val networkAvailability: Flow<Boolean>? = null,
) {

    public companion object {
        /**
         * Unlimited cache: no size-based eviction is performed.
         */
        public const val CACHE_SIZE_UNLIMITED: Long = -1L

        /**
         * Default cache size, e.g. 100 MB.
         */
        public const val DEFAULT_CACHE_SIZE_BYTES: Long = 100L * 1024L * 1024L // e.g. 100 MB

        /**
         * Minimum allowed cache size in bytes for the underlying Room database.
         *
         * Room databases are typically at least one page (4 KiB) on disk even
         * when nearly empty, so values below this offer no real benefit and are
         * coerced to this minimum.
         */
        public const val MIN_CACHE_SIZE_BYTES: Long = 4_096L
    }
}