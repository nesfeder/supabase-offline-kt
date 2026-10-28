// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.storage.VARIANT_BOUND
import io.github.nesfeder.supabase.offline.internal.storage.VARIANT_SEPARATOR

/**
 * What the file cache's storage-row queries return from a real database, for the platform tests
 * that open one (Android, and both iOS SQLite builds) to compare with
 * [EXPECTED_STORAGE_QUERY_RESULTS].
 *
 * @property rowsForObject keys of `forObject("avatars", "u-1/me.png", …)`, sorted.
 * @property rowsForPercentObject keys of `forObject("avatars", "a%b.png", …)`, sorted.
 * @property rowsSameNameIgnoringCase keys of `sameNameIgnoringCase("avatars", "u-1/me.png")`,
 *   sorted.
 * @property rowsSamePercentNameIgnoringCase keys of `sameNameIgnoringCase("avatars", "A%B.PNG")`,
 *   sorted.
 * @property allKeys keys of every row, sorted.
 * @property totalSizeBytes the summed recorded size.
 */
internal data class StorageQueryResults(
    val rowsForObject: List<String>,
    val rowsForPercentObject: List<String>,
    val rowsSameNameIgnoringCase: List<String>,
    val rowsSamePercentNameIgnoringCase: List<String>,
    val allKeys: List<String>,
    val totalSizeBytes: Long,
)

private fun row(bucketId: String, path: String, size: Long?) =
    CachedFileRow(
        key = "$bucketId::$path",
        bucketId = bucketId,
        path = path,
        localPath = "/data/storage/$bucketId/$path",
        sizeBytes = size,
        eTag = null,
        lastFetchedEpochMs = 1_727_000_000_000,
    )

/**
 * Writes an object, one of its transformed variants, a neighbor whose path merely starts the same,
 * one whose path is exactly the bound of the object's variants (its path and a space), the same
 * path in another bucket, the same path in other case and in its bucket in other case, a name with
 * SQL wildcard characters, one holding the key's `::` and a row with no recorded size, then runs
 * every query the file cache uses.
 */
internal suspend fun OfflineDatabase.runStorageQueries(): StorageQueryResults =
    cachedFileDao().runStorageQueries()

/** [OfflineDatabase.runStorageQueries] on a DAO alone, so the in-memory one runs it too. */
internal suspend fun CachedFileDao.runStorageQueries(): StorageQueryResults {
    val dao = this
    listOf(
            row("avatars", "u-1/me.png", 10),
            row("avatars", "u-1/me.png${VARIANT_SEPARATOR}height=64&width=64", 3),
            row("avatars", "u-1/me.png.bak", 5),
            row("avatars", "u-1/me.png ", 4),
            row("photos", "u-1/me.png", 7),
            row("avatars", "U-1/Me.png", 8),
            row("Avatars", "u-1/me.png", 9),
            row("avatars", "a%b.png", 1),
            row("avatars", "aXb.png", 2),
            row("avatars", "a::b.png", 6),
            row("avatars", "unsized.png", null),
        )
        .forEach { dao.upsert(it) }
    return StorageQueryResults(
        rowsForObject =
            dao.forObject(
                    "avatars",
                    "u-1/me.png",
                    "u-1/me.png$VARIANT_SEPARATOR",
                    "u-1/me.png$VARIANT_BOUND",
                )
                .map { it.key }
                .sorted(),
        rowsForPercentObject =
            dao.forObject(
                    "avatars",
                    "a%b.png",
                    "a%b.png$VARIANT_SEPARATOR",
                    "a%b.png$VARIANT_BOUND",
                )
                .map { it.key }
                .sorted(),
        rowsSameNameIgnoringCase =
            dao.sameNameIgnoringCase("avatars", "u-1/me.png").map { it.key }.sorted(),
        rowsSamePercentNameIgnoringCase =
            dao.sameNameIgnoringCase("avatars", "A%B.PNG").map { it.key }.sorted(),
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
        rowsSameNameIgnoringCase =
            listOf("Avatars::u-1/me.png", "avatars::U-1/Me.png", "avatars::u-1/me.png"),
        rowsSamePercentNameIgnoringCase = listOf("avatars::a%b.png"),
        allKeys =
            listOf(
                "Avatars::u-1/me.png",
                "avatars::U-1/Me.png",
                "avatars::a%b.png",
                "avatars::a::b.png",
                "avatars::aXb.png",
                "avatars::u-1/me.png",
                "avatars::u-1/me.png${VARIANT_SEPARATOR}height=64&width=64",
                "avatars::u-1/me.png ",
                "avatars::u-1/me.png.bak",
                "avatars::unsized.png",
                "photos::u-1/me.png",
            ),
        totalSizeBytes = 55,
    )
