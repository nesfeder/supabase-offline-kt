// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.fetchedAtOf
import io.github.nesfeder.supabase.offline.internal.db.isStaleStamp
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/**
 * One live reader's overlay of a query, for [QueryOverlay.servedPayloadFlow]: the view it last
 * read, and the overlay it built for it, built again only when the view changed.
 *
 * @param readTogether reads the table's queued mutations and the query's payload as one step, under
 *   the table's snapshot lock.
 * @param overlaid the overlay of a view: the payload the current identity is served, `null` for
 *   none, with those of its mutations the payload does not hold; given the mutations' parsed bodies
 *   and the tick read before the view was.
 */
internal class LiveOverlay(
    private val freshness: CacheFreshness,
    private val ownership: CacheOwnership,
    private val mutationQueue: MutationQueue,
    private val identityProvider: suspend () -> String?,
    private val readTogether: suspend () -> TableSnapshot,
    private val overlaid:
        suspend (
            inputs: OverlaidInputs,
            bodyOf: (MutationRow) -> JsonElement,
            readTick: Long,
        ) -> ServedOverlay?,
) {
    private var last: OverlaidInputs? = null
    private var lastOverlaid: ServedOverlay? = null

    /** Reads the view now, in the order [QueryOverlay.servedPayloadFlow] states, and serves it. */
    suspend fun <T> read(alongside: StateFlow<T>): Pair<ServedPayload?, T> {
        val value = alongside.value
        // Read before the snapshot: a clear landing after it voids the shape record written
        val readTick = freshness.tickNow()
        val identity = identityProvider()
        val served = ownership.cacheServesCurrentIdentity()
        // Takes the table's snapshot lock for this read alone, none other held. Lock order:
        // send lock, snapshot lock, table write lock, queue lock, clear lock
        val snapshot = readTogether()
        val servedRow = snapshot.row?.takeIf { served }?.let(::StoredRow)
        val inputs =
            OverlaidInputs(
                servedRow?.content,
                mutationQueue.visibleTo(identity, snapshot.mutations.rows),
                snapshot.unconfirmedSends,
            )
        if (inputs != last) {
            lastOverlaid = overlaid(inputs, snapshot.mutations::bodyOf, readTick)
            last = inputs
        }
        val payload = lastOverlaid?.let { overlay ->
            ServedPayload(
                payload = overlay.payload,
                fetchedAtMillis = servedRow?.let { stored -> fetchedAtOf(stored.updatedAt) },
                isStale = servedRow == null || isStaleStamp(servedRow.updatedAt),
                hasPendingMutations = overlay.hasPendingMutations,
                pendingRowKeys = overlay.pendingRowKeys,
            )
        }
        return payload to value
    }
}

/** A cached payload as stored: its [content] and its fetch stamp. */
internal data class StoredRow(val content: StoredPayload, val updatedAt: Long) {
    constructor(row: CachedQueryRow) : this(StoredPayload(row), row.updatedAt)
}

/** A cached payload's text and what it holds of the synced mutations. */
internal data class StoredPayload(val payload: String, val syncedThrough: Long) {
    constructor(row: CachedQueryRow) : this(row.payload, row.syncedThrough)
}

/**
 * What a reader's overlay is a function of: the payload it is served, `null` for none, the
 * mutations of its table visible to the identity, in order, and the sends that may have reached the
 * server.
 */
internal data class OverlaidInputs(
    val stored: StoredPayload?,
    val mutations: List<MutationRow>,
    val unconfirmedSends: Set<Long>,
)
