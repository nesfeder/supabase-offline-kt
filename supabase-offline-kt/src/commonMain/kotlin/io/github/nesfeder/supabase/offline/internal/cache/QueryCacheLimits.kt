// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

/**
 * The bounds the store's eviction pass holds the object cache to.
 *
 * @property maxPayloadBytes the bound on the summed length of cached payloads, not on the database
 *   file, which never shrinks after a `DELETE`; `null` for no bound.
 * @property evictionBatchSize how many of the oldest rows one pass deletes at a time; the pass
 *   treats anything below one as one.
 * @property evictionHeadroomBytes how far below the bound a pass drains, so a burst of writes does
 *   not start a pass on every write; a value that is not positive, or not below the bound, drains
 *   to the bound itself.
 */
internal data class QueryCacheLimits(
    val maxPayloadBytes: Long?,
    val evictionBatchSize: Int,
    val evictionHeadroomBytes: Long,
)
