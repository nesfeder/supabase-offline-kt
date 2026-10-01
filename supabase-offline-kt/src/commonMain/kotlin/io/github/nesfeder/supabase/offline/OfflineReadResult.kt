// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.result.PostgrestResult

/**
 * A single emission of the in-band read+refresh state for a `selectAsFlow` subscription.
 *
 * Consumers that want a Loading state until data is known observe [result] directly (a `null`
 * result is the typed "no data yet" state); consumers that want today's instant-empty-state
 * rendering map the no-data case to `emptyList()` in their repository. [refresh] is available for a
 * future UI affordance ("refresh failed, showing stale/empty cache") — consumers may ignore it.
 *
 * @property result The cached/network payload, or `null` when no data is available yet for this
 *   signature (no cached payload and no client-side mutation overlay). A `null` result is the typed
 *   "no data yet" state that replaces the old fabricated empty-array cache seed; a present result
 *   whose body is `[]` means the server genuinely returned no rows.
 * @property refresh The current [RefreshState] of this subscription's background refresh.
 */
public data class OfflineReadResult(
    val result: PostgrestResult?,
    val refresh: RefreshState,
)
