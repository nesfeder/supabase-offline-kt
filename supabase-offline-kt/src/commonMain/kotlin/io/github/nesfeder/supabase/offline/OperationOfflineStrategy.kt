package io.github.nesfeder.supabase.offline

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Strategy for reading data from the network or local cache.
 */
public sealed interface ReadStrategy {

    /**
     * Only fetch from the network. If the network is unavailable, throw an error.
     */
    public data object NetworkOnly : ReadStrategy

    /**
     * Try to fetch from the network first. If the network is unavailable,
     * fall back to the local cache.
     *
     * @param cacheDuration If provided, the cached data is only used if it's
     * newer than this duration. If the cache is expired, the network error is
     * thrown instead.
     */
    public data class NetworkFirst(val cacheDuration: Duration? = null) : ReadStrategy {

        public constructor(cacheDurationMillis: Long?) : this(cacheDurationMillis?.milliseconds)
    }

    /**
     * Try to fetch from the local cache first. If the data is not in the cache,
     * fetch from the network.
     *
     * @param cacheDuration If provided, the cached data is only used if it's
     * newer than this duration. If the cache is expired, it's treated as a cache
     * miss and the network is used.
     */
    public data class CacheFirst(val cacheDuration: Duration? = null) : ReadStrategy {

        public constructor(cacheDurationMillis: Long?) : this(cacheDurationMillis?.milliseconds)
    }

    /**
     * Only fetch from the local cache. If the data is not in the cache, throw an error.
     *
     * @param cacheDuration If provided, the cached data is only used if it's
     * newer than this duration. If the cache is expired, it's treated as a cache
     * miss and an error is thrown.
     */
    public data class CacheOnly(val cacheDuration: Duration? = null) : ReadStrategy {

        public constructor(cacheDurationMillis: Long?) : this(cacheDurationMillis?.milliseconds)
    }
}

/**
 * Strategy for observing data as a Flow in an offline-aware manner.
 *
 * Defines how the Flow is bootstrapped and whether a network request
 * is performed to refresh the local cache.
 */
public enum class FlowReadStrategy {

    /**
     * Emit values from the local cache immediately and in parallel
     * trigger a network request.
     *
     * When the network response arrives, the cache is updated and
     * the Flow continues emitting future cache changes.
     *
     * If the network request fails, the Flow continues emitting cached data without error. The
     * refresh is **self-healing**: it retries for the life of the subscription (a fast ladder, then
     * capped exponential backoff up to 60s) instead of giving up, and wakes early when an internal
     * recovery accelerator fires (auth session restored, or the optional
     * [SupabaseOfflineConfig.networkAvailability] connectivity hint). Per-query refresh status is
     * observable via `selectAsFlow`'s [RefreshState].
     */
    CacheFirstThenNetwork,

    /**
     * Perform a network request first and wait for it to complete.
     *
     * Once the response is stored in the cache, start observing
     * the cache for future changes.
     *
     * If the network request fails, the Flow fails.
     *
     * The bootstrap request is the only refresh this strategy performs — there is no self-healing
     * loop behind it — so it waits out the auth session-restore window before accepting an empty
     * response, on the same bounded ladder the one-shot reads use (roughly 3.6s worst case). That
     * keeps a cold-start subscription from bootstrapping off an RLS-filtered `[]` and then
     * observing an empty cache indefinitely. Once the ladder is spent the empty is accepted; a
     * genuinely-empty table therefore delays this strategy's first emission when the subscription
     * starts mid-restore.
     */
    NetworkFirstThenCache,

    /**
     * Only observe the local cache.
     *
     * No network request is performed. The Flow emits only
     * cached data and future cache updates.
     */
    CacheOnly
}

/**
 * Strategy for executing mutation operations (insert, update, delete) in an offline-aware manner.
 */
public enum class MutationStrategy {

    /**
     * Only attempt the mutation online. If there is no network, throw an error.
     *
     * The write never touches the pending queue, so it can reach the server before writes still
     * queued from earlier — to the same rows, or to a parent row it depends on. Use
     * [QueueIfOffline] for writes that must keep their order.
     */
    OnlineOnly,

    /**
     * Send the mutation through the pending queue, so it keeps its place among the signed-in
     * identity's writes, and keep it there while the network is unavailable.
     *
     * The write is persisted first, then sent at once when nothing of the same identity is pending
     * ahead of it, in any table. It reports [MutationOutcome.Applied], carrying the server response,
     * when the server executed it. It reports [MutationOutcome.Queued] when it waits in the queue:
     * because the network is unavailable, or because earlier writes are still pending — then it goes
     * out after them, and a refusal from the server lands on the engine's rejected list instead of
     * being thrown. Callers that read the written row back branch on [MutationOutcome.Applied] vs
     * [MutationOutcome.Queued] so they can construct the domain object from the DTO they sent
     * instead of decoding a placeholder.
     *
     * A write sent at the head that the server refuses, or answers with a `5xx`, is thrown and does
     * not stay queued.
     *
     * Overlaying is applied automatically: the UI will immediately reflect
     * the mutation as if it succeeded, even before it is synced.
     *
     * A few writes cannot be queued faithfully and are sent directly, as with [OnlineOnly]: a
     * `dryRun()` write, an UPDATE or DELETE whose filter the queue cannot record exactly, and a write
     * made while the session's identity cannot be told (restoring or refreshing).
     */
    QueueIfOffline
}