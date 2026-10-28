// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.openAndroidClient
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.runner.RunWith

/**
 * Every write to the cached queries' table is counted: each holder of the table in an open client
 * holds the one counting view of it, and the view counts every write the table takes. A live reader
 * hears of a payload write only through those counts, so a part writing the table directly, or a
 * write the view passes through uncounted, would leave the readers of what it wrote stale.
 */
@RunWith(AndroidJUnit4::class)
class EveryCacheWriterCountsAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory =
        File(context.cacheDir, "every-cache-writer-${Random.nextLong().toULong()}")

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun every_holder_of_the_cached_queries_table_holds_the_one_counting_view() = runTest {
        // Given — a client over a table of its own, following a table through Realtime
        val table = InMemoryQueryCacheDao()
        val client =
            newTestOfflineClient(
                queryCacheDao = table,
                watchedTables = listOf(WatchedTable("tasks")),
                serverChangeFeed = FakeServerChangeFeed(),
            )
        try {
            // When — every cached-queries table the client's own objects reach is found
            val holders = cacheTablesReachableFrom(client)

            // Then — the counting view is the only one, and only it holds the table itself
            assertOneCountingViewOver(table, holders, alsoHeldBy = { false })
        } finally {
            client.close()
        }
    }

    @Test
    fun on_the_platform_database_only_the_database_and_the_counting_view_hold_its_table() =
        runTest {
            // Given — a client over the platform's database
            val client =
                openAndroidClient(
                    context,
                    newTestSupabaseClient(),
                    Offline.createConfig {
                        autoSync = false
                        dataDirectory = directory.toOkioPath().toString()
                    },
                )
            try {
                // When — every cached-queries table the client's own objects reach is found
                val holders = cacheTablesReachableFrom(client)
                val database = reachedDatabaseOf(client)

                // Then — Room's DAO is held by the database that made it and the one counting view
                assertOneCountingViewOver(
                    database.queryCacheDao(),
                    holders,
                    alsoHeldBy = { holder -> OfflineDatabase::class.java.isAssignableFrom(holder) },
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun every_write_the_table_takes_moves_the_count_of_the_table_it_changes() = runTest {
        // Given — the table's methods, each a read, a write or the transaction
        val declared =
            QueryCacheDao::class
                .java
                .declaredMethods
                .filterNot { method -> method.isSynthetic || method.isBridge }
                .map { method -> method.name }
                .toSet()

        // Then — a method added to the table is named here first: as a write, the counting view
        // must count it, and this test calls it
        assertEquals(
            (READS + WRITES.keys + TRANSACTION).sorted(),
            declared.sorted(),
            "each method of QueryCacheDao is a read, a write or the transaction",
        )
        WRITES.forEach { (name, write) ->
            // When — the write is made through a counting view, whether it changes a row or not
            val view = PayloadWriteCountingDao(InMemoryQueryCacheDao())
            view.upsert(CachedQueryRow(TABLE, "list", "[]", updatedAt = 1L))
            val before = view.writesTo(write.countedAgainst)
            write.call(view)

            // Then — the table it changes, or every table, was counted
            assertNotEquals(before, view.writesTo(write.countedAgainst), "$name is not counted")
        }
    }

    /**
     * Asserts that [holders] hold one counting view, over [table], and besides it only [table]:
     * held by that view, and by no class but those [alsoHeldBy] accepts.
     */
    private fun assertOneCountingViewOver(
        table: QueryCacheDao,
        holders: Map<QueryCacheDao, List<Pair<Class<*>, String>>>,
        alsoHeldBy: (Class<*>) -> Boolean,
    ) {
        val counting = holders.keys.filterIsInstance<PayloadWriteCountingDao>()
        assertEquals(1, counting.size, "counting views: ${describe(holders)}")
        assertEquals(setOf(table, counting.single()), holders.keys.toSet(), describe(holders))
        val tableHolders = holders.getValue(table)
        assertTrue(
            tableHolders.any { (holder, _) -> holder == PayloadWriteCountingDao::class.java },
            "the counting view is not over the table: ${describe(holders)}",
        )
        assertTrue(
            tableHolders.all { (holder, _) ->
                holder == PayloadWriteCountingDao::class.java || alsoHeldBy(holder)
            },
            "holders of the table itself: $tableHolders",
        )
        // and the walk reached each component that writes the table
        val viewHolders = holders.getValue(counting.single()).map { (holder, _) -> holder }
        WRITERS.forEach { writer ->
            assertTrue(
                viewHolders.any { holder -> holder.simpleName == writer },
                "$writer holds no counting view: $viewHolders",
            )
        }
    }

    /** The platform database [root]'s own objects reach. */
    private fun reachedDatabaseOf(root: Any): OfflineDatabase =
        walk(root) { _, _, _ -> }.filterIsInstance<OfflineDatabase>().single()

    /**
     * Every [QueryCacheDao] reachable from [root] through the engine's own objects, each with the
     * class and field of each of its holders.
     */
    private fun cacheTablesReachableFrom(
        root: Any
    ): Map<QueryCacheDao, List<Pair<Class<*>, String>>> {
        val found = IdentityHashMap<QueryCacheDao, MutableList<Pair<Class<*>, String>>>()
        walk(root) { holder, field, element ->
            if (element is QueryCacheDao)
                found.getOrPut(element) { mutableListOf() } += holder.javaClass to field.name
        }
        return found
    }

    /**
     * Walks the engine's objects from [root], telling [visit] of each value each holds, and returns
     * every object walked.
     */
    private fun walk(
        root: Any,
        visit: (holder: Any, field: Field, element: Any) -> Unit,
    ): Set<Any> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val holder = pending.removeFirst()
            if (!seen.add(holder)) continue
            for (field in instanceFieldsOf(holder.javaClass)) {
                val value = field.get(holder) ?: continue
                for (element in elementsOf(value)) {
                    visit(holder, field, element)
                    if (isEngineClass(element.javaClass)) pending += element
                }
            }
        }
        return seen
    }

    /**
     * The instance fields of [type] and of its superclasses up to the first that is neither the
     * engine's nor Kotlin's, which holds a bound reference's receiver.
     */
    private fun instanceFieldsOf(type: Class<*>): List<Field> =
        generateSequence(type) { current -> current.superclass }
            .takeWhile { current -> isEngineClass(current) || current.name.startsWith("kotlin.") }
            .flatMap { current -> current.declaredFields.asSequence() }
            .filter { field -> !Modifier.isStatic(field.modifiers) }
            .onEach { field -> field.isAccessible = true }
            .toList()

    /** What [value] holds: itself, or what a collection, a lazy, a state or a pair holds. */
    private fun elementsOf(value: Any): List<Any> =
        when (value) {
            is Lazy<*> -> if (value.isInitialized()) listOfNotNull(value.value) else emptyList()
            is StateFlow<*> -> listOfNotNull(value.value)
            is AtomicReference<*> -> listOfNotNull(value.get())
            is Pair<*, *> -> listOfNotNull(value.first, value.second)
            is Triple<*, *, *> -> listOfNotNull(value.first, value.second, value.third)
            is Map<*, *> -> (value.keys + value.values).filterNotNull()
            is Iterable<*> -> value.filterNotNull()
            is Array<*> -> value.filterNotNull()
            else -> listOf(value)
        }

    private fun isEngineClass(type: Class<*>): Boolean = type.name.startsWith(ENGINE_PACKAGE)

    private fun describe(holders: Map<QueryCacheDao, List<Pair<Class<*>, String>>>): String =
        holders.entries.joinToString { (dao, fields) ->
            "${dao.javaClass.simpleName} in " +
                fields.map { (holder, field) -> "${holder.simpleName}.$field" }
        }

    /**
     * A write the table takes, made through [call], and the table it counts against: its own, or
     * the reserved rows' for a write only they take; a write that may reach every table moves every
     * table's count.
     */
    private class Write(val countedAgainst: String, val call: suspend (QueryCacheDao) -> Unit)

    private companion object {
        const val ENGINE_PACKAGE = "io.github.nesfeder.supabase.offline."
        const val TABLE = "tasks"

        /**
         * The parts that write the table: the components `buildCacheComponents` gives it, and the
         * file cache's pin records.
         */
        val WRITERS =
            listOf(
                "CacheOwnership",
                "MutationQueue",
                "QueryCache",
                "QueryOverlay",
                "CachePatcher",
                "LocalData",
                "PinnedFileRecords",
            )

        /** The table's methods that read and write nothing. */
        val READS =
            setOf(
                "get",
                "observe",
                "hasRow",
                "allForTable",
                "maxSyncedThrough",
                "minSyncedThroughForTable",
                "cacheKeysForTable",
                "rowsWithCacheKeyPrefix",
                "totalPayloadBytes",
                "payloadLengthOf",
                "payloadLengthsOldestFirst",
            )

        /** The table's transaction, whose writes are the block's own. */
        const val TRANSACTION = "runInTransaction"

        /** Each of the table's writes, by name. */
        val WRITES: Map<String, Write> =
            mapOf(
                "upsert" to
                    Write(TABLE) { dao -> dao.upsert(CachedQueryRow(TABLE, "new", "[]", 2L)) },
                "updatePayload" to
                    Write(TABLE) { dao -> dao.updatePayload(TABLE, "list", "[{}]", 2L) },
                "delete" to Write(TABLE) { dao -> dao.delete(TABLE, "list") },
                "deleteTable" to Write(TABLE) { dao -> dao.deleteTable(TABLE) },
                "deleteAll" to Write(TABLE) { dao -> dao.deleteAll() },
                "deleteAllExceptTable" to
                    Write(TABLE) { dao -> dao.deleteAllExceptTable(RESERVED_TABLE_NAME) },
                "deleteWithCacheKeyPrefix" to
                    Write(TABLE) { dao -> dao.deleteWithCacheKeyPrefix(TABLE, "li") },
                "deleteOrphanedQueryShapes" to
                    Write(RESERVED_TABLE_NAME) { dao -> dao.deleteOrphanedQueryShapes() },
                "deletePayloads" to
                    Write(TABLE) { dao ->
                        dao.deletePayloads(TABLE, listOf(1L), fetchedAtOrBefore = 1L)
                    },
            )
    }
}
