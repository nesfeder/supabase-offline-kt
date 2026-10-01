// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The Android database file stays `supabase_offline.db` in the app's database directory, where
 * existing installs keep their cache and their unsent writes.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDbPathProviderTest {

    @Test
    fun `dbPath returns correct path in app database directory`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()

        // When
        val path = defaultDatabasePath(context)

        // Then
        val expectedPath = context.getDatabasePath("supabase_offline.db").absolutePath
        assertEquals(expectedPath, path.toString())
        assertTrue(path.toString().endsWith("supabase_offline.db"))
    }
}
