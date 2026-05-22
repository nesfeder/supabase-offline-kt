package io.github.nesfeder.supabase.internal.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PlatformBaseDirectoryAndroidTest {

    @Test
    fun `path is correct subdirectory of filesDir`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val platformDir = PlatformBaseDirectory(context)

        // When
        val path = platformDir.path

        // Then
        val expected = File(context.filesDir, "supabase_offline").absolutePath
        assertEquals(expected, path.toString())
        assertTrue(path.toString().endsWith("supabase_offline"))
    }
}