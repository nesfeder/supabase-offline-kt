// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.discard
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A resumable upload replaces the object like any other write, so once it finishes the cached copy
 * of the path is dropped and never served in place of the new one.
 *
 * These run supabase-kt's real resumable client against [RecordingStorageServer]'s TUS endpoints: a
 * resumable upload cannot be faked, its type is sealed in supabase-kt.
 */
class StorageResumableUploadTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var server: RecordingStorageServer

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-resumable-${Random.nextLong().toULong()}"
        store = newTestStore()
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = "OLD".encodeToByteArray()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket() =
        newTestBucketApi(
            delegate = server.supabaseClient().storage.from(BUCKET),
            baseDirectory = dataDirectory,
            store = store,
        )

    /** Waits, on real time, for the cached copy of [PATH] to go. */
    private suspend fun awaitUncached(bucket: CachingBucketApi) {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { while (bucket.cachedFile(PATH) != null) delay(5) }
        }
    }

    @Test
    fun a_finished_resumable_upload_drops_the_cached_copy() = runTest {
        // Given — the old version is cached
        val bucket = bucket()
        bucket.downloadAuthenticated(PATH) {}
        assertNotNull(bucket.cachedFile(PATH))

        // When — a resumable upload replaces it
        val upload =
            bucket.resumable.createOrContinueUpload(
                "NEW".encodeToByteArray(),
                source = "new-version",
                path = PATH,
            ) {
                upsert = true
            }
        upload.startOrResumeUploading()
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { upload.stateFlow.first { it.isDone } }
        }

        // Then — the stale copy is gone, and the next read fetches the new bytes
        awaitUncached(bucket)
        assertNull(bucket.cachedFile(PATH))
        assertEquals("NEW", bucket.downloadAuthenticated(PATH) {}.decodeToString())
    }

    @Test
    fun a_resumable_upload_continued_later_drops_the_cached_copy_when_it_finishes() = runTest {
        // Given — the old version is cached, and an upload of the new one was created but not sent
        val bucket = bucket()
        bucket.downloadAuthenticated(PATH) {}
        val bytes = "NEW".encodeToByteArray()
        bucket.resumable.createOrContinueUpload(bytes, source = "new-version", path = PATH) {
            upsert = true
        }

        // When — it is continued, as an app does on its next start
        val continued =
            bucket.resumable.continuePreviousUploads { _, offset ->
                ByteReadChannel(bytes).apply { discard(offset) }
            }
        continued.forEach { deferred ->
            val upload = deferred.await()
            upload.startOrResumeUploading()
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { upload.stateFlow.first { it.isDone } }
            }
        }

        // Then
        assertEquals(1, continued.size)
        awaitUncached(bucket)
        assertNull(bucket.cachedFile(PATH))
    }

    private companion object {
        const val BUCKET = "media"
        const val PATH = "u-1/avatar.png"
    }
}
