package io.github.nesfeder.supabase

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Strategy for reading data from the network or local cache.
 */
sealed interface ReadStrategy {

    /**
     * Only fetch from the network. If the network is unavailable, throw an error.
     */
    data object NetworkOnly : ReadStrategy

    /**
     * Try to fetch from the network first. If the network is unavailable,
     * fall back to the local cache.
     *
     * @param cacheDuration If provided, the cached data is only used if it's
     * newer than this duration. If the cache is expired, the network error is
     * thrown instead.
     */
    data class NetworkFirst(val cacheDuration: Duration? = null) : ReadStrategy {

        constructor(cacheDurationMillis: Long?) : this(cacheDurationMillis?.milliseconds)
    }

    /**
     * Try to fetch from the local cache first. If the data is not in the cache,
     * fetch from the network.
     *
     * @param cacheDuration If provided, the cached data is only used if it's
     * newer than this duration. If the cache is expired, it's treated as a cache
     * miss and the network is used.
     */
    data class CacheFirst(val cacheDuration: Duration? = null) : ReadStrategy {

        constructor(cacheDurationMillis: Long?) : this(cacheDurationMillis?.milliseconds)
    }

    /**
     * Only fetch from the local cache. If the data is not in the cache, throw an error.
     *
     * @param cacheDuration If provided, the cached data is only used if it's
     * newer than this duration. If the cache is expired, it's treated as a cache
     * miss and an error is thrown.
     */
    data class CacheOnly(val cacheDuration: Duration? = null) : ReadStrategy {

        constructor(cacheDurationMillis: Long?) : this(cacheDurationMillis?.milliseconds)
    }
}

/**
 * Strategy for observing data as a Flow in an offline-aware manner.
 *
 * Defines how the Flow is bootstrapped and whether a network request
 * is performed to refresh the local cache.
 */
enum class FlowReadStrategy {

    /**
     * Emit values from the local cache immediately and in parallel
     * trigger a network request.
     *
     * When the network response arrives, the cache is updated and
     * the Flow continues emitting future cache changes.
     *
     * If the network request fails, the Flow continues emitting
     * cached data without error.
     */
    CacheFirstThenNetwork,

    /**
     * Perform a network request first and wait for it to complete.
     *
     * Once the response is stored in the cache, start observing
     * the cache for future changes.
     *
     * If the network request fails, the Flow fails.
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
enum class MutationStrategy {

    /**
     * Only attempt the mutation online. If there is no network, throw an error.
     */
    OnlineOnly,

    /**
     * Add the mutation to the pending queue if the network is unavailable.
     *
     * Overlaying is applied automatically: the UI will immediately reflect
     * the mutation as if it succeeded, even before it is synced.
     */
    QueueIfOffline
}