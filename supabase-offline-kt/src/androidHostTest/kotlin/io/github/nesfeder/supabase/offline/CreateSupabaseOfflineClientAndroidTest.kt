package io.github.nesfeder.supabase.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient

/**
 * The Android factory builds a client over the real Room database builder for every supported
 * configuration.
 */
@RunWith(AndroidJUnit4::class)
class CreateSupabaseOfflineClientAndroidTest {

    /** A Supabase client with PostgREST installed; its mock engine is never reached here. */
    private fun testSupabaseClient(): SupabaseClient = newTestSupabaseClient()

    @Test
    fun `createSupabaseOfflineClient returns non-null client with default config`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val supabase = testSupabaseClient()

        // When
        val client = createSupabaseOfflineClient(context, supabase)

        // Then
        assertNotNull(client)
        assertSame(supabase, client.supabase)
    }

    @Test
    fun `createSupabaseOfflineClient respects offlineStoragePath override`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val supabase = testSupabaseClient()
        val customPath = context.cacheDir.resolve("custom_offline").absolutePath.toPath()
        val config = SupabaseOfflineConfig(offlineStoragePath = customPath)

        // When
        val client = createSupabaseOfflineClient(context, supabase, config)

        // Then — construction succeeds; where files land is covered by the storage tests
        assertNotNull(client)
    }

    @Test
    fun `createSupabaseOfflineClient allows unlimited cache size`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val supabase = testSupabaseClient()
        val config = SupabaseOfflineConfig(cacheSizeBytes = CACHE_SIZE_UNLIMITED)

        // When
        val client = createSupabaseOfflineClient(context, supabase, config)

        // Then
        assertNotNull(client)
    }
}
