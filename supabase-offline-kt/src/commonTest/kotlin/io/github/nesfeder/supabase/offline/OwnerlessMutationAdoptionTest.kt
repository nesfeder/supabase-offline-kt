// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.enqueueUpdate
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Mutations an earlier build queued carry no owner. That build, with the Auth plugin installed,
 * replayed only while signed in, so they belong to a signed-in user: they are adopted by the first
 * signed-in pass and never by a signed-out one, which would send them on the anon key. Without the
 * Auth plugin there is only the anonymous identity, and it adopts them.
 */
class OwnerlessMutationAdoptionTest {

    /** The queue's table an earlier build left its mutations in, and the client then opens. */
    private val mutationDao = FakeMutationDao()

    /** The earlier build's components over [mutationDao]. */
    private val store = newTestStore(mutationDao = mutationDao)
    private val server = RecordingPostgrestServer()

    @Test
    fun a_signed_out_pass_neither_adopts_nor_sends_an_earlier_builds_mutations() = runTest {
        // Given — a mutation with no owner, and the Auth plugin installed with nobody signed in
        store.mutationQueue.enqueueUpdate(
            TABLE,
            Json.parseToJsonElement("""{"title":"from the old build"}"""),
            byId("1"),
        )
        val client =
            newTestOfflineClient(
                supabase = newTestSupabaseClientWithAuth(server.engine),
                mutationDao = mutationDao,
                coordinatorScope = idleCoroutineScope(),
            )

        // When
        val signedOut = client.syncPendingMutationsReport()

        // Then — untouched and unsent
        assertEquals(0, signedOut.syncedCount)
        assertEquals(emptyList(), server.requests.map { it.body })
        assertEquals(listOf(null), store.mutationQueue.allPendingMutations().map { it.owner })
    }

    @Test
    fun the_first_signed_in_pass_adopts_and_sends_an_earlier_builds_mutations() = runTest {
        // Given — as above, then a user signs in
        store.mutationQueue.enqueueUpdate(
            TABLE,
            Json.parseToJsonElement("""{"title":"from the old build"}"""),
            byId("1"),
        )
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        val client =
            newTestOfflineClient(
                supabase = supabase,
                mutationDao = mutationDao,
                coordinatorScope = idleCoroutineScope(),
            )
        client.syncPendingMutationsReport()
        supabase.signInForTest("user-a")

        // When
        val signedIn = client.syncPendingMutationsReport()

        // Then
        assertEquals(1, signedIn.syncedCount)
        assertEquals(listOf("""{"title":"from the old build"}"""), server.requests.map { it.body })
    }

    @Test
    fun without_the_auth_plugin_the_anonymous_identity_adopts_them() = runTest {
        // Given — no Auth plugin: every write is anonymous
        store.mutationQueue.enqueueUpdate(
            TABLE,
            Json.parseToJsonElement("""{"title":"from the old build"}"""),
            byId("1"),
        )
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                mutationDao = mutationDao,
                coordinatorScope = idleCoroutineScope(),
            )

        // When
        val report = client.syncPendingMutationsReport()

        // Then
        assertEquals(1, report.syncedCount)
    }

    private fun byId(id: String): FilterDescriptor =
        FilterDescriptor(
            conditions =
                listOf(FilterDescriptor.Condition(column = "id", operatorId = "eq", value = id))
        )

    private companion object {
        const val TABLE = "tasks"
    }
}
