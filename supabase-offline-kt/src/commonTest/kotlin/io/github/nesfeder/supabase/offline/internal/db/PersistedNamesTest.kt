// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Every name and key the mutation queue writes to disk, as exact literals. Once an app has written
 * them they are frozen: a later build reads them back from devices that ran this one. A rename of a
 * constant or a property must fail here rather than pass as a refactor.
 */
class PersistedNamesTest {

    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao, nowMillis = { NOW })

    @Test
    fun the_reserved_table_and_its_signatures_are_these() {
        assertEquals(
            "__supabase_offline_kt__.reserved_engine_state.this_name_is_longer_than_any_postgres_identifier",
            RESERVED_TABLE_NAME,
        )
        // Postgres truncates identifiers to NAMEDATALEN - 1 = 63 bytes, so no table has this name.
        assertTrue(RESERVED_TABLE_NAME.encodeToByteArray().size > 63)
        assertEquals("rejected_mutations", REJECTED_MUTATIONS_SIGNATURE)
        assertEquals("mutation_owners", MUTATION_OWNERS_SIGNATURE)
        assertEquals("synced_mutation_coverage", SYNCED_MUTATION_COVERAGE_SIGNATURE)
        assertEquals("unconfirmed_sends", UNCONFIRMED_SENDS_SIGNATURE)
    }

    @Test
    fun the_stored_operation_names_are_these() {
        assertEquals(
            listOf("INSERT", "UPSERT", "UPDATE", "DELETE"),
            listOf(
                MutationOperation.INSERT,
                MutationOperation.UPSERT,
                MutationOperation.UPDATE,
                MutationOperation.DELETE,
            ),
        )
    }

    @Test
    fun a_rejected_list_is_written_with_these_keys() = runTest {
        // Given / When — an entry with every field set
        store.mutationQueueState.addRejection(
            StoredRejection(
                id = 7,
                table = "todos",
                operation = MutationOperation.UPDATE,
                bodyJson = "{}",
                filterJson = "f",
                createdAtMs = 1,
                defaultToNull = false,
                onConflict = "id",
                ignoreDuplicates = true,
                deltaJson = "d",
                owner = "user:user-a",
                rejectedAtMs = 2,
                message = "refused",
                serverError =
                    StoredServerError(
                        status = 409,
                        code = "23505",
                        message = "duplicate",
                        details = "x",
                        hint = "y",
                    ),
                bodyOmittedChars = 5,
            )
        )

        // Then
        assertEquals(
            """{"version":1,"entries":[{"id":7,"table":"todos","operation":"UPDATE","bodyJson":"{}",""" +
                """"filterJson":"f","createdAtMs":1,"defaultToNull":false,"onConflict":"id",""" +
                """"ignoreDuplicates":true,"deltaJson":"d","owner":"user:user-a","rejectedAtMs":2,""" +
                """"message":"refused","serverError":{"status":409,"code":"23505","message":"duplicate",""" +
                """"details":"x","hint":"y"},"bodyOmittedChars":5}]}""",
            assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE))
                .payload,
        )
    }

    @Test
    fun the_count_of_dropped_rejections_is_written_under_this_key() = runTest {
        // Given / When — one more rejection than the list keeps
        repeat(MAX_REJECTED_MUTATIONS + 1) { index ->
            store.mutationQueueState.addRejection(
                StoredRejection(
                    id = index + 1L,
                    table = "t",
                    operation = "INSERT",
                    bodyJson = "[]",
                    createdAtMs = 0,
                    rejectedAtMs = 0,
                    message = "m",
                )
            )
        }

        // Then
        val payload =
            assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE))
                .payload
        assertTrue(payload.endsWith("""],"droppedCount":1}"""), payload.takeLast(40))
    }

    @Test
    fun the_owner_records_are_written_with_these_keys_and_values() = runTest {
        // Given / When — one write by a user, one by nobody signed in
        store.enqueueOwnedBy(QueueIdentity.User("user-a").storedValue) {
            enqueueDelete(table = "todos", filter = null)
        }
        store.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueDelete(table = "todos", filter = null)
        }

        // Then
        assertEquals(
            """{"version":1,"owners":{"1":"user:user-a","2":"anonymous"}}""",
            assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, MUTATION_OWNERS_SIGNATURE)).payload,
        )
    }

    @Test
    fun synced_mutation_coverage_is_written_with_these_keys() = runTest {
        // Given — a replayed mutation, then a network response for a cached query of its table
        store.enqueueUpdate(
            table = "todos",
            body = Json.parseToJsonElement("""{"title":"x"}"""),
            filter = null,
        )
        store.markMutationSynced(id = 1, table = "todos")

        // When
        store.addPayload(
            "todos",
            "todos?id=eq.1",
            """[{"id":1}]""",
            requestStartedAtTick = store.monotonicTickNow(),
        )

        // Then
        assertEquals(
            """{"version":1,"coveredSignatures":{"1":["todos?id=eq.1"]}}""",
            assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, SYNCED_MUTATION_COVERAGE_SIGNATURE))
                .payload,
        )
    }

    @Test
    fun unconfirmed_sends_are_written_with_these_keys() = runTest {
        // Given — a keyed INSERT whose send is starting
        store.enqueueInsert(
            table = "todos",
            body = Json.parseToJsonElement("""[{"id":"t1"}]""") as JsonArray,
        )

        // When
        store.markSendUnconfirmed(1)

        // Then
        assertEquals(
            """{"version":1,"mutationIds":[1]}""",
            assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, UNCONFIRMED_SENDS_SIGNATURE))
                .payload,
        )
    }

    @Test
    fun an_unreadable_rejected_list_is_kept_aside_under_this_key() = runTest {
        // Given
        objectDao.seedPayload(RESERVED_TABLE_NAME, REJECTED_MUTATIONS_SIGNATURE, "not a list")

        // When
        store.mutationQueueState.rejections()

        // Then
        assertEquals(
            "not a list",
            objectDao.getRow(RESERVED_TABLE_NAME, "rejected_mutations.unreadable.$NOW")?.payload,
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
