// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.modules.SerializersModule

/**
 * The typed writes encode through the app's own serializer, `postgrest.serializer`, so they send
 * the bytes supabase-kt sends. The app's serializer here differs from the default twice over: it
 * encodes defaults, and it knows a contextual serializer the library has never heard of.
 */
class TypedWritesTest {

    /** A value only the app's serializer can encode: through [DueAsText], as `"t+<seconds>"`. */
    private data class Due(val epochSeconds: Long)

    /** The app's contextual serializer for [Due]. */
    private object DueAsText : KSerializer<Due> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("TypedWritesTest.Due", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Due) {
            encoder.encodeString("t+${value.epochSeconds}")
        }

        override fun deserialize(decoder: Decoder): Due =
            Due(decoder.decodeString().removePrefix("t+").toLong())
    }

    @Serializable
    private data class Note(val id: String, @Contextual val due: Due, val pinned: Boolean = false)

    /** The app's JSON: defaults encoded, and [DueAsText] for [Due]. */
    private val appJson = Json {
        encodeDefaults = true
        serializersModule = SerializersModule { contextual(Due::class, DueAsText) }
    }

    private val note = Note(id = "n1", due = Due(42))

    private val notes = listOf(note, Note(id = "n2", due = Due(43), pinned = true))

    private fun appClient(server: RecordingPostgrestServer): SupabaseClient =
        newTestSupabaseClient(engine = server.engine) {
            install(Postgrest) {
                maxRetries = 0
                serializer = KotlinXSerializer(appJson)
            }
        }

    @Test
    fun a_typed_insert_of_one_row_sends_what_supabase_kt_sends() = runTest {
        // Given — a client whose PostgREST serializer is the app's own, online
        val server = RecordingPostgrestServer()
        val supabase = appClient(server)
        val offline = newTestOfflineClient(supabase = supabase)

        // When — the same write through supabase-kt, then through the offline builder
        supabase.from(TABLE).insert(note)
        val outcome = offline.from(TABLE).insert(note)

        // Then — the same bytes, the app's encoding in them, and the server applied it
        val (upstream, ours) = server.requests.map { it.body }
        assertEquals(upstream, ours)
        assertEquals("""[{"id":"n1","due":"t+42","pinned":false}]""", ours)
        assertIs<MutationOutcome.Applied>(outcome)
        offline.close()
    }

    @Test
    fun a_typed_insert_of_a_list_sends_what_supabase_kt_sends() = runTest {
        // Given
        val server = RecordingPostgrestServer()
        val supabase = appClient(server)
        val offline = newTestOfflineClient(supabase = supabase)

        // When
        supabase.from(TABLE).insert(notes)
        offline.from(TABLE).insert(notes)

        // Then
        val (upstream, ours) = server.requests.map { it.body }
        assertEquals(upstream, ours)
        offline.close()
    }

    @Test
    fun a_typed_upsert_of_one_row_and_of_a_list_sends_what_supabase_kt_sends() = runTest {
        // Given
        val server = RecordingPostgrestServer()
        val supabase = appClient(server)
        val offline = newTestOfflineClient(supabase = supabase)

        // When
        supabase.from(TABLE).upsert(note) { onConflict = "id" }
        offline.from(TABLE).upsert(note) { onConflict = "id" }
        supabase.from(TABLE).upsert(notes)
        offline.from(TABLE).upsert(notes)

        // Then
        val bodies = server.requests.map { it.body }
        assertEquals(bodies[0], bodies[1])
        assertEquals(bodies[2], bodies[3])
        assertEquals("""[{"id":"n1","due":"t+42","pinned":false}]""", bodies[1])
        offline.close()
    }

    @Test
    fun a_typed_update_and_the_update_dsl_send_what_supabase_kt_sends() = runTest {
        // Given
        val server = RecordingPostgrestServer()
        val supabase = appClient(server)
        val offline = newTestOfflineClient(supabase = supabase)

        // When — a typed update, then the DSL with a value only the app's serializer encodes
        supabase.from(TABLE).update(note) { filter { eq("id", "n1") } }
        offline.from(TABLE).update(note) { filter { eq("id", "n1") } }
        supabase.from(TABLE).update({ set("due", Due(7)) }) { filter { eq("id", "n1") } }
        offline.from(TABLE).update({ set("due", Due(7)) }) { filter { eq("id", "n1") } }

        // Then
        val requests = server.requests
        assertEquals(requests[0].body, requests[1].body)
        assertEquals(requests[0].query, requests[1].query)
        assertEquals(requests[2].body, requests[3].body)
        assertEquals("""{"due":"t+7"}""", requests[3].body)
        offline.close()
    }

    @Test
    fun a_queued_typed_write_keeps_the_app_encoding_for_its_replay() = runTest {
        // Given — offline, so the write is queued
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val offline =
            newTestOfflineClient(
                supabase = appClient(server),
                coordinatorScope = idleCoroutineScope(),
            )

        // When
        val outcome = offline.from(TABLE).insert(note)

        // Then — the queued write holds the body supabase-kt would have sent
        val queued = assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(
            appJson.parseToJsonElement("""[{"id":"n1","due":"t+42","pinned":false}]"""),
            queued.pendingMutation.body,
        )
        assertIs<JsonArray>(queued.pendingMutation.body)
    }

    private companion object {
        const val TABLE = "notes"
    }
}
