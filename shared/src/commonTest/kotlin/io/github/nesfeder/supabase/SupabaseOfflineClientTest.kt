package io.github.nesfeder.supabase

import io.github.nesfeder.supabase.internal.FakePostgrest
import io.github.nesfeder.supabase.internal.FakeSupabaseClient
import io.github.nesfeder.supabase.internal.db.FakeMutationDao
import io.github.nesfeder.supabase.internal.db.FakeObjectDao
import io.github.nesfeder.supabase.internal.db.FakeStorageDao
import io.github.nesfeder.supabase.internal.db.OfflineStore
import io.github.nesfeder.supabase.internal.db.newTestOfflineStore
import io.github.nesfeder.supabase.internal.storage.FakeStorage
import io.github.nesfeder.supabase.internal.storage.OfflineBucketApi
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.PostgrestQueryBuilder
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SupabaseOfflineClientTest {

    private fun newOfflineStore(): OfflineStore =
        newTestOfflineStore(
            objectDao = FakeObjectDao(),
            storageDao = FakeStorageDao(),
            mutationDao = FakeMutationDao(),
        )

    @Test
    fun from_withPostgrestInstalled_returnsOfflineBuilder() = runTest {
        // Given
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        // Register a trivial builder for the default schema + table so that
        // SupabaseOfflineClientImpl.from(...) can resolve it via FakePostgrest.
        val defaultSchema = postgrest.config.defaultSchema
        val registeredBuilder = PostgrestQueryBuilder(postgrest, "assets")
        supabase.registerBuilder(defaultSchema, "assets", registeredBuilder)

        val store = newOfflineStore()
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

        val client = SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { baseDir }
        )

        // When
        val offlineBuilder = client.from("assets")

        // Then - we simply assert that we received some builder instance; the
        // detailed behaviour of OfflinePostgrestQueryBuilderImpl is covered in
        // its dedicated tests.
        assertNotNull(offlineBuilder)
    }

    @Test
    fun from_schema_table_returnsOfflineBuilder() = runTest {
        // Given
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        val registeredBuilder = PostgrestQueryBuilder(postgrest, "assets")
        supabase.registerBuilder("custom", "assets", registeredBuilder)

        val store = newOfflineStore()
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

        val client = SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { baseDir }
        )

        // When
        val offlineBuilder = client.from("custom", "assets")

        // Then
        assertNotNull(offlineBuilder)
    }

    // ---------------------------------------------------------------------
    // Guards: ensurePostgrestInstalled / ensureStorageInstalled
    // ---------------------------------------------------------------------

    @Test
    fun from_whenPostgrestNotInstalled_throwsHelpfulError() = runTest {
        // Given
        val supabase: SupabaseClient = FakeSupabaseClient()
        val store = newOfflineStore()
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

        val client = SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { baseDir }
        )

        // When / Then
        val thrown = runCatching { client.from("assets") }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(
            thrown.message?.contains(
                "Plugin rest not installed or not of type Postgrest. Consider installing Postgrest within your SupabaseClientBuilder"
            ),
            true,
            "Expected helpful Postgrest installation error message, but was: ${thrown.message}"
        )
    }

    @Test
    fun storage_whenStorageNotInstalled_throwsHelpfulError() = runTest {
        // Given: FakeSupabaseClient without storage plugin (default)
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val store = newOfflineStore()
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

        val client = SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { baseDir }
        )

        // When / Then
        val thrown = runCatching { client.storage("bucket") }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(
            thrown.message?.contains(
                "Storage plugin is not installed. Install with createSupabaseClient { install(Storage) }"
            ),
            true,
            "Expected helpful Storage installation error message, but was: ${thrown.message}"
        )
    }

    @Test
    fun get_operators_delegatesToFrom() = runTest {
        // Given
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        val builder1 = PostgrestQueryBuilder(postgrest, "t1")
        val builder2 = PostgrestQueryBuilder(postgrest, "t2")
        supabase.registerBuilder("public", "t1", builder1)
        supabase.registerBuilder("schema", "t2", builder2)

        val store = newOfflineStore()
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

        val client = SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { baseDir }
        )

        // When
        val op1 = client["t1"]
        val op2 = client["schema", "t2"]

        // Then
        assertNotNull(op1)
        assertNotNull(op2)
    }

    @Test
    fun storage_returnsOfflineBucketApi() = runTest {
        // Given
        val postgrest = FakePostgrest()
        val storage = FakeStorage()
        val supabase = FakeSupabaseClient(postgrest, storage)
        val store = newOfflineStore()
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

        val client = SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { baseDir }
        )

        // When
        val bucketApi = client.storage("bucket")

        // Then
        assertIs<OfflineBucketApi>(bucketApi)
    }

    @Test
    fun syncAllPendingMutations_delegatesToSyncer() = runTest {
        // Given
        val store = newOfflineStore()
        store.enqueueInsert("assets", kotlinx.serialization.json.buildJsonArray { })

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

        val seenTables = mutableListOf<String>()
        postgrest.throwException = { table ->
            seenTables.add(table)
            // Throw to prevent actual network call failure confusion (though FakePostgrest would fail anyway)
            // Use specific error we can ignore or expected
            null // Let it proceed to try to find builder
        }
        // Use a builder that will fail on execute but we check if `from` was called
        // Actually, FakePostgrest throws if no builder registered.
        // We want to verify `from` was called.
        // If we register a builder, the syncer will try to execute insert on it.
        // Since `PostgrestQueryBuilder` requires real client, it will fail.
        // But we just want to verify delegation.

        // Simpler: Use throwException to capture the call and then throw.
        postgrest.throwException = { table ->
            seenTables.add(table)
            throw RuntimeException("Stop sync")
        }

        val client = SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { baseDir }
        )

        // When
        val result = client.syncAllPendingMutations()

        // Then - Since we threw "Stop sync", result is 0 (swallowed or propagated? implementation says swallowed for table level)
        // Wait, syncAllPendingMutations swallows table errors.
        assertEquals(0, result)
        assertEquals(listOf("assets"), seenTables)
    }

    @Test
    fun syncPendingMutations_delegatesToSyncer() = runTest {
        // Given
        val store = newOfflineStore()
        store.enqueueInsert("assets", kotlinx.serialization.json.buildJsonArray { })

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

        val seenTables = mutableListOf<String>()
        postgrest.throwException = { table ->
            seenTables.add(table)
            throw RuntimeException("Stop sync")
        }

        val client = SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { baseDir }
        )

        // When
        // syncPendingMutations throws on unknown errors? implementation says propagated.
        try {
            client.syncPendingMutations("assets")
        } catch (e: Exception) {
            // Expected
        }

        // Then
        assertEquals(listOf("assets"), seenTables)
    }

    // NOTE: We intentionally do not test the exact failure message from
    // ensurePostgrestInstalled/ensureStorageInstalled here because wiring
    // FakeSupabaseClient with arbitrary plugin states is non-trivial and
    // brittle against upstream changes. The critical happy-path wiring of
    // from(..) and storage(..) is covered by other tests.

    @Test
    fun pendingMutationCount_delegatesToStore() = runTest {
        // Given
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val store = newOfflineStore()
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

        val client = SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { baseDir }
        )

        // Seed some mutations directly in the store.
        store.enqueueDelete("assets")
        store.enqueueDelete("other")

        // When
        val total = client.pendingMutationCount()
        val assetsOnly = client.pendingMutationCount("assets")

        // Then
        assertEquals(2, total)
        assertEquals(1, assetsOnly)
    }
}
