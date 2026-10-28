// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

/**
 * The engine's fixed limits on what its caches and queue keep: each bounds what one structure holds
 * or what one step reads, and none is a setting. The cache's bound itself, `cacheSizeBytes`, is the
 * app's to set; these follow it or stand on their own. The bounds that keep one SQL statement
 * within SQLite's 999 bound variables, or one eviction pass's reads finite, stay beside the queries
 * they guard, in `QueryCache`.
 */
internal object EngineLimits {

    /**
     * The share of the query cache's bound whose payloads are kept parsed: one in this many. See
     * `parsedPayloadCharsKeptFor`.
     */
    const val PARSED_PAYLOAD_SHARE: Long = 32L

    /** The least payload text kept parsed, whatever the bound: 1 MiB. */
    const val MIN_PARSED_PAYLOAD_CHARS: Long = 1024L * 1024L

    /** The most payload text kept parsed, whatever the bound: 4 MiB. */
    const val MAX_PARSED_PAYLOAD_CHARS: Long = 4L * 1024L * 1024L

    /**
     * The most rows a table's key index reads, beyond which a read finds rows held elsewhere by
     * reading every payload instead.
     */
    const val MAX_KEY_INDEXED_ROWS: Int = 100_000

    /**
     * The most synced mutations a table keeps for cached queries fetched before they replayed; the
     * oldest go first. A bound on work and space, read from no clock.
     */
    const val MAX_RETAINED_SYNCED_PER_TABLE: Int = 1_000

    /**
     * How far below the query cache's bound an eviction pass drains, so the next writes do not
     * start another at once: 50 MiB, or to the bound itself when it is 50 MiB or less.
     */
    const val EVICTION_HEADROOM_BYTES: Long = 50L * 1024L * 1024L
}
