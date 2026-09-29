// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

/**
 * The `object_cache` table name under which the engine keeps its own state — the rejected-mutation
 * list, the owner of each queued mutation, which cached payloads already contain each synced
 * mutation, and which INSERTs may already be on the server — without a schema change.
 *
 * It cannot collide with a table reachable through PostgREST: Postgres truncates identifiers to
 * `NAMEDATALEN - 1` = 63 bytes, so no table can be *named* anything longer, and this name is far
 * longer. A `from(table)` call only ever produces cache rows under the name it was given, and
 * `SupabaseOfflineClientImpl.from` refuses this one.
 *
 * Rows under this name are never evicted, never counted towards the cache size, never returned by a
 * read and never touched by an overlay; `clearAll` removes them with everything else. An older
 * build of the engine that opens the database sees them as cache rows for a table nobody queries.
 *
 * Changing this value orphans every installed device's rejected list and owner records, so it never
 * changes.
 */
internal const val RESERVED_TABLE_NAME: String =
    "__supabase_offline_kt__.reserved_engine_state.this_name_is_longer_than_any_postgres_identifier"

/** Signature, within [RESERVED_TABLE_NAME], of the persisted rejected-mutation list. */
internal const val REJECTED_MUTATIONS_SIGNATURE: String = "rejected_mutations"

/** Signature, within [RESERVED_TABLE_NAME], of the owner recorded for each queued mutation. */
internal const val MUTATION_OWNERS_SIGNATURE: String = "mutation_owners"

/**
 * Signature, within [RESERVED_TABLE_NAME], of the cache keys whose payload a network response wrote
 * after each synced mutation replayed — the payloads that already contain it.
 */
internal const val SYNCED_MUTATION_COVERAGE_SIGNATURE: String = "synced_mutation_coverage"

/**
 * Signature, within [RESERVED_TABLE_NAME], of the INSERT mutations whose send started and whose
 * outcome is unknown: the server may already hold their rows.
 */
internal const val UNCONFIRMED_SENDS_SIGNATURE: String = "unconfirmed_sends"

/**
 * Whether [table] is the engine's reserved state table rather than a table of the app's.
 *
 * @param table A table name as a caller passed it.
 * @return `true` only for [RESERVED_TABLE_NAME].
 */
internal fun isReservedTable(table: String): Boolean = table == RESERVED_TABLE_NAME
