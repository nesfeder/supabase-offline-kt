// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.overlaidPayloadFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * On a real database, where one `object_cache` table holds every table's payloads and Room tells
 * every reader of it of any write: a live reader of a table loads its payload again for a write to
 * that table, once, and never for a write to another.
 */
@RunWith(AndroidJUnit4::class)
class LiveReaderRereadsAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory =
        File(context.cacheDir, "live-reader-rereads-${Random.nextLong().toULong()}")

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun live_readers_load_their_payload_again_only_for_writes_to_their_own_table() = runTest {
        // Room's queries run on the test scheduler, so each write's every consequence, Room's
        // change notifications included, has run once the scheduler is idle
        val dispatcher = StandardTestDispatcher(testScheduler)
        directory.mkdirs()
        val database =
            buildOfflineDatabase(
                offlineDatabaseBuilder(context, File(directory, "supabase_offline.db").path),
                queryDispatcher = dispatcher,
            )
        try {
            val loads = PayloadLoadCountingDao(database.queryCacheDao(), table = READ_TABLE)
            val store =
                newTestStore(
                    queryCacheDao = loads,
                    cachedFileDao = database.cachedFileDao(),
                    mutationDao = database.mutationDao(),
                )

            // Given — five live readers of table A, each of its own cached list
            val cacheKeys = List(READERS) { index -> "list-$index" }
            cacheKeys.forEach { key -> store.queryCache.cacheResponse(READ_TABLE, key, rows(key)) }
            val shown = MutableStateFlow(emptyMap<String, String?>())
            val readers = cacheKeys.map { key ->
                launch(dispatcher) {
                    store.queryOverlay.overlaidPayloadFlow(READ_TABLE, key).collect { payload ->
                        shown.update { current -> current + (key to payload) }
                    }
                }
            }
            advanceUntilIdle()
            assertEquals(cacheKeys.associateWith { key -> rows(key) }, shown.value)

            // When — 100 payloads are written to table B, each fetched on its own
            val beforeOtherTable = loads.count
            repeat(WRITES) { index ->
                store.queryCache.cacheResponse(WRITTEN_TABLE, "list-$index", rows("b-$index"))
                advanceUntilIdle()
            }
            val loadsForOtherTable = loads.count - beforeOtherTable
            // and then one payload of table A
            val beforeOwnTable = loads.count
            store.queryCache.cacheResponse(READ_TABLE, cacheKeys[0], rows("changed"))
            advanceUntilIdle()
            val loadsForOwnTable = loads.count - beforeOwnTable

            // Then — no reader loads its payload for table B's writes; each loads it once for
            // table A's, and the one whose list changed shows the change
            assertEquals(
                mapOf("for table B's writes" to 0, "for table A's write" to READERS),
                mapOf(
                    "for table B's writes" to loadsForOtherTable,
                    "for table A's write" to loadsForOwnTable,
                ),
                "loads of table A's payloads",
            )
            assertEquals(rows("changed"), shown.value[cacheKeys[0]])
            readers.forEach { reader -> reader.cancelAndJoin() }
        } finally {
            database.close()
        }
    }

    /**
     * [delegate], counting each load of one of [table]'s payloads: a read, or a row Room hands an
     * observer.
     */
    private class PayloadLoadCountingDao(
        private val delegate: QueryCacheDao,
        private val table: String,
    ) : QueryCacheDao by delegate {
        private val loads = AtomicInteger()

        /** The loads counted so far. */
        val count: Int
            get() = loads.get()

        override suspend fun get(table: String, cacheKey: String): CachedQueryRow? =
            delegate.get(table, cacheKey).also { if (table == this.table) loads.incrementAndGet() }

        override fun observe(table: String, cacheKey: String): Flow<CachedQueryRow?> =
            delegate.observe(table, cacheKey).onEach {
                if (table == this.table) loads.incrementAndGet()
            }

        override suspend fun allForTable(table: String): List<CachedQueryRow> =
            delegate.allForTable(table).also { if (table == this.table) loads.incrementAndGet() }
    }

    private companion object {
        const val READ_TABLE = "tasks"
        const val WRITTEN_TABLE = "projects"
        const val READERS = 5
        const val WRITES = 100

        /** A list of two rows titled after [name]. */
        fun rows(name: String): String =
            """[{"id":"$name-1","title":"$name one"},{"id":"$name-2","title":"$name two"}]"""
    }
}
