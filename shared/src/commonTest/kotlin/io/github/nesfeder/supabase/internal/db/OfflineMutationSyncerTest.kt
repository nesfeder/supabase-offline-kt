package io.github.nesfeder.supabase.internal.db

import io.github.nesfeder.supabase.OfflineLogLevel
import io.github.nesfeder.supabase.internal.CapturingLogger
import io.github.nesfeder.supabase.internal.FakePostgrest
import io.github.nesfeder.supabase.internal.FakeSupabaseClient
import io.github.nesfeder.supabase.internal.fakeMutationRow
import io.github.nesfeder.supabase.internal.makeFakeHttpResponse
import io.github.nesfeder.supabase.internal.postgrest.FakePostgrestQueryBuilderWrapper
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OfflineMutationSyncerTest {

    // ─────────────────────────────────────────────────────────────────────
    // No-pending / already-synced tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun syncAllTables_noPending_returnsZero() = runTest {
        // Given
        val store = newTestOfflineStore()
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        // Factory should never be invoked.
        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, _ ->
                error("postgrestFactory should not be called when there are no pending mutations")
            }
        )

        // When
        val synced = syncer.syncAllTables()

        // Then
        assertEquals(0, synced)
    }

    @Test
    fun syncAllTables_ignoresAlreadySyncedMutations() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val storageDao = FakeStorageDao()
        val mutationDao = FakeMutationDao()

        // Already-synced mutation: should not be considered "pending".
        mutationDao.insert(
            fakeMutationRow(
                table = "assets",
                createdAtMs = 1L,
                syncedAtMs = 1_700_000_000_000L,
            )
        )

        val store = newTestOfflineStore(
            objectDao = objectDao,
            storageDao = storageDao,
            mutationDao = mutationDao,
        )

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        // Factory should not be invoked because there are no pending mutations.
        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, _ ->
                error("postgrestFactory should not be called when only synced mutations exist")
            }
        )

        // When
        val synced = syncer.syncAllTables()

        // Then
        assertEquals(0, synced)
        // Underlying DAO still retains the synced mutation, countPending only counts unsynced mutations.
        assertEquals(0, mutationDao.countPending())
        assertEquals(1, mutationDao.allForTable("assets").size)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Malformed JSON bodies should be dropped (deleteMutation) for operations
    // that require parsing: INSERT / UPSERT / UPDATE
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun syncTable_insert_malformedBody_deletesMutation() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val storageDao = FakeStorageDao()
        val mutationDao = FakeMutationDao()
        // Malformed JSON (not an array)
        mutationDao.insert(
            fakeMutationRow(
                table = "assets",
                operation = MutationOperation.INSERT,
                bodyJson = "not-json-array",
                createdAtMs = 1L,
            )
        )

        val store = newTestOfflineStore(objectDao, storageDao, mutationDao)
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest
        )
        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, table ->
                assertEquals("assets", table)
                assetsWrapper
            }
        )

        // When
        val synced = syncer.syncTable(table = "assets")

        // Then - mutation dropped, nothing synced
        assertEquals(0, synced)
        assertEquals(0, mutationDao.countAll())
    }

    @Test
    fun syncTable_upsert_malformedBody_deletesMutation() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val storageDao = FakeStorageDao()
        val mutationDao = FakeMutationDao()
        mutationDao.insert(
            fakeMutationRow(
                table = "assets",
                operation = MutationOperation.UPSERT,
                bodyJson = "not-json-array",
                createdAtMs = 1L,
            )
        )

        val store = newTestOfflineStore(objectDao, storageDao, mutationDao)
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest
        )
        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, _ -> assetsWrapper }
        )

        // When
        val synced = syncer.syncTable(table = "assets")

        // Then
        assertEquals(0, synced)
        assertEquals(0, mutationDao.countAll())
    }

    @Test
    fun syncTable_update_malformedBody_deletesMutation() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val storageDao = FakeStorageDao()
        val mutationDao = FakeMutationDao()
        // UPDATE expects a JsonElement (object), provide malformed
        mutationDao.insert(
            fakeMutationRow(
                table = "assets",
                operation = MutationOperation.UPDATE,
                bodyJson = "",
                createdAtMs = 1L,
            )
        )

        val store = newTestOfflineStore(objectDao, storageDao, mutationDao)
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest
        )
        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, _ -> assetsWrapper }
        )

        // When
        val synced = syncer.syncTable(table = "assets")

        // Then
        assertEquals(0, synced)
        assertEquals(0, mutationDao.countAll())
    }

    // ─────────────────────────────────────────────────────────────────────
    // Verify FakePostgrestQueryBuilderWrapper request lambdas are invoked
    // and that important options propagate
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun syncTable_invokesRequestLambdas_andPropagatesOptions() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val storageDao = FakeStorageDao()
        val mutationDao = FakeMutationDao()

        // Insert
        mutationDao.insert(
            fakeMutationRow(
                table = "assets",
                operation = MutationOperation.INSERT,
                bodyJson = buildJsonArray { add(buildJsonObject { put("id", 1) }) }.toString(),
                defaultToNull = false,
                createdAtMs = 1L,
            )
        )
        // Upsert
        mutationDao.insert(
            fakeMutationRow(
                table = "assets",
                operation = MutationOperation.UPSERT,
                bodyJson = buildJsonArray { add(buildJsonObject { put("id", 2) }) }.toString(),
                onConflict = "id",
                ignoreDuplicates = true,
                defaultToNull = false,
                createdAtMs = 2L,
            )
        )
        // Update
        mutationDao.insert(
            fakeMutationRow(
                table = "assets",
                operation = MutationOperation.UPDATE,
                bodyJson = buildJsonObject { put("name", "X") }.toString(),
                createdAtMs = 3L,
            )
        )
        // Delete
        mutationDao.insert(
            fakeMutationRow(
                table = "assets",
                operation = MutationOperation.DELETE,
                bodyJson = "{}",
                createdAtMs = 4L,
            )
        )

        val store = newTestOfflineStore(objectDao, storageDao, mutationDao)
        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val wrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest
        )
        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, _ -> wrapper }
        )

        // When
        val synced = syncer.syncTable(table = "assets")

        // Then: all 4 mutations applied and marked synced
        assertEquals(4, synced)
        assertEquals(4, mutationDao.allForTable("assets").size)
        // Request lambdas were invoked by the fake
        assertTrue(wrapper.insertRequestInvoked)
        assertTrue(wrapper.upsertRequestInvoked)
        assertTrue(wrapper.updateRequestInvoked)
        assertTrue(wrapper.deleteRequestInvoked)
        // Options propagated
        assertEquals(false, wrapper.lastInsertDefaultToNull)
        assertEquals("id", wrapper.lastUpsertOnConflict)
        assertEquals(true, wrapper.lastUpsertIgnoreDuplicates)
        assertEquals(false, wrapper.lastUpsertDefaultToNull)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Successful mutation paths (INSERT / UPSERT / UPDATE / DELETE)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun syncTable_insert_success_removesMutationsAndCountsSynced() = runTest {
        // Given
        val store = newTestOfflineStore()
        val body1: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        val body2: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "2") })
        }

        store.enqueueInsert("assets", body1)
        store.enqueueInsert("assets", body2)

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        // Default FakePostgrestQueryBuilderWrapper returns success for insert.
        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest,
        )

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, table ->
                when (table) {
                    "assets" -> assetsWrapper
                    else -> error("Unexpected table: $table")
                }
            }
        )

        // When
        val synced = syncer.syncTable(table = "assets")

        // Then - both inserts were applied and removed from pending set
        assertEquals(2, synced)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun syncTable_upsert_success_removesMutationsAndCountsSynced() = runTest {
        // Given
        val store = newTestOfflineStore()
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "u1") })
        }

        store.enqueueUpsert(
            table = "assets",
            bodyJson = body,
            filter = null,
            onConflict = "id",
            ignoreDuplicates = false,
            defaultToNull = true,
        )

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest,
        )

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, table ->
                when (table) {
                    "assets" -> assetsWrapper
                    else -> error("Unexpected table: $table")
                }
            }
        )

        // When
        val synced = syncer.syncTable(table = "assets")

        // Then
        assertEquals(1, synced)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun syncTable_update_success_removesMutationsAndCountsSynced() = runTest {
        // Given
        val store = newTestOfflineStore()
        val patch: JsonElement = buildJsonObject {
            put("status", "updated")
        }

        store.enqueueUpdate(
            table = "assets",
            body = patch,
            filter = null,
        )

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest,
        )

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, table ->
                when (table) {
                    "assets" -> assetsWrapper
                    else -> error("Unexpected table: $table")
                }
            }
        )

        // When
        val synced = syncer.syncTable(table = "assets")

        // Then
        assertEquals(1, synced)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun syncTable_delete_success_removesMutationsAndCountsSynced() = runTest {
        // Given
        val store = newTestOfflineStore()

        store.enqueueDelete(
            table = "assets",
            filter = null,
        )

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest,
        )

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, table ->
                when (table) {
                    "assets" -> assetsWrapper
                    else -> error("Unexpected table: $table")
                }
            }
        )

        // When
        val synced = syncer.syncTable(table = "assets")

        // Then
        assertEquals(1, synced)
        assertEquals(0, store.pendingMutationCount())
    }

    // ─────────────────────────────────────────────────────────────────────
    // Error handling: permanent vs network vs unknown errors
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun syncTable_permanentFailure_dropsMutationAndDoesNotThrow() = runTest {
        // Given
        val store = newTestOfflineStore()
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert("assets", body)

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        val response = makeFakeHttpResponse()

        // Configure the wrapper to always fail with a 409-like permanent error.
        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest,
        ).apply {
            insertResult = Result.failure(
                PostgrestRestException(
                    message = "conflict",
                    hint = null,
                    details = null,
                    code = "409",
                    response = response
                )
            )
        }

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, table ->
                when (table) {
                    "assets" -> assetsWrapper
                    else -> error("Unexpected table: $table")
                }
            }
        )

        // When - permanent failures should be swallowed at mutation level,
        // the offending mutation dropped, and no exception thrown.
        val synced = syncer.syncTable(table = "assets")

        // Then - mutation is removed (cannot ever succeed), but not counted
        // as "synced" because it did not apply on the server.
        assertEquals(0, synced)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun syncTable_networkFailure_keepsMutationAndDoesNotThrow() = runTest {
        // Given
        val store = newTestOfflineStore()
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert("assets", body)

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        // Simulate a connectivity / timeout error.
        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest,
        ).apply {
            insertResult = Result.failure(ConnectTimeoutException("network"))
        }

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, table ->
                when (table) {
                    "assets" -> assetsWrapper
                    else -> error("Unexpected table: $table")
                }
            }
        )

        // When
        val synced = syncer.syncTable(table = "assets")

        // Then - nothing is synced; the mutation remains pending.
        assertEquals(0, synced)
        assertEquals(1, store.pendingMutationCount())
    }

    @Test
    fun syncTable_unknownServerError_propagatesAndKeepsMutation() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val storageDao = FakeStorageDao()
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(
            objectDao = objectDao,
            storageDao = storageDao,
            mutationDao = mutationDao,
        )

        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert("assets", body)

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        // Unknown / application error: neither network nor permanent.
        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest,
        ).apply {
            insertResult = Result.failure(IllegalStateException("server error"))
        }

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, table ->
                when (table) {
                    "assets" -> assetsWrapper
                    else -> error("Unexpected table: $table")
                }
            }
        )

        // When
        var thrown: Throwable? = null
        try {
            syncer.syncTable(table = "assets")
        } catch (t: Throwable) {
            thrown = t
        }

        // Then - mutation is still pending and the error surfaced.
        assertEquals(1, store.pendingMutationCount())
        assertNotNull(thrown)
    }

    @Test
    fun syncAllTables_unknownErrorOnOneTable_doesNotStopOtherTables() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val storageDao = FakeStorageDao()
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(
            objectDao = objectDao,
            storageDao = storageDao,
            mutationDao = mutationDao,
        )

        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }

        // Enqueue one mutation for each of two tables.
        store.enqueueInsert("assets", body)
        store.enqueueInsert("other", body)

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)

        val assetsWrapper = FakePostgrestQueryBuilderWrapper(
            table = "assets",
            postgrest = postgrest,
        ).apply {
            insertResult = Result.failure(IllegalStateException("server error"))
        }
        val otherWrapper = FakePostgrestQueryBuilderWrapper(
            table = "other",
            postgrest = postgrest,
        )

        val seenTables = mutableListOf<String>()

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            postgrestFactory = { _, table ->
                seenTables += table
                when (table) {
                    "assets" -> assetsWrapper
                    "other" -> otherWrapper
                    else -> error("Unexpected table: $table")
                }
            }
        )

        // When - syncAllTables should swallow table-level failures and
        // continue with remaining tables.
        val synced = syncer.syncAllTables()

        // Then - one mutation was successfully synced (from the second table).
        // The first table failed, but the factory was invoked for *both* tables,
        // proving that failure in the first did not prevent the second from running.
        assertEquals(1, synced)
        assertEquals(listOf("assets", "other"), seenTables)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Logging assertion tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun syncTable_success_logsInfoStartAndComplete() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newTestOfflineStore()
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert("assets", body)
        store.enqueueInsert("assets", body)

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val wrapper = FakePostgrestQueryBuilderWrapper(table = "assets", postgrest = postgrest)

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            sdkLogger = capturing.asSdkLogger(),
            postgrestFactory = { _, _ -> wrapper }
        )

        // When
        syncer.syncTable(table = "assets")

        // Then
        val infos = capturing.entriesAt(OfflineLogLevel.INFO)
        assertEquals(2, infos.size)
        assertEquals(true, infos[0].message.contains("Syncing 2 pending mutations for assets"))
        assertEquals(true, infos[1].message.contains("Sync complete: 2 mutations synced for assets"))
    }

    @Test
    fun syncTable_success_logsDebugPerMutation() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newTestOfflineStore()
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert("assets", body)

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val wrapper = FakePostgrestQueryBuilderWrapper(table = "assets", postgrest = postgrest)

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            sdkLogger = capturing.asSdkLogger(),
            postgrestFactory = { _, _ -> wrapper }
        )

        // When
        syncer.syncTable(table = "assets")

        // Then
        val debugEntries = capturing.entriesAt(OfflineLogLevel.DEBUG)
        assertEquals(1, debugEntries.size)
        assertEquals(true, debugEntries[0].message.contains("Synced INSERT mutation for assets"))
    }

    @Test
    fun syncTable_permanentFailure_logsWarning() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newTestOfflineStore()
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert("assets", body)

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val response = makeFakeHttpResponse()
        val wrapper = FakePostgrestQueryBuilderWrapper(table = "assets", postgrest = postgrest).apply {
            insertResult = Result.failure(
                PostgrestRestException(
                    message = "conflict",
                    hint = null,
                    details = null,
                    code = "409",
                    response = response
                )
            )
        }

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            sdkLogger = capturing.asSdkLogger(),
            postgrestFactory = { _, _ -> wrapper }
        )

        // When
        syncer.syncTable(table = "assets")

        // Then
        val warnings = capturing.entriesAt(OfflineLogLevel.WARNING)
        assertEquals(true, warnings.any {
            it.message.contains("Permanent failure for INSERT on assets, dropping mutation")
        })
    }

    @Test
    fun syncTable_networkFailure_logsWarningPaused() = runTest {
        // Given
        val capturing = CapturingLogger()
        val store = newTestOfflineStore()
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "1") })
        }
        store.enqueueInsert("assets", body)

        val postgrest = FakePostgrest()
        val supabase = FakeSupabaseClient(postgrest)
        val wrapper = FakePostgrestQueryBuilderWrapper(table = "assets", postgrest = postgrest).apply {
            insertResult = Result.failure(ConnectTimeoutException("network"))
        }

        val syncer = OfflineMutationSyncer(
            store = store,
            supabase = supabase,
            sdkLogger = capturing.asSdkLogger(),
            postgrestFactory = { _, _ -> wrapper }
        )

        // When
        syncer.syncTable(table = "assets")

        // Then
        val warnings = capturing.entriesAt(OfflineLogLevel.WARNING)
        assertEquals(true, warnings.any {
            it.message.contains("Network unavailable, pausing sync for assets")
        })
    }
}