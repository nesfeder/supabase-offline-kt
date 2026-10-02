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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
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

    private companion object {
        const val TABLE = "todos"
        const val SERVER_ROWS = """[{"id":1,"title":"Buy milk"}]"""
    }
}
