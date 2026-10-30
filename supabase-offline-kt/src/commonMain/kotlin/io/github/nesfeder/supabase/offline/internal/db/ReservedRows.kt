// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

/**
 * The `object_cache` table name under which the engine keeps its own small records — the
 * rejected-mutation and conflicted-mutation lists, each cached query's shape, each pinned Storage
 * object, whose the cache is and when the current user's queue was last emptied — beside the cache.
 *
 * It cannot collide with a table reachable through PostgREST: Postgres truncates identifiers to
 * `NAMEDATALEN - 1` = 63 bytes, so no table can be *named* anything longer, and this name is far
 * longer. A `from(table)` call only ever produces cache rows under the name it was given, and
 * `OfflineClient.from` refuses this one.
 *
 * Rows under this name are never evicted, never counted toward the cache size, never returned by a
 * read and never touched by an overlay; `clearLocalData` removes them with everything else, and a
 * drop of the cache for a new owner removes the query shapes, the pinned objects and the
 * last-synced record of the identity before it, and keeps the rest.
 *
 * Changing this value orphans every installed device's records, so it never changes.
 */
internal const val RESERVED_TABLE_NAME: String =
    "__supabase_offline_kt__.reserved_engine_state.this_name_is_longer_than_any_postgres_identifier"

/** Signature, within [RESERVED_TABLE_NAME], of the persisted rejected-mutation list. */
internal const val REJECTED_MUTATIONS_CACHE_KEY: String = "rejected_mutations"

/** Signature, within [RESERVED_TABLE_NAME], of the persisted conflicted-mutation list. */
internal const val CONFLICTED_MUTATIONS_CACHE_KEY: String = "conflicted_mutations"

/**
 * Signature, within [RESERVED_TABLE_NAME], of the identity the query cache and the Storage file
 * cache belong to.
 */
internal const val CACHE_OWNER_CACHE_KEY: String = "cache_owner"

/**
 * Signature, within [RESERVED_TABLE_NAME], of when the current user's queue was last emptied by
 * sending, with whose queue it was: `SyncStatus.lastSyncedAt` across clients.
 */
internal const val LAST_SYNCED_AT_CACHE_KEY: String = "last_synced_at"

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
 * The name that begins the signature of each pinned Storage object's record within
 * [RESERVED_TABLE_NAME]: the name, a unit separator (U+001F), then the object's cached-file key,
 * `<bucket>::<path>`.
 */
internal const val PINNED_FILE_CACHE_KEY_PREFIX_NAME: String = "pinned_file"

/**
 * [PINNED_FILE_CACHE_KEY_PREFIX_NAME] and the separator after it, as a record's signature begins.
 */
internal const val PINNED_FILE_CACHE_KEY_PREFIX: String =
    PINNED_FILE_CACHE_KEY_PREFIX_NAME + "\u001F"

/**
 * The `mutation_cache` table name of every queued Storage upload's row: a write of the one queue,
 * ordered with the row writes around it, that targets no table of the app's. It cannot collide with
 * a table reachable through PostgREST for the reason [RESERVED_TABLE_NAME] cannot, and
 * `OfflineClient.from` refuses it, so no table's reads, overlay, counts or single-table pass meet
 * an upload.
 *
 * Changing this value orphans every installed device's queued uploads, so it never changes.
 */
internal const val STORAGE_UPLOADS_TABLE_NAME: String =
    "__supabase_offline_kt__.storage_uploads.this_name_is_longer_than_any_postgres_identifier"

/**
 * Whether [table] is one of the engine's own table names rather than a table of the app's: the
 * reserved state table, or the one queued uploads are filed under.
 *
 * @param table A table name as a caller passed it.
 * @return `true` only for [RESERVED_TABLE_NAME] and [STORAGE_UPLOADS_TABLE_NAME].
 */
internal fun isReservedTable(table: String): Boolean =
    table == RESERVED_TABLE_NAME || table == STORAGE_UPLOADS_TABLE_NAME

/**
 * Refuses the engine's reserved state table as the target of a cache write or a queued write, so no
 * user-facing path can touch the reserved rows.
 *
 * @return [table], when it is an app table.
 */
internal fun requireUserTable(table: String): String {
    require(!isReservedTable(table)) { "The table name is reserved by the offline engine" }
    return table
}
