package io.github.nesfeder.supabase.internal.db

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineCacheSettingsTest {

    @Test
    fun currentDbSizeBytes_missingFile_returnsNull() {
        // Given a path that should not exist
        val bogus = "/this/path/should/not/exist/${'$'}{System.currentTimeMillis()}".toPath()

        // When
        val size = currentDbSizeBytes(bogus)

        // Then
        assertEquals(null, size)
    }
}
