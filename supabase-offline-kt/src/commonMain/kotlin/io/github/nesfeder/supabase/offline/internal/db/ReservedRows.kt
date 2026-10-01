// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

/**
 * The `object_cache` table name under which the engine keeps its own small records — the
 * rejected-mutation list, each cached query's shape and whose the cache is — beside the cache.
 *
 * It cannot collide with a table reachable through PostgREST: Postgres truncates identifiers to
 * `NAMEDATALEN - 1` = 63 bytes, so no table can be *named* anything longer, and this name is far
 * longer. A `from(table)` call only ever produces cache rows under the name it was given, and
 * `SupabaseOfflineClientImpl.from` refuses this one.
 *
 * Rows under this name are never evicted, never counted towards the cache size, never returned by a
 * read and never touched by an overlay; `clearLocalData` removes them with everything else, and a
 * drop of the cache for a new owner removes the query shapes and keeps the rest.
 *
 * Changing this value orphans every installed device's records, so it never changes.
 */
internal const val RESERVED_TABLE_NAME: String =
    "__supabase_offline_kt__.reserved_engine_state.this_name_is_longer_than_any_postgres_identifier"

/** Signature, within [RESERVED_TABLE_NAME], of the persisted rejected-mutation list. */
internal const val REJECTED_MUTATIONS_CACHE_KEY: String = "rejected_mutations"

/**
 * Signature, within [RESERVED_TABLE_NAME], of the identity the query cache and the Storage file
 * cache belong to.
 */
internal const val CACHE_OWNER_CACHE_KEY: String = "cache_owner"

/**
 * The name that begins the signature of each query-shape record within [RESERVED_TABLE_NAME]: the
 * name, a unit separator (U+001F), the cached query's table, another separator, and its cache key.
 */
internal const val QUERY_SHAPE_CACHE_KEY_PREFIX_NAME: String = "query_shape"

/**
 * [QUERY_SHAPE_CACHE_KEY_PREFIX_NAME] and the separator after it, as a record's signature begins.
 */
internal const val QUERY_SHAPE_CACHE_KEY_PREFIX: String =
    QUERY_SHAPE_CACHE_KEY_PREFIX_NAME + "\u001F"

/** The length of [QUERY_SHAPE_CACHE_KEY_PREFIX]. */
internal const val QUERY_SHAPE_CACHE_KEY_PREFIX_LENGTH: Int = 12

/**
 * Whether [table] is the engine's reserved state table rather than a table of the app's.
 *
 * @param table A table name as a caller passed it.
 * @return `true` only for [RESERVED_TABLE_NAME].
 */
internal fun isReservedTable(table: String): Boolean = table == RESERVED_TABLE_NAME
