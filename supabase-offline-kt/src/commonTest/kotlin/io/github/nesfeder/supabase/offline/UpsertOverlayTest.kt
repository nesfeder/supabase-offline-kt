// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.mutationqueue.DEFAULT_PRIMARY_KEY
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Every row of an upsert reaches the cached queries once: matched by the conflict columns, or by
 * the table's primary key when none are given, online and queued, and still once after a restart.
 * The primary key is `id` unless the client is told otherwise for a table, composite keys included.
 */
class UpsertOverlayTest {

    private val server = FakeRelationalPostgrestServer()
    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()

    private var client: Offline = newClient()

    private fun newClient(primaryKeys: PrimaryKeys = DefaultPrimaryKeys): Offline =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
            coordinatorScope = idleCoroutineScope(),
            primaryKeys = primaryKeys,
        )

    /** A new client over the same database, as after the app restarts. */
    private fun restart(primaryKeys: PrimaryKeys = DefaultPrimaryKeys) {
        client = newClient(primaryKeys)
    }

    private suspend fun cached(table: String): List<JsonObject> =
        rows(client.from(table).select(readStrategy = ReadStrategy.CacheOnly()).data)

    private suspend fun cache(table: String) {
        client.from(table).select(readStrategy = ReadStrategy.NetworkOnly)
    }

    private fun goOffline() {
        server.recorder.answer = { connectivityFailure() }
    }

    @Test
    fun a_multi_row_online_upsert_lands_every_row_once() = runTest {
        // Given
        server.seed(TABLE, row("""{"id":"1","v":1}"""), row("""{"id":"2","v":1}"""))
        cache(TABLE)

        // When
        client.from(TABLE).upsert(
            rows("""[{"id":"1","v":2},{"id":"2","v":2},{"id":"3","v":2}]""").let(::JsonArray)
        ) {
            onConflict = "id"
        }

        // Then
        val expected = rows("""[{"id":"1","v":2},{"id":"2","v":2},{"id":"3","v":2}]""")
        assertEquals(expected, cached(TABLE))
        restart()
        assertEquals(expected, cached(TABLE))
    }

    @Test
    fun an_online_upsert_without_conflict_columns_merges_by_primary_key() = runTest {
        // Given
        server.seed(TABLE, row("""{"id":"1","v":1,"title":"kept"}"""))
        cache(TABLE)

        // When
        client.from(TABLE).upsert(rows("""[{"id":"1","v":2}]""").let(::JsonArray))

        // Then
        assertEquals(rows("""[{"id":"1","v":2,"title":"kept"}]"""), cached(TABLE))
    }

    @Test
    fun a_queued_multi_row_upsert_shows_every_row_once_across_a_restart() = runTest {
        // Given
        server.seed(TABLE, row("""{"id":"1","v":1}"""), row("""{"id":"2","v":1}"""))
        cache(TABLE)
        goOffline()

        // When
        client.from(TABLE).upsert(
            rows("""[{"id":"1","v":2},{"id":"3","v":2}]""").let(::JsonArray)
        ) {
            onConflict = "id"
        }

        // Then
        val expected = rows("""[{"id":"1","v":2},{"id":"2","v":1},{"id":"3","v":2}]""")
        assertEquals(expected, cached(TABLE))
        restart()
        assertEquals(expected, cached(TABLE))
    }

    @Test
    fun a_queued_upsert_on_a_unique_column_merges_into_the_row_holding_it() = runTest {
        // Given — rows are unique on email; the upsert body carries no id
        server.seed(TABLE, row("""{"id":"1","email":"a@example.com","v":1}"""))
        cache(TABLE)
        goOffline()

        // When
        client.from(TABLE).upsert(rows("""[{"email":"a@example.com","v":2}]""").let(::JsonArray)) {
            onConflict = "email"
        }

        // Then
        val expected = rows("""[{"id":"1","email":"a@example.com","v":2}]""")
        assertEquals(expected, cached(TABLE))
        restart()
        assertEquals(expected, cached(TABLE))
    }

    @Test
    fun a_queued_upsert_on_a_composite_key_table_merges_by_that_key() = runTest {
        // Given — memberships are keyed on (team_id, member_id) and have no id column
        client = newClient(MEMBERSHIP_KEYS)
        server.seed(MEMBERSHIPS, row("""{"team_id":"t","member_id":"m","role":"viewer"}"""))
        cache(MEMBERSHIPS)
        goOffline()

        // When
        client
            .from(MEMBERSHIPS)
            .upsert(rows("""[{"team_id":"t","member_id":"m","role":"editor"}]""").let(::JsonArray))

        // Then
        val expected = rows("""[{"team_id":"t","member_id":"m","role":"editor"}]""")
        assertEquals(expected, cached(MEMBERSHIPS))
        restart(MEMBERSHIP_KEYS)
        assertEquals(expected, cached(MEMBERSHIPS))
    }

    @Test
    fun a_replayed_insert_on_a_composite_key_table_is_neither_lost_nor_doubled() = runTest {
        // Given — the rows carry an id that is not unique: it names the team
        client = newClient(MEMBERSHIP_KEYS)
        server.seed(MEMBERSHIPS, row("""{"id":"t","team_id":"t","member_id":"m1"}"""))
        cache(MEMBERSHIPS)
        val online = server.recorder.answer
        goOffline()
        client
            .from(MEMBERSHIPS)
            .insert(rows("""[{"id":"t","team_id":"t","member_id":"m2"}]""").let(::JsonArray))

        // When — it replays, and the cached query has not refreshed since
        server.recorder.answer = online
        client.syncPendingMutations().syncedCount

        // Then
        val expected =
            rows(
                """[{"id":"t","team_id":"t","member_id":"m1"},{"id":"t","team_id":"t","member_id":"m2"}]"""
            )
        assertEquals(expected, cached(MEMBERSHIPS))
        restart(MEMBERSHIP_KEYS)
        assertEquals(expected, cached(MEMBERSHIPS))
    }

    private fun row(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun rows(json: String): List<JsonObject> =
        (Json.parseToJsonElement(json) as JsonArray).map { it.jsonObject }

    private companion object {
        const val TABLE = "contacts"
        const val MEMBERSHIPS = "memberships"
        val MEMBERSHIP_KEYS = PrimaryKeys { table ->
            if (table == MEMBERSHIPS) listOf("team_id", "member_id") else DEFAULT_PRIMARY_KEY
        }
    }
}
