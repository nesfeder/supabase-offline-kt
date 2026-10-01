// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import android.content.Context
import androidx.startup.AppInitializer
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.internal.ApplicationContextInitializer
import io.github.nesfeder.supabase.offline.internal.recordedApplicationContext
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * `install(Offline)` on Android: the application context comes from androidx.startup, as
 * supabase-kt's Storage plugin gets its own, and the plugin opens the real Room database where its
 * configuration says, one client per database.
 *
 * Every client is closed: an open one keeps its database claimed for the rest of the process.
 */
@RunWith(AndroidJUnit4::class)
class OfflinePluginAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directories = mutableListOf<File>()

    /**
     * Robolectric starts no content provider, so androidx.startup's never runs on its own, and it
     * builds a new application per test: record this test's, as the initializer does at startup.
     */
    @BeforeTest
    fun startUp() {
        ApplicationContextInitializer().create(context)
    }

    @AfterTest
    fun tearDown() {
        directories.forEach { it.deleteRecursively() }
    }

    private fun uniqueDirectory(): File =
        File(context.cacheDir, "offline-plugin-${Random.nextLong().toULong()}").also {
            directories += it
        }

    /** A client with PostgREST and the plugin; its mock engine is never reached here. */
    private fun pluginClient(configure: Offline.Config.() -> Unit = {}): SupabaseClient =
        newTestSupabaseClient {
            install(Postgrest)
            install(Offline) { configure() }
        }

    @Test
    fun the_manifest_registers_the_initializer_with_androidx_startup() {
        // When — the provider the merged manifest declares starts, as it does when the app starts
        Robolectric.setupContentProvider(
            InitializationProvider::class.java,
            "${context.packageName}.androidx-startup",
        )

        // Then — it found the library's initializer in the manifest and ran it
        assertTrue(
            AppInitializer.getInstance(context)
                .isEagerlyInitialized(ApplicationContextInitializer::class.java),
            "the manifest does not register the initializer",
        )
    }

    @Test
    fun the_initializer_records_the_application_context() {
        // When
        ApplicationContextInitializer().create(context)

        // Then
        assertSame(context.applicationContext, recordedApplicationContext())
    }

    @Test
    fun the_database_is_opened_in_the_data_directory() = runTest {
        // Given
        val directory = uniqueDirectory()

        // When
        val supabase = pluginClient { dataDirectory = directory.absolutePath }
        assertEquals(0, supabase.offline.pendingMutationCount())

        // Then
        assertTrue(File(directory, "supabase_offline.db").exists(), "no database in the directory")
        supabase.close()
    }

    @Test
    fun without_a_data_directory_the_database_is_in_the_apps_database_directory() = runTest {
        // Given
        val supabase = pluginClient()

        // When
        assertEquals(0, supabase.offline.pendingMutationCount())

        // Then
        assertTrue(context.getDatabasePath("supabase_offline.db").exists(), "no database there")
        supabase.close()
    }

    @Test
    fun a_second_client_on_the_same_data_directory_fails_until_the_first_closes() = runTest {
        // Given
        val directory = uniqueDirectory()
        val first = pluginClient { dataDirectory = directory.absolutePath }

        // When
        val failure =
            assertFailsWith<IllegalStateException> {
                pluginClient { dataDirectory = directory.absolutePath }
            }
        first.close()

        // Then — the claim says why, and closing the first client frees the database
        assertTrue("already open" in failure.message.orEmpty(), "unclear: ${failure.message}")
        val second = pluginClient { dataDirectory = directory.absolutePath }
        assertEquals(0, second.offline.pendingMutationCount())
        second.close()
    }
}
