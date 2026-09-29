// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.internal.ClientOperationGate
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import io.github.nesfeder.supabase.offline.testdoubles.forbiddenRestException
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A write through the offline bucket never leaves an old copy behind to be served later: an upload
 * or update over a cached object drops the cached copy (and every transformed variant of it), and
 * so does a copy onto it.
 *
 * Storage writes are online-only in this release: offline they fail with the upstream connectivity
 * error, queue nothing, and leave the cache as it was.
 */
class StorageWriteInvalidationTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore
    private lateinit var server: RecordingStorageServer

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-invalidation-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = OLD.encodeToByteArray()
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

    /** Reads [path] with the network down: the cached bytes, or `null` when nothing is served. */
    private suspend fun offlineRead(path: String, transformed: Boolean = false): String? {
        server.online = false
        return try {
            bucket()
                .downloadAuthenticated(path) { if (transformed) transform { size(64, 64) } }
                .decodeToString()
        } catch (failure: Exception) {
            assertTrue(
                failure.isNetworkOrConnectivityFailure(),
                "expected the connectivity failure, got $failure",
            )
            null
        } finally {
            server.online = true
        }
    }

    @Test
    fun an_update_never_leaves_the_old_bytes_to_be_served() = runTest {
        // Given — the old avatar is cached
        bucket().downloadAuthenticated(PATH) {}

        // When — the user replaces it at the same path
        bucket().update(PATH, NEW.encodeToByteArray())

        // Then — an offline read never returns the old image
        assertNotEquals(OLD, offlineRead(PATH))
        assertEquals(NEW, bucket().downloadAuthenticated(PATH) {}.decodeToString())
    }

    @Test
    fun a_write_stopped_by_close_never_leaves_a_stale_copy_to_be_served() = runTest {
        // Each write, and the paths whose cached copies it touches
        val writes: List<Triple<String, List<String>, suspend (OfflineBucketApi) -> Unit>> =
            listOf(
                Triple("update", listOf(PATH)) { bucket ->
                    bucket.update(PATH, NEW.encodeToByteArray())
                },
                Triple("delete", listOf(PATH)) { bucket -> bucket.delete(PATH) },
                Triple("move", listOf(PATH, OTHER_PATH)) { bucket ->
                    bucket.move(PATH, OTHER_PATH)
                },
                Triple("copy", listOf(OTHER_PATH)) { bucket -> bucket.copy(PATH, OTHER_PATH) },
            )
        for ((name, touched, write) in writes) {
            // Given — the old bytes cached at every path the write touches, and the write on the
            // wire,
            // held at the server
            server.objects["$BUCKET/$PATH"] = OLD.encodeToByteArray()
            server.objects["$BUCKET/$OTHER_PATH"] = OLD.encodeToByteArray()
            touched.forEach { path -> bucket().downloadAuthenticated(path) {} }
            val gate = ClientOperationGate()
            val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val closing =
                newTestBucketApi(
                    delegate = server.supabaseClient().storage.from(BUCKET),
                    baseDirectory = dataDirectory,
                    store = store,
                    backgroundScope = clientScope,
                    operationGate = gate,
                )
            val held = CompletableDeferred<Unit>()
            server.gate = held
            server.requestArrived = CompletableDeferred()
            val call = async(Dispatchers.Default) { runCatching { write(closing) } }
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { server.requestArrived.await() }
            }

            // When — the client closes, as its close() does: the gate refuses, the client's scope
            // ends
            gate.close()
            clientScope.cancel()
            val outcome =
                withContext(Dispatchers.Default) { withTimeoutOrNull(5.seconds) { call.await() } }
            server.gate = null
            held.complete(Unit)

            // Then — the write was stopped with the closed error, and since it may have reached the
            // server, no read serves the old bytes at any path it touched
            assertNotNull(outcome, "close() did not stop the $name on the wire")
            assertClientClosedError(outcome.exceptionOrNull(), "the $name on the wire at close()")
            touched.forEach { path ->
                assertNotEquals(OLD, offlineRead(path), "$name left the old bytes served at $path")
            }
        }
    }

    @Test
    fun an_upsert_upload_never_leaves_the_old_bytes_to_be_served() = runTest {
        // Given
        bucket().downloadAuthenticated(PATH) {}

        // When
        bucket().upload(PATH, NEW.encodeToByteArray()) { upsert = true }

        // Then
        assertNotEquals(OLD, offlineRead(PATH))
    }

    @Test
    fun a_signed_url_upload_never_leaves_the_old_bytes_to_be_served() = runTest {
        // Given
        bucket().downloadAuthenticated(PATH) {}

        // When
        bucket().uploadToSignedUrl(PATH, token = "signed-token", data = NEW.encodeToByteArray())

        // Then
        assertNotEquals(OLD, offlineRead(PATH))
    }

    @Test
    fun an_update_drops_every_transformed_variant_too() = runTest {
        // Given — a thumbnail of the old avatar is cached
        val oldThumbnail =
            bucket().downloadAuthenticated(PATH) { transform { size(64, 64) } }.decodeToString()

        // When
        bucket().update(PATH, NEW.encodeToByteArray())

        // Then
        assertNotEquals(oldThumbnail, offlineRead(PATH, transformed = true))
    }

    @Test
    fun a_copy_onto_a_cached_object_drops_its_cached_copy() = runTest {
        // Given — the destination is cached; the copy source differs
        server.objects["$BUCKET/other.png"] = NEW.encodeToByteArray()
        val fake = FakeBucketApi(bucketId = BUCKET)
        fake.onDownload = { OLD.encodeToByteArray() }
        val bucket = newTestBucketApi(delegate = fake, baseDirectory = dataDirectory, store = store)
        bucket.downloadAuthenticated(PATH) {}
        assertNotNull(store.getStorageByKey(BUCKET, PATH))

        // When
        bucket.copy(from = "other.png", to = PATH, destinationBucket = null)

        // Then
        assertNull(store.getStorageByKey(BUCKET, PATH))
    }

    @Test
    fun a_vararg_delete_removes_the_cached_copy() = runTest {
        // Given
        val fake = FakeBucketApi(bucketId = BUCKET)
        fake.onDownload = { OLD.encodeToByteArray() }
        val bucket = newTestBucketApi(delegate = fake, baseDirectory = dataDirectory, store = store)
        bucket.downloadAuthenticated(PATH) {}

        // When — the vararg overload, which the interface forwards to delete(Collection)
        bucket.delete(PATH)

        // Then
        assertEquals(listOf<Collection<String>>(listOf(PATH)), fake.deletedPaths)
        assertNull(store.getStorageByKey(BUCKET, PATH))
    }

    @Test
    fun a_write_that_timed_out_drops_the_cached_copy_it_may_have_replaced() = runTest {
        // Given — a cached object, and an update whose response never arrives
        val fake = FakeBucketApi(bucketId = BUCKET)
        fake.onDownload = { OLD.encodeToByteArray() }
        val bucket = newTestBucketApi(delegate = fake, baseDirectory = dataDirectory, store = store)
        bucket.downloadAuthenticated(PATH) {}
        fake.onWrite = { throw HttpRequestTimeoutException(PATH, 10_000) }

        // When — the server may or may not have stored the new bytes
        assertFailsWith<HttpRequestTimeoutException> {
            bucket.update(PATH, NEW.encodeToByteArray())
        }

        // Then — the old copy is not trusted any more
        assertNull(store.getStorageByKey(BUCKET, PATH))
    }

    @Test
    fun a_write_the_server_rejected_keeps_the_cached_copy() = runTest {
        // Given — a cached object, and an update the server refuses: its copy is unchanged
        val fake = FakeBucketApi(bucketId = BUCKET)
        fake.onDownload = { OLD.encodeToByteArray() }
        val bucket = newTestBucketApi(delegate = fake, baseDirectory = dataDirectory, store = store)
        bucket.downloadAuthenticated(PATH) {}
        fake.onWrite = { throw forbiddenRestException() }

        // When
        assertFailsWith<UnauthorizedRestException> { bucket.update(PATH, NEW.encodeToByteArray()) }

        // Then
        assertNotNull(store.getStorageByKey(BUCKET, PATH))
    }

    @Test
    fun storage_writes_fail_offline_with_the_connectivity_error_and_leave_the_cache_alone() =
        runTest {
            // Given — a cached object, then the network goes away
            val fake = FakeBucketApi(bucketId = BUCKET)
            fake.onDownload = { OLD.encodeToByteArray() }
            val bucket =
                newTestBucketApi(delegate = fake, baseDirectory = dataDirectory, store = store)
            bucket.downloadAuthenticated(PATH) {}
            fake.onWrite = { throw IOException("network unreachable") }

            // When — every write
            val failures =
                listOf<suspend () -> Unit>(
                        { bucket.upload(PATH, NEW.encodeToByteArray()) },
                        { bucket.update(PATH, NEW.encodeToByteArray()) },
                        { bucket.uploadToSignedUrl(PATH, "token", NEW.encodeToByteArray()) },
                        { bucket.delete(listOf(PATH)) },
                        { bucket.move(PATH, "moved.png") },
                        { bucket.copy(PATH, "copied.png") },
                    )
                    .map { write -> assertFailsWith<IOException> { write() } }

            // Then — each surfaced the connectivity error; nothing was queued or cached anew, and
            // the
            // cached copy is still served
            failures.forEach { failure -> assertTrue(failure.isNetworkOrConnectivityFailure()) }
            assertEquals(0, store.pendingMutationCount())
            assertNull(store.getStorageByKey(BUCKET, "moved.png"))
            assertNull(store.getStorageByKey(BUCKET, "copied.png"))
            fake.onDownload = { throw IOException("network unreachable") }
            assertContentEquals(OLD.encodeToByteArray(), bucket.downloadAuthenticated(PATH) {})
        }

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        const val OLD = "old-avatar"
        const val NEW = "new-avatar"
        const val OTHER_PATH = "u-1/other.png"
    }
}
