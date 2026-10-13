// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One write waiting in the mutation queue to be sent.
 *
 * @property id the write's id in the queue. It survives a restart, and a write the server later
 *   refuses keeps it, so it identifies the same write wherever it turns up.
 * @property table the table the write targets.
 * @property operation what the write does.
 * @property createdAt when the write was queued, by the device's clock. Writes are sent in the
 *   order they were made, which is the order of [PendingMutation.id], not of this.
 * @property rowKeys the primary-key values of the rows the write touches, as far as the library can
 *   tell: one object per row of the body that carries the whole key, or the key's equality
 *   conditions in the filter. Empty when it cannot tell. The keys come from the table's primary key
 *   as [Offline.Config.primaryKeys] gives it.
 * @property body the body the write sends, or `null` for a DELETE or a stored body that can no
 *   longer be read.
 */
@ConsistentCopyVisibility
public data class PendingMutation
internal constructor(
    public val id: Long,
    public val table: String,
    public val operation: MutationOperation,
    public val createdAt: Instant,
    public val rowKeys: List<JsonObject>,
    public val body: JsonElement?,
)
