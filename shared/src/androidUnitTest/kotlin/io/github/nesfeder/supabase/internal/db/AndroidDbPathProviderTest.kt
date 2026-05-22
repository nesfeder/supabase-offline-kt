package io.github.nesfeder.supabase.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AndroidDbPathProviderTest {

    @Test
    fun `dbPath returns correct path in app database directory`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = AndroidDbPathProvider(context)

        // When
        val path = provider.dbPath()

        // Then
        val expectedPath = context.getDatabasePath("supabase_offline.db").absolutePath
        assertEquals(expectedPath, path.toString())
        assertTrue(path.toString().endsWith("supabase_offline.db"))
    }
}