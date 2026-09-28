package io.github.nesfeder.supabase.offline.internal.db

import okio.FileSystem
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNull

/** Ported from the previous standalone engine. */
class OfflineCacheSettingsTest {

    @Test
    fun currentDbSizeBytes_missingFile_returnsNull() {
        // Given — a path that does not exist
        val missing = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-missing-${Random.nextLong()}" / "offline.db"

        // When
        val size = currentDbSizeBytes(missing)

        // Then
        assertNull(size)
    }
}
