// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.storage.VARIANT_SEPARATOR

/**
 * What the file cache's storage-row queries return from a real database, for the platform tests
 * that open one (Android, and both iOS SQLite builds) to compare with
 * [EXPECTED_STORAGE_QUERY_RESULTS].
 *
 * @property rowsForObject keys of `forObject("avatars", "u-1/me.png", …)`, sorted.
 * @property rowsForPercentObject keys of `forObject("avatars", "a%b.png", …)`, sorted.
 * @property allKeys keys of every row, sorted.
 * @property totalSizeBytes the summed recorded size.
 */
internal data class StorageQueryResults(
    val rowsForObject: List<String>,
    val rowsForPercentObject: List<String>,
    val allKeys: List<String>,
    val totalSizeBytes: Long,
)

private fun row(bucketId: String, path: String, size: Long?) =
    StorageRow(
        key = "$bucketId::$path",
        bucketId = bucketId,
        path = path,
        localPath = "/data/storage/$bucketId/$path",
        sizeBytes = size,
        eTag = null,
        lastFetchedEpochMs = 1_727_000_000_000,
    )

/**
 * Writes an object, one of its transformed variants, a neighbour whose path merely starts the same,
 * the same path in another bucket, a name with SQL wildcard characters and a row with no recorded
 * size, then runs every query the file cache uses.
 */
internal suspend fun OfflineDatabase.runStorageQueries(): StorageQueryResults {
    val dao = storageDao()
    listOf(
            row("avatars", "u-1/me.png", 10),
            row("avatars", "u-1/me.png${VARIANT_SEPARATOR}height=64&width=64", 3),
            row("avatars", "u-1/me.png.bak", 5),
            row("photos", "u-1/me.png", 7),
            row("avatars", "a%b.png", 1),
            row("avatars", "aXb.png", 2),
            row("avatars", "unsized.png", null),
        )
        .forEach { dao.upsert(it) }
    return StorageQueryResults(
        rowsForObject =
            dao.forObject("avatars", "u-1/me.png", "u-1/me.png$VARIANT_SEPARATOR")
                .map { it.key }
                .sorted(),
        rowsForPercentObject =
            dao.forObject("avatars", "a%b.png", "a%b.png$VARIANT_SEPARATOR")
                .map { it.key }
                .sorted(),
        allKeys = dao.all().map { it.key }.sorted(),
        totalSizeBytes = dao.totalSizeBytes(),
    )
}

/** What [runStorageQueries] must return. */
internal val EXPECTED_STORAGE_QUERY_RESULTS: StorageQueryResults =
    StorageQueryResults(
        rowsForObject =
            listOf(
                "avatars::u-1/me.png",
                "avatars::u-1/me.png${VARIANT_SEPARATOR}height=64&width=64",
            ),
        rowsForPercentObject = listOf("avatars::a%b.png"),
        allKeys =
            listOf(
                "avatars::a%b.png",
                "avatars::aXb.png",
                "avatars::u-1/me.png",
                "avatars::u-1/me.png${VARIANT_SEPARATOR}height=64&width=64",
                "avatars::u-1/me.png.bak",
                "avatars::unsized.png",
                "photos::u-1/me.png",
            ),
        totalSizeBytes = 28,
    )
