// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

/**
 * A payload with the current identity's mutations overlaid, as a read serves it.
 *
 * @property payload the rows, encoded.
 * @property hasPendingMutations whether a mutation not yet sent changed one of them.
 */
internal data class ServedOverlay(val payload: String, val hasPendingMutations: Boolean)

/**
 * A cached query's payload as a live reader is served it, with what the reader reports of it.
 *
 * @property payload the rows with the current identity's mutations overlaid, encoded.
 * @property fetchedAtMillis when the cached payload under them was fetched, in epoch milliseconds;
 *   `null` when nothing is cached and the rows are the overlay alone.
 * @property isStale whether the cached payload is stale (to be fetched again), or absent.
 * @property hasPendingMutations whether a mutation not yet sent changed one of the rows.
 */
internal data class ServedPayload(
    val payload: String,
    val fetchedAtMillis: Long?,
    val isStale: Boolean,
    val hasPendingMutations: Boolean,
)
