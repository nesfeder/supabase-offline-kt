// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The column each table keeps its row version in, as the configuration names it. A queued UPDATE or
 * DELETE of one row of such a table replays only while the server's row still has the version the
 * device saw.
 */
internal fun interface VersionColumns {

    /**
     * The version column of [table].
     *
     * @param table A table name.
     * @return The column, or `null` when the table has none and its writes replay unguarded.
     */
    fun of(table: String): String?
}

/** [VersionColumns] that names none: every write replays unguarded. */
internal val NoVersionColumns: VersionColumns = VersionColumns { null }

/**
 * A version value as the queue stores it: a string's content or a number's text, sent back
 * verbatim. `null` for JSON `null`, or anything that is not a primitive.
 */
internal fun versionTextOf(value: JsonElement?): String? =
    (value as? JsonPrimitive)?.takeUnless { primitive -> primitive is JsonNull }?.content
