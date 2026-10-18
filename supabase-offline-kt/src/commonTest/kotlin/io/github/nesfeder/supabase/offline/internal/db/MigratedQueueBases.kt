// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

/**
 * The bases of [ShippedDatabaseFixture]'s queued writes once this database migrated it, then of its
 * UPDATE after one is written: every row starts with none, and the columns hold what is written.
 *
 * @return the bases before and after, each as `(baseVersion, baseJson)` per row id.
 */
internal suspend fun OfflineDatabase.basesBeforeAndAfterOneIsWritten(): Pair<RowBases, RowBases> {
    val dao = mutationDao()
    val before = dao.allRowsById().associate { row -> row.id to (row.baseVersion to row.baseJson) }
    dao.updateBase(id = UPDATE_ID, baseVersion = MIGRATED_BASE_VERSION, baseJson = MIGRATED_BASE_JSON)
    val after = dao.allRowsById().associate { row -> row.id to (row.baseVersion to row.baseJson) }
    return before to after
}

/** Every row of the queue, pending and synced, by id. */
private suspend fun MutationDao.allRowsById(): List<MutationRow> =
    ShippedDatabaseFixture.MUTATION_ROWS.mapNotNull { row -> byId(row.id) }

/** The fixture's queued UPDATE. */
internal const val UPDATE_ID: Long = 3

/** A version as a guarded write stores it, with its `+00:00` offset. */
internal const val MIGRATED_BASE_VERSION: String = "2026-10-18T13:00:27.486123+00:00"

/** The base row stored beside [MIGRATED_BASE_VERSION]. */
internal const val MIGRATED_BASE_JSON: String =
    """{"title":"Walk the dog","done":false,"updated_at":"2026-10-18T13:00:27.486123+00:00"}"""

/** Each queued row's `(baseVersion, baseJson)`, by id. */
internal typealias RowBases = Map<Long, Pair<String?, String?>>

/** Every fixture row with no base. */
private val NO_BASES: RowBases =
    ShippedDatabaseFixture.MUTATION_ROWS.associate { row -> row.id to Pair(null, null) }

/** What [basesBeforeAndAfterOneIsWritten] must return: no base, then one on the UPDATE. */
internal val MIGRATED_QUEUE_BASES: Pair<RowBases, RowBases> =
    NO_BASES to (NO_BASES + (UPDATE_ID to Pair(MIGRATED_BASE_VERSION, MIGRATED_BASE_JSON)))
