package io.github.nesfeder.supabase.internal.db

import io.github.nesfeder.supabase.internal.db.OfflineMutationOverlay.applyPendingMutations
import io.github.nesfeder.supabase.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.internal.postgrest.FilterEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineMutationOverlayTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun applyPendingMutations_noPending_returnsServerJson() {
        // Given
        val serverJson = """[{"id":"1"}]"""
        val pending = emptyList<MutationRow>()

        // When
        val result = applyPendingMutations(json, serverJson, pending)

        // Then
        assertEquals(serverJson, result)
    }

    @Test
    fun applyInsertMutation_appendsRows() = runTest {
        // Given
        val body: JsonArray = buildJsonArray {
            add(buildJsonObject { put("id", "2") })
            add(buildJsonObject { put("id", "3") })
        }

        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(
            objectDao = FakeObjectDao(),
            storageDao = FakeStorageDao(),
            mutationDao = mutationDao,
        )

        // We use enqueueInsert to construct MutationRow correctly
        store.enqueueInsert(table = "assets", body = body, filter = null)
        val pending = mutationDao.pendingForTable("assets")
        val serverJson = """[{"id":"1"}]"""

        // When
        val result = applyPendingMutations(json, serverJson, pending)

        // Then
        val arr = json.parseToJsonElement(result) as JsonArray
        val ids = arr.map { it as JsonObject }.map { it["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("1", "2", "3"), ids)
    }

    @Test
    fun applyUpsertMutation_mergesOrInserts() = runTest {
        // Given
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(
            objectDao = FakeObjectDao(),
            storageDao = FakeStorageDao(),
            mutationDao = mutationDao,
        )

        val upsertBody = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "1")
                    put("name", "changed")
                }
            )
        }

        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                FilterDescriptor.Condition(
                    column = "id",
                    operatorId = "eq",
                    value = "1"
                )
            )
        )

        store.enqueueUpsert(
            table = "assets",
            bodyJson = upsertBody,
            filter = descriptor,
            onConflict = "id",
            ignoreDuplicates = false,
            defaultToNull = true
        )

        val pending = mutationDao.pendingForTable("assets")
        val serverJson = """[{"id":"1","name":"original"}]"""

        // When
        val result = applyPendingMutations(json, serverJson, pending)

        // Then
        val arr = json.parseToJsonElement(result) as JsonArray
        val obj = arr[0] as JsonObject
        assertEquals("1", obj["id"]!!.jsonPrimitive.content)
        assertEquals("changed", obj["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun applyUpdateMutation_patchesMatchingRows() = runTest {
        // Given
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(
            objectDao = FakeObjectDao(),
            storageDao = FakeStorageDao(),
            mutationDao = mutationDao,
        )

        val patchBody: JsonElement = buildJsonObject {
            put("status", "active")
        }

        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                FilterDescriptor.Condition(
                    column = "id",
                    operatorId = "eq",
                    value = "1"
                )
            )
        )

        store.enqueueUpdate(
            table = "assets",
            body = patchBody,
            filter = descriptor
        )

        val pending = mutationDao.pendingForTable("assets")
        val serverJson = """[{"id":"1","status":"old"},{"id":"2","status":"old"}]"""

        // When
        val result = applyPendingMutations(json, serverJson, pending)

        // Then
        val arr = json.parseToJsonElement(result) as JsonArray
        val statuses = arr.map { it as JsonObject }.map { it["status"]!!.jsonPrimitive.content }
        assertEquals(listOf("active", "old"), statuses)
    }

    @Test
    fun applyDeleteMutation_removesMatchingRows() = runTest {
        // Given
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(
            objectDao = FakeObjectDao(),
            storageDao = FakeStorageDao(),
            mutationDao = mutationDao,
        )

        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                FilterDescriptor.Condition(
                    column = "id",
                    operatorId = "eq",
                    value = "1"
                )
            )
        )

        store.enqueueDelete(table = "assets", filter = descriptor)
        val pending = mutationDao.pendingForTable("assets")
        val serverJson = """[{"id":"1"},{"id":"2"}]"""

        // When
        val result = applyPendingMutations(json, serverJson, pending)

        // Then
        val arr = json.parseToJsonElement(result) as JsonArray
        val ids = arr.map { it as JsonObject }.map { it["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("2"), ids)
    }

    @Test
    fun invalidServerJson_fallsBackToOriginal() = runTest {
        // Given
        val serverJson = """{"not":"an array"}""" // not a JsonArray
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(
            objectDao = FakeObjectDao(),
            storageDao = FakeStorageDao(),
            mutationDao = mutationDao,
        )
        val body: JsonArray = buildJsonArray { add(buildJsonObject { put("id", "1") }) }
        store.enqueueInsert("assets", body)
        val pending = mutationDao.pendingForTable("assets")

        // When
        val result = applyPendingMutations(json, serverJson, pending)

        // Then
        assertEquals(serverJson, result)
    }

    @Test
    fun applyDeleteMutation_noMatches_rowsRemainUnchanged() {
        // Given
        val server = buildJsonArray {
            add(buildJsonObject { put("id", 1); put("name", "A") })
            add(buildJsonObject { put("id", 2); put("name", "B") })
        }.toString()

        val filter = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                FilterDescriptor.Condition("id", operatorId = "eq", value = "999")
            )
        )

        val pending = listOf(
            MutationRow(
                id = 1L,
                table = "assets",
                operation = MutationOperation.DELETE,
                bodyJson = "{}",
                filterJson = FilterEngine.encode(filter, json),
                createdAtMs = 0L,
                syncedAtMs = null,
                defaultToNull = null,
                onConflict = null,
                ignoreDuplicates = null,
            )
        )

        // When
        val result = applyPendingMutations(json, server, pending)

        // Then
        assertEquals(server, result)
    }
}