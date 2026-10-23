// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Every part of an open client that holds the cached queries' table holds the one counting view of
 * it: a live reader hears of a payload write only through those counts, so a part writing the table
 * directly would leave the readers of what it wrote stale.
 */
@RunWith(AndroidJUnit4::class)
class EveryCacheWriterCountsAndroidTest {

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
            val counting = holders.keys.filterIsInstance<PayloadWriteCountingDao>()
            assertEquals(1, counting.size, "counting views: ${describe(holders)}")
            assertEquals(
                setOf<QueryCacheDao>(table, counting.single()),
                holders.keys.toSet(),
                describe(holders),
            )
            val countingFields = "${PayloadWriteCountingDao::class.java.name}."
            assertTrue(
                holders.getValue(table).all { field -> field.startsWith(countingFields) },
                "holders of the table itself: ${holders.getValue(table)}",
            )
            // and the walk reached each component that writes the table
            val holderClasses =
                holders.getValue(counting.single()).map { field -> field.substringBeforeLast('.') }
            WRITERS.forEach { writer ->
                assertTrue(
                    holderClasses.any { holder -> holder.endsWith(".$writer") },
                    "$writer holds no counting view: $holderClasses",
                )
            }
        } finally {
            client.close()
        }
    }

    /**
     * Every [QueryCacheDao] reachable from [root] through the engine's own objects, each with the
     * fields (`class.field`) that hold it.
     */
    private fun cacheTablesReachableFrom(root: Any): Map<QueryCacheDao, List<String>> {
        val found = IdentityHashMap<QueryCacheDao, MutableList<String>>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val pending = ArrayDeque<Any>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val holder = pending.removeFirst()
            if (!seen.add(holder)) continue
            for (field in instanceFieldsOf(holder.javaClass)) {
                val value = field.get(holder) ?: continue
                for (element in elementsOf(value)) {
                    if (element is QueryCacheDao)
                        found.getOrPut(element) { mutableListOf() } +=
                            "${holder.javaClass.name}.${field.name}"
                    if (isEngineObject(element)) pending += element
                }
            }
        }
        return found
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

    /** What [value] holds: itself, or the elements of a collection, a lazy or a state. */
    private fun elementsOf(value: Any): List<Any> =
        when (value) {
            is Lazy<*> -> if (value.isInitialized()) listOfNotNull(value.value) else emptyList()
            is StateFlow<*> -> listOfNotNull(value.value)
            is Map<*, *> -> (value.keys + value.values).filterNotNull()
            is Iterable<*> -> value.filterNotNull()
            is Array<*> -> value.filterNotNull()
            else -> listOf(value)
        }

    private fun isEngineObject(value: Any): Boolean = isEngineClass(value.javaClass)

    private fun isEngineClass(type: Class<*>): Boolean = type.name.startsWith(ENGINE_PACKAGE)

    private fun describe(holders: Map<QueryCacheDao, List<String>>): String =
        holders.entries.joinToString { (dao, fields) -> "${dao.javaClass.simpleName} in $fields" }

    private companion object {
        const val ENGINE_PACKAGE = "io.github.nesfeder.supabase.offline."

        /** The components [buildCacheComponents] gives the table, each of which writes it. */
        val WRITERS =
            listOf(
                "CacheOwnership",
                "MutationQueue",
                "QueryCache",
                "QueryOverlay",
                "CachePatcher",
                "LocalData",
            )
    }
}
