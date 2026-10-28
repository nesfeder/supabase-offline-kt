// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.EngineLimits

/**
 * The bounds the store's eviction pass holds the object cache to, and the room its parsed payloads
 * are kept in.
 *
 * @property maxPayloadBytes the bound on the summed length of cached payloads, not on the database
 *   file, which never shrinks after a `DELETE`; `null` for no bound.
 * @property evictionHeadroomBytes how far below the bound a pass drains, so a burst of writes does
 *   not start a pass on every write; a value that is not positive, or not below the bound, drains
 *   to the bound itself.
 * @property parsedPayloadCharsKept the characters of payload text whose parsed rows are kept in
 *   memory, the least recently used going first when more would be kept; by default
 *   [parsedPayloadCharsKeptFor] the bound.
 */
internal data class QueryCacheLimits(
    val maxPayloadBytes: Long?,
    val evictionHeadroomBytes: Long,
    val parsedPayloadCharsKept: Long = parsedPayloadCharsKeptFor(maxPayloadBytes),
)

/**
 * How many characters of payload text a cache bounded at [maxPayloadBytes] keeps parsed: a
 * thirty-second of the bound ([EngineLimits.PARSED_PAYLOAD_SHARE]), at least
 * [EngineLimits.MIN_PARSED_PAYLOAD_CHARS] and at most [EngineLimits.MAX_PARSED_PAYLOAD_CHARS], and
 * the most for a cache with no bound. The parsed rows of a payload take eight to ten times its text
 * in memory, so the text is the proxy, and the ceiling keeps the kept rows under about 40 MB
 * whatever the bound (about 30 MB at the default bound, about 10 MB at the floor).
 */
internal fun parsedPayloadCharsKeptFor(maxPayloadBytes: Long?): Long =
    if (maxPayloadBytes == null) EngineLimits.MAX_PARSED_PAYLOAD_CHARS
    else
        (maxPayloadBytes / EngineLimits.PARSED_PAYLOAD_SHARE).coerceIn(
            EngineLimits.MIN_PARSED_PAYLOAD_CHARS,
            EngineLimits.MAX_PARSED_PAYLOAD_CHARS,
        )
