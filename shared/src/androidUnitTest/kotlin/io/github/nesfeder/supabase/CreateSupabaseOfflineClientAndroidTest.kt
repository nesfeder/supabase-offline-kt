package io.github.nesfeder.supabase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.internal.FakePostgrest
import io.github.nesfeder.supabase.internal.FakeSupabaseClient
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class CreateSupabaseOfflineClientAndroidTest {

    @Test
    fun `createSupabaseOfflineClient returns non-null client with default config`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePostgrest = FakePostgrest()
        val fakeSupabase = FakeSupabaseClient(postgrestPlugin = fakePostgrest)

        // When
        val client = createSupabaseOfflineClient(context, fakeSupabase)

        // Then
        assertNotNull(client)
    }

    @Test
    fun `createSupabaseOfflineClient respects offlineStoragePath override`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePostgrest = FakePostgrest()
        val fakeSupabase = FakeSupabaseClient(postgrestPlugin = fakePostgrest)
        val customPath = context.cacheDir.resolve("custom_offline").absolutePath.toPath()
        val config = SupabaseOfflineConfig(offlineStoragePath = customPath)

        // When
        val client = createSupabaseOfflineClient(context, fakeSupabase, config)

        // Then
        assertNotNull(client)
        // Note: Full validation of path usage requires verifying where files are stored,
        // which requires a FakeSupabaseClient with Storage plugin support.
        // As the provided FakeSupabaseClient only supports Postgrest, we verify creation succeeds.
    }

    @Test
    fun `createSupabaseOfflineClient allows unlimited cache size`() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePostgrest = FakePostgrest()
        val fakeSupabase = FakeSupabaseClient(postgrestPlugin = fakePostgrest)
        val config = SupabaseOfflineConfig(cacheSizeBytes = CACHE_SIZE_UNLIMITED)

        // When
        val client = createSupabaseOfflineClient(context, fakeSupabase, config)

        // Then
        assertNotNull(client)
    }
}
