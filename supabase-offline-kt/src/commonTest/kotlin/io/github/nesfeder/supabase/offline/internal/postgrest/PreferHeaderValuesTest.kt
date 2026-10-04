// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class, SupabaseInternal::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
import io.github.nesfeder.supabase.offline.testdoubles.assertNotQueueable
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.client.request.header
import io.ktor.client.request.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * A dry run is a write whose `Prefer` header carries `tx=rollback`, wherever it carries it:
 * supabase-kt writes its own `Prefer` first and appends one a caller adds, so the check reads every
 * value, in every header, not the first. On supabase-kt 3.6, which has no `dryRun()`, a header the
 * caller adds is the only way to write a dry run.
 */
class PreferHeaderValuesTest {

    private val server = RecordingPostgrestServer()

    @Test
    fun a_rollback_in_a_second_prefer_header_is_a_dry_run() = runTest {
        // Given / When
        val capture = preferOfAWriteWith("return=minimal", "tx=rollback")

        // Then
        assertTrue(capture.requestedRollback, "read ${capture.prefer}")
    }

    @Test
    fun a_rollback_comma_joined_into_one_prefer_header_is_a_dry_run() = runTest {
        // Given / When
        val capture = preferOfAWriteWith("return=minimal, tx=rollback")

        // Then
        assertTrue(capture.requestedRollback, "read ${capture.prefer}")
    }

    @Test
    fun a_rollback_in_the_first_prefer_header_is_a_dry_run() = runTest {
        // Given / When
        val capture = preferOfAWriteWith("tx=rollback", "return=minimal")

        // Then
        assertTrue(capture.requestedRollback, "read ${capture.prefer}")
    }

    @Test
    fun a_write_without_a_rollback_in_any_prefer_header_is_not_a_dry_run() = runTest {
        // Given / When
        val capture = preferOfAWriteWith("return=minimal", "count=exact, missing=default")

        // Then
        assertEquals(false, capture.requestedRollback, "read ${capture.prefer}")
    }

    @Test
    fun an_offline_write_that_adds_prefer_tx_rollback_itself_is_refused_and_never_queued() =
        runTest {
            // Given — offline, and a write whose caller adds the rollback preference itself
            val offline = RecordingPostgrestServer(answer = { connectivityFailure() })
            val mutationDao = FakeMutationDao()
            val builder =
                newTestOfflineTable(
                    delegate = offline.supabaseClient().postgrest.from(TABLE),
                    store = newTestStore(mutationDao = mutationDao),
                )

            // When / Then — not queued, so it can never replay as a real write
            assertNotQueueable<HttpRequestException> {
                builder.update(
                    Json.parseToJsonElement("""{"title":"never committed"}"""),
                    MutationStrategy.QueueIfOffline,
                ) {
                    filter { eq("id", 1) }
                    headers.append(PREFER, "tx=rollback")
                }
            }
            assertTrue(mutationDao.inserted.isEmpty())
        }

    /**
     * Sends a PostgREST write carrying [preferHeaders], one `Prefer` header each, under a capture,
     * and returns what the dry-run check recorded.
     */
    private suspend fun preferOfAWriteWith(vararg preferHeaders: String): PreferHeaderCapture {
        val supabase = server.supabaseClient()
        supabase.installDryRunCheck()
        val capture = PreferHeaderCapture()
        runCatching {
            withContext(capture) {
                supabase.httpClient.httpClient.post("$TEST_SUPABASE_URL/rest/v1/$TABLE") {
                    preferHeaders.forEach { value -> header(PREFER, value) }
                }
            }
        }
        assertTrue(capture.hasObservedWrite, "the check did not see the write")
        assertTrue(server.requests.isEmpty(), "the check let the write out")
        return capture
    }

    private companion object {
        const val TABLE = "todos"
        const val PREFER = "Prefer"
    }
}
