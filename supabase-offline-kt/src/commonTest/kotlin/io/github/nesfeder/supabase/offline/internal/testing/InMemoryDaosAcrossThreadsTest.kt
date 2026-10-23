// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.internal.db.CachedFileRow
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * The in-memory DAOs back the testing artifact's client, whose live readers run on one thread while
 * a refresh writes on another, as Room's do. Every read sees a whole state, and every observer ends
 * on what the DAO holds, however the threads interleave.
 */
class InMemoryDaosAcrossThreadsTest {

    @Test
    fun the_object_dao_reads_while_another_thread_writes() = runTest {
        // Given
        val dao = InMemoryQueryCacheDao()
        val failures = AtomicInt(0)

        // When — one thread writes payloads while another reads, and registers observers
        withContext(Dispatchers.Default) {
            whileWriting(write = { index -> dao.upsert(payloadRow(index)) }) {
                repeat(READS) { read ->
                    countingFailures(failures) {
                        dao.rowsWithCacheKeyPrefix(RESERVED_TABLE_NAME, "shape:")
                        dao.maxSyncedThrough()
                        dao.minSyncedThroughForTable(TABLE)
                        dao.cacheKeysForTable(TABLE)
                        dao.observe(TABLE, "observed-${read % CACHE_KEYS}")
                    }
                }
            }
        }

        // Then
        assertEquals(0, failures.load())
    }

    @Test
    fun writers_on_two_threads_leave_each_observer_on_what_the_dao_holds() = runTest {
        // Given — observers of every signature the writers touch
        val dao = InMemoryQueryCacheDao()
        val observed = List(CACHE_KEYS) { index -> dao.observe(TABLE, "q$index") }

        // When — two threads write the same signatures
        withContext(Dispatchers.Default) {
            List(2) { writer ->
                    launch {
                        repeat(WRITES) { round ->
                            dao.upsert(
                                CachedQueryRow(
                                    table = TABLE,
                                    cacheKey = "q${round % CACHE_KEYS}",
                                    payload = "[$writer,$round]",
                                    updatedAt = 1L,
                                )
                            )
                        }
                    }
                }
                .joinAll()
        }

        // Then
        observed.forEachIndexed { index, flow ->
            assertEquals(dao.get(TABLE, "q$index"), flow.first())
        }
    }

    @Test
    fun the_storage_dao_reads_while_another_thread_writes() = runTest {
        // Given
        val dao = InMemoryCachedFileDao()
        val failures = AtomicInt(0)

        // When
        withContext(Dispatchers.Default) {
            whileWriting(write = { index -> dao.upsert(cachedFileRow(index)) }) {
                repeat(READS) { read ->
                    countingFailures(failures) {
                        dao.all()
                        dao.totalSizeBytes()
                        dao.forObject(
                            BUCKET,
                            "file-${read % CACHE_KEYS}",
                            "file-${read % CACHE_KEYS}@",
                            "file-${read % CACHE_KEYS}A",
                        )
                        dao.delete(BUCKET, "file-${read % CACHE_KEYS}")
                    }
                }
            }
        }

        // Then
        assertEquals(0, failures.load())
    }

    /** Runs [write] on a thread of its own, over and over, until [read] returns. */
    private suspend fun CoroutineScope.whileWriting(
        write: suspend (index: Int) -> Unit,
        read: suspend () -> Unit,
    ) {
        val writing = AtomicBoolean(true)
        val writer = launch {
            var index = 0
            while (writing.load()) write(index++)
        }
        launch { read() }.join()
        writing.store(false)
        writer.join()
    }

    /** Runs [block], counting any failure it throws in [failures]. */
    private suspend fun countingFailures(failures: AtomicInt, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            failures.addAndFetch(1)
        }
    }

    private fun payloadRow(index: Int): CachedQueryRow =
        CachedQueryRow(
            table = TABLE,
            cacheKey = "q${index % CACHE_KEYS}",
            payload = "[]",
            updatedAt = 1L,
            syncedThrough = index.toLong(),
        )

    private fun cachedFileRow(index: Int): CachedFileRow =
        CachedFileRow(
            key = "$BUCKET::file-${index % CACHE_KEYS}",
            bucketId = BUCKET,
            path = "file-${index % CACHE_KEYS}",
            localPath = "/in-memory/file-$index",
            sizeBytes = index.toLong(),
            eTag = null,
            lastFetchedEpochMs = 1L,
        )

    private companion object {
        const val TABLE = "todos"
        const val BUCKET = "avatars"
        const val CACHE_KEYS = 500
        const val READS = 20_000
        const val WRITES = 5_000
    }
}
