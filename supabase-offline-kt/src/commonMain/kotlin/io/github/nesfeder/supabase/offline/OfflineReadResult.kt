// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.result.PostgrestResult

/**
 * One emission of a reactive read ([OfflinePostgrestQueryBuilder.selectAsFlow]): the rows as they
 * stand, and how the refresh behind them stands.
 *
 * @property result the rows, with the user's queued writes applied, or `null` while nothing is
 *   known for the query: nothing cached and no queued write that the query admits. A result whose
 *   body is `[]` means the server returned no rows.
 * @property refresh how the refresh behind the read stands; see [RefreshState].
 */
public data class OfflineReadResult(
    val result: PostgrestResult?,
    val refresh: RefreshState,
)
