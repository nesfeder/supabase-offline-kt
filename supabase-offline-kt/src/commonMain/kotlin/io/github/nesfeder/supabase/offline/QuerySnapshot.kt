// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlin.time.Instant

/**
 * One emission of a reactive read ([OfflinePostgrestQueryBuilder.selectAsFlow]): the rows as they
 * stand, where they came from, and how the refresh behind them stands. Each field is true of this
 * emission, so a change to any of them emits, a refresh that fetched the same rows included.
 *
 * @property result the rows, with the user's queued writes applied, or `null` while nothing is
 *   known for the query: nothing cached and no queued write that the query admits. A result whose
 *   body is `[]` means the server returned no rows.
 * @property isFromCache whether the rows are the device's copy rather than an answer this read has
 *   had from the server: `true` until the read's refresh succeeds, for a
 *   [FlowReadStrategy.CacheOnly] read always, and again once the cache marks them stale (to be
 *   fetched again). `false` once the read has fetched them, and while it shows them with later
 *   writes applied.
 * @property fetchedAt when the rows under [QuerySnapshot.result] were fetched from the server, by
 *   the device's clock: the time of the fetch, never of a later local change applied to them. For
 *   rows the library made from queued writes alone, when it made them. `null` while nothing is
 *   cached.
 * @property hasPendingMutations whether a write that waits in the mutation queue changed one of the
 *   rows shown: a row it added, changed or removed.
 * @property refresh how the refresh behind the read stands; see [RefreshState].
 */
@ConsistentCopyVisibility
public data class QuerySnapshot
internal constructor(
    public val result: PostgrestResult?,
    public val isFromCache: Boolean,
    public val fetchedAt: Instant?,
    public val hasPendingMutations: Boolean,
    public val refresh: RefreshState,
)
