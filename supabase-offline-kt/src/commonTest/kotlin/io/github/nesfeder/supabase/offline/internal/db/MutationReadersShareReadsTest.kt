// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A reader of a table's cached queries reads that table's mutation rows again only when this store
 * changes them, and every reader of the table shares that one read: a write to another table costs
 * the reader nothing, and a write to its own table costs one read however many readers there are.
 */
class MutationReadersShareReadsTest {

    @Test
    fun readers_of_one_table_share_one_read_when_they_start_and_one_per_write() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — twenty live readers of the table, each on its own query
            val mutationDao = FakeMutationDao()
            val store = newTestOfflineStore(mutationDao = mutationDao)
            store.enqueueInsert(TABLE, rowsWithId("note-0"))
            val seen = startReaders(store)
            val readsWhenStarted = mutationDao.tableListReads[TABLE]

            // When — one write to the table
            store.enqueueInsert(TABLE, rowsWithId("note-1"))

            // Then — every reader shows it, and the table's rows were read once for all of them
            assertTrue(seen.all { payload -> payload?.contains("note-1") == true }, "$seen")
            assertEquals(1, readsWhenStarted)
            assertEquals(2, mutationDao.tableListReads[TABLE])
        }

    @Test
    fun a_write_to_another_table_reads_nothing_for_this_tables_readers() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — twenty live readers of the table
            val mutationDao = FakeMutationDao()
            val store = newTestOfflineStore(mutationDao = mutationDao)
            store.enqueueInsert(TABLE, rowsWithId("note-0"))
            val seen = startReaders(store)
            val shownBefore = seen.toList()

            // When — fifty writes to another table
            repeat(50) { index -> store.enqueueInsert(OTHER_TABLE, rowsWithId("tag-$index")) }

            // Then — this table's rows were never read again, and no reader changed
            assertEquals(1, mutationDao.tableListReads[TABLE])
            assertEquals(shownBefore, seen.toList())
        }

    /** Starts [READERS] readers of [TABLE], each keeping what it shows last. */
    private fun TestScope.startReaders(store: OfflineStore): Array<String?> {
        val seen = arrayOfNulls<String>(READERS)
        repeat(READERS) { reader ->
            backgroundScope.launch {
                store.overlaidPayloadFlow(TABLE, "sig-$reader").collect { payload ->
                    seen[reader] = payload
                }
            }
        }
        return seen
    }

    private fun rowsWithId(id: String): JsonArray =
        JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive(id)))))

    private companion object {
        const val TABLE = "notes"
        const val OTHER_TABLE = "tags"
        const val READERS = 20
    }
}
