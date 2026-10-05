// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Downloaded storage objects stay under `<filesDir>/supabase_offline`, where existing installs keep
 * them and where `clearLocalData` looks for them.
 */
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
