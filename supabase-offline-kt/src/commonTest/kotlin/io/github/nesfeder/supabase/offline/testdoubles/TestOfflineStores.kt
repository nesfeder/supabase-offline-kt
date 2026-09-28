package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.db.DbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import okio.Path
import okio.Path.Companion.toPath

/** [DbPathProvider] pointing at a fixed path that no test ever opens. */
internal object FakeDbPathProvider : DbPathProvider {
    override fun dbPath(): Path = "/fake/offline.db".toPath()
}

/**
 * Builds a real [OfflineStore] over the shared in-memory DAO fakes, with size-based eviction off
 * and a fixed clock, so a test drives the store's real logic without Room or a filesystem.
 *
 * @param objectDao object-cache fake; pass one in to seed or inspect cached payloads.
 * @param storageDao storage-metadata fake.
 * @param mutationDao mutation-queue fake; pass one in to inspect what was queued.
 * @param nowMillis clock for row timestamps; defaults to a fixed `0L`.
 * @return a store wired to the given fakes.
 */
internal fun newTestOfflineStore(
    objectDao: FakeObjectDao = FakeObjectDao(),
    storageDao: FakeStorageDao = FakeStorageDao(),
    mutationDao: FakeMutationDao = FakeMutationDao(),
    nowMillis: () -> Long = { 0L },
): OfflineStore = OfflineStore(
    objectDao = objectDao,
    storageDao = storageDao,
    mutationDao = mutationDao,
    dbPathProvider = FakeDbPathProvider,
    settings = OfflineCacheSettings(
        maxDbSizeBytes = null,
        evictionBatchSize = 0,
        minRetainedBytes = 0,
    ),
    nowMillis = nowMillis,
    dbSizeProvider = { null },
)
