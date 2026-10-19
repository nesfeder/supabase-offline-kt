// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.offline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `install(Offline) { inMemory() }`: the plugin behaves as it does over a database for the things a
 * consumer's tests lean on, and it sees the time the test's clock gives it.
 */
class InMemoryTest {

    private val server = ScriptedServer()

    @Test
    fun a_row_read_online_is_served_from_memory_after_the_network_drops() = runTest {
        // Given — one read while online, which the client caches
        val supabase = server.supabaseClient { inMemory() }
        supabase.offline.from(TABLE).select()

        // When — the same read after the network drops
        server.online = false
        val result = supabase.offline.from(TABLE).select()

        // Then — the cached row comes back instead of the failure
        assertEquals(SERVER_ROWS, result.data)
        supabase.close()
    }

    @Test
    fun a_write_made_offline_is_queued_in_memory() = runTest {
        // Given — a client whose network is down
        server.online = false
        val supabase = server.supabaseClient { inMemory() }

        // When
        val outcome =
            supabase.offline
                .from(TABLE)
                .insert(JsonArray(listOf(buildJsonObject { put("title", "Buy milk") })))

        // Then — the write is queued rather than lost
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, supabase.offline.pendingMutationCount())
        supabase.close()
    }

    @Test
    fun the_network_reads_as_unknown_until_the_test_switches_it_off() = runTest {
        // Given — a row cached while the network is on
        val supabase = server.supabaseClient { inMemory() }
        supabase.offline.from(TABLE).select()
        val before = supabase.offline.syncStatus.value.network

        // When — the test switches the library's network access off
        supabase.offline.disableNetwork()
        val read = supabase.offline.from(TABLE).select()
        val write =
            supabase.offline
                .from(TABLE)
                .insert(JsonArray(listOf(buildJsonObject { put("title", "Tea") })))

        // Then — the read is the cached row, the write waits, and the status says why
        assertEquals(NetworkStatus.UNKNOWN, before)
        assertEquals(NetworkStatus.DISABLED, supabase.offline.syncStatus.value.network)
        assertEquals(SERVER_ROWS, read.data)
        assertIs<MutationOutcome.Queued>(write)
        supabase.close()
    }

    @Test
    fun a_cached_read_ages_by_the_clock_the_test_gives() = runTest {
        // Given — a row cached at the clock's time, then the network gone
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        val supabase = server.supabaseClient { inMemory(clock = clock) }
        supabase.offline.from(TABLE).select()
        server.online = false
        val fiveMinutes = ReadStrategy.CacheOnly(maxAge = 5.minutes)

        // When — the clock moves past the read's maxAge
        val fresh = supabase.offline.from(TABLE).select(readStrategy = fiveMinutes)
        clock.now = clock.now + 6.minutes

        // Then — the same read now finds the row too old
        assertEquals(SERVER_ROWS, fresh.data)
        assertFailsWith<CacheMissException> {
            supabase.offline.from(TABLE).select(readStrategy = fiveMinutes)
        }
        supabase.close()
    }

    @Test
    fun a_queued_write_and_a_sync_are_stamped_by_the_clock_the_test_gives() = runTest {
        // Given — a client offline, on the test's clock
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        server.online = false
        val supabase = server.supabaseClient { inMemory(clock = clock) }

        // When — a write is queued, then sent a minute later
        supabase.offline
            .from(TABLE)
            .insert(JsonArray(listOf(buildJsonObject { put("title", "Buy milk") })))
        val queuedAt = supabase.offline.pendingMutations().first().single().createdAt
        clock.now = clock.now + 1.minutes
        server.online = true
        val report = supabase.offline.syncPendingMutations()
        val syncedAt = supabase.offline.syncStatus.first { it.lastSyncedAt != null }.lastSyncedAt

        // Then — both times are the clock's, not the device's
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), queuedAt)
        assertEquals(1, report.syncedCount)
        assertEquals(Instant.parse("2026-01-01T00:01:00Z"), syncedAt)
        supabase.close()
    }

    @Test
    fun a_table_with_a_version_column_records_a_base_and_replays_against_it() = runTest {
        // Given — a row read at a version, and the server's answer to the guarded write
        val guardedServer =
            ScriptedServer(
                rows = """[{"id":1,"title":"Buy milk","updated_at":"$BASE"}]""",
                writtenRows = """[{"id":1,"title":"Oat milk","updated_at":"$NEXT"}]""",
            )
        val supabase = guardedServer.supabaseClient {
            inMemory()
            autoSync = false
            versionColumn(TABLE, "updated_at")
        }
        supabase.offline.from(TABLE).select()

        // When — the row is renamed offline, then the queue replays
        supabase.offline.disableNetwork()
        supabase.offline.from(TABLE).update(buildJsonObject { put("title", "Oat milk") }) {
            filter { eq("id", 1) }
        }
        val queued = supabase.offline.pendingMutations().first().single()
        supabase.offline.enableNetwork()
        val report = supabase.offline.syncPendingMutations()

        // Then — the in-memory queue kept the base, and the replay was preconditioned on it
        assertEquals(BASE, queued.baseVersion)
        assertEquals(1, report.syncedCount)
        val write = guardedServer.writes.single()
        assertEquals("eq.$BASE", write.url.parameters["updated_at"])
        assertTrue("return=representation" in write.headers["Prefer"].orEmpty())
        supabase.close()
    }

    private companion object {
        const val TABLE = "todos"
        const val SERVER_ROWS = """[{"id":1,"title":"Buy milk"}]"""
        const val BASE = "2026-10-18T13:00:27.486123+00:00"
        const val NEXT = "2026-10-18T13:05:00.000002+00:00"
    }
}
