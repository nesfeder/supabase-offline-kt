package io.github.nesfeder.supabase.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.testdoubles.closingAfter
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Android factory builds a client over the real Room database builder for every supported
 * configuration, one client per database, and puts the database where the configuration says.
 *
 * Every client is closed: an open one keeps its database claimed for the rest of the process.
 */
@RunWith(AndroidJUnit4::class)
class CreateSupabaseOfflineClientAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directories = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        directories.forEach { it.deleteRecursively() }
    }

    /** A Supabase client with PostgREST installed; its mock engine is never reached here. */
    private fun testSupabaseClient(): SupabaseClient = newTestSupabaseClient()

    private fun uniqueDirectory(): File =
        File(context.cacheDir, "offline-client-${Random.nextLong().toULong()}").also { directories += it }

    private fun configIn(directory: File) = SupabaseOfflineConfig(offlineStoragePath = directory.absolutePath.toPath())

    @Test
    fun `createSupabaseOfflineClient returns non-null client with default config`() = runTest {
        // Given
        val supabase = testSupabaseClient()

        // When
        createSupabaseOfflineClient(context, supabase).closingAfter { client ->
            // Then
            assertNotNull(client)
            assertSame(supabase, client.supabase)
        }
    }

    @Test
    fun `createSupabaseOfflineClient respects offlineStoragePath override`() = runTest {
        // Given
        val config = configIn(uniqueDirectory())

        // When
        createSupabaseOfflineClient(context, testSupabaseClient(), config).closingAfter { client ->
            // Then — construction succeeds; where files land is covered by the storage tests
            assertNotNull(client)
        }
    }

    @Test
    fun `createSupabaseOfflineClient allows unlimited cache size`() = runTest {
        // Given
        val config = SupabaseOfflineConfig(cacheSizeBytes = CACHE_SIZE_UNLIMITED, fileCacheSizeBytes = CACHE_SIZE_UNLIMITED)

        // When
        createSupabaseOfflineClient(context, testSupabaseClient(), config).closingAfter { client ->
            // Then
            assertNotNull(client)
        }
    }

    @Test
    fun `a second client on the same database fails fast while the first is open`() = runTest {
        // Given — one client open on a database
        val config = configIn(uniqueDirectory())
        createSupabaseOfflineClient(context, testSupabaseClient(), config).closingAfter {
            // When / Then — two engines on one file would replay the same queue twice
            val failure = assertFailsWith<IllegalStateException> {
                createSupabaseOfflineClient(context, testSupabaseClient(), config)
            }
            assertTrue("already open" in failure.message.orEmpty(), "unclear message: ${failure.message}")
        }
    }

    @Test
    fun `the default database is claimed like any other`() = runTest {
        // Given
        createSupabaseOfflineClient(context, testSupabaseClient()).closingAfter {
            // When / Then
            assertFailsWith<IllegalStateException> { createSupabaseOfflineClient(context, testSupabaseClient()) }
        }
    }

    @Test
    fun `closing a client lets another open the same database and read what it wrote`() = runTest {
        // Given — a client that queued nothing but opened and wrote its database
        val config = configIn(uniqueDirectory())
        val first = createSupabaseOfflineClient(context, testSupabaseClient(), config)
        assertEquals(0, first.pendingMutationCount())

        // When
        first.close()
        val second = createSupabaseOfflineClient(context, testSupabaseClient(), config)

        // Then — the file was released, and the second client reads it
        second.closingAfter { assertEquals(0, it.pendingMutationCount()) }
    }

    @Test
    fun `a closed client refuses further use`() = runTest {
        // Given
        val client = createSupabaseOfflineClient(context, testSupabaseClient(), configIn(uniqueDirectory()))

        // When
        client.close()
        client.close()

        // Then — closing twice is harmless; using it afterwards is a clear error
        val failure = assertFailsWith<IllegalStateException> { client.from("todos") }
        assertTrue("closed" in failure.message.orEmpty(), "unclear message: ${failure.message}")
    }

    @Test
    fun `offlineStoragePath moves the database as well as the downloaded files`() = runTest {
        // Given
        val directory = uniqueDirectory()
        val defaultDatabase = context.getDatabasePath("supabase_offline.db")
        defaultDatabase.delete()

        // When — the first query opens the database
        createSupabaseOfflineClient(context, testSupabaseClient(), configIn(directory)).closingAfter { client ->
            client.pendingMutationCount()
        }

        // Then — it lives in the configured directory, and nothing was created at the default path
        assertTrue(File(directory, "supabase_offline.db").exists(), "the database is not in offlineStoragePath")
        assertFalse(defaultDatabase.exists(), "a database was created at the default path as well")
    }

    @Test
    fun `without offlineStoragePath the database stays at its default path`() = runTest {
        // Given
        val defaultDatabase = context.getDatabasePath("supabase_offline.db")

        // When
        createSupabaseOfflineClient(context, testSupabaseClient()).closingAfter { client ->
            client.pendingMutationCount()
        }

        // Then
        assertTrue(defaultDatabase.exists(), "the database left its default path")
    }
}
