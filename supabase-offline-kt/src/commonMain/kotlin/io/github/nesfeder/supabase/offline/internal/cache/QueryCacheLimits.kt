// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

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
 * thirty-second of the bound, at least [MIN_PARSED_PAYLOAD_CHARS] and at most
 * [MAX_PARSED_PAYLOAD_CHARS], and the most for a cache with no bound. The parsed rows of a payload
 * take eight to ten times its text in memory, so the text is the proxy, and the ceiling keeps the
 * kept rows under about 40 MB whatever the bound (about 30 MB at the default bound, about 10 MB at
 * the floor).
 */
internal fun parsedPayloadCharsKeptFor(maxPayloadBytes: Long?): Long =
    if (maxPayloadBytes == null) MAX_PARSED_PAYLOAD_CHARS
    else
        (maxPayloadBytes / PARSED_PAYLOAD_SHARE).coerceIn(
            MIN_PARSED_PAYLOAD_CHARS,
            MAX_PARSED_PAYLOAD_CHARS,
        )

/** The share of the cache bound whose payloads are kept parsed: one in this many. */
private const val PARSED_PAYLOAD_SHARE: Long = 32L

/** The least payload text kept parsed, whatever the bound: 1 MiB. */
internal const val MIN_PARSED_PAYLOAD_CHARS: Long = 1024L * 1024L

/** The most payload text kept parsed, whatever the bound: 4 MiB. */
internal const val MAX_PARSED_PAYLOAD_CHARS: Long = 4L * 1024L * 1024L
