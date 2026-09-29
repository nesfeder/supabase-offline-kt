// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

/**
 * Everything [ShippedDatabaseFixture] puts in the database, as read back through the library's own
 * DAOs.
 *
 * @property objectRows every cached SELECT payload for the fixture's table, oldest first.
 * @property storageRow the storage-object row under the fixture's key, if found.
 * @property pendingMutations every queued write not yet synced, in replay order.
 * @property mutationCount every row in the mutation queue, synced or not.
 */
internal data class OfflineDatabaseContents(
    val objectRows: List<ObjectRow>,
    val storageRow: StorageRow?,
    val pendingMutations: List<MutationRow>,
    val mutationCount: Int,
)

/** Reads [ShippedDatabaseFixture]'s rows back through this database's DAOs. */
internal suspend fun OfflineDatabase.readShippedFixtureContents(): OfflineDatabaseContents =
    OfflineDatabaseContents(
        objectRows = objectDao().allForTable("todos").sortedBy { it.updatedAt },
        storageRow = storageDao().getByKey(ShippedDatabaseFixture.STORAGE_ROW.key),
        pendingMutations = mutationDao().pendingAll(),
        mutationCount = mutationDao().countAll(),
    )

/** What [readShippedFixtureContents] must return for an intact shipped database. */
internal val SHIPPED_FIXTURE_CONTENTS: OfflineDatabaseContents =
    OfflineDatabaseContents(
        objectRows = ShippedDatabaseFixture.OBJECT_ROWS.sortedBy { it.updatedAt },
        storageRow = ShippedDatabaseFixture.STORAGE_ROW,
        pendingMutations =
            ShippedDatabaseFixture.MUTATION_ROWS.filter { it.syncedAtMs == null }
                .sortedBy { it.createdAtMs },
        mutationCount = ShippedDatabaseFixture.MUTATION_ROWS.size,
    )

/**
 * What [readShippedFixtureContents] must return once a schema version 1 install has migrated: the
 * same rows, with every queued write's `delta_json` NULL (read as "no declarations").
 */
internal val SHIPPED_VERSION_1_FIXTURE_CONTENTS_AFTER_MIGRATION: OfflineDatabaseContents =
    SHIPPED_FIXTURE_CONTENTS.copy(
        pendingMutations =
            SHIPPED_FIXTURE_CONTENTS.pendingMutations.map { row -> row.copy(deltaJson = null) }
    )
