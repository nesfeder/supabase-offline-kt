// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

/**
 * The app's pending mutations and rejected entries, counted together; see
 * [OfflineStore.shownQueueCounts].
 *
 * @property pending the pending mutations the app is shown.
 * @property rejected the rejected entries the app is shown.
 */
internal data class ShownQueueCounts(val pending: Int, val rejected: Int)
