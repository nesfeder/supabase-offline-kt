// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.postgrest.RequestSession
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.notFoundRestException
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.SYSTEM

/**
 * A download sent while the session is unsettled (restoring, or after a failed refresh) goes out on
 * the anon key, and Storage answers an object that key may not read as "not found". That answer
 * keeps the user's cached copy and is answered from it, as a `401` is; a settled session's "not
 * found" still drops the copy.
 */
class StorageNotFoundWhileUnsettledTest {

    private val dataDirectory =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-not-found-unsettled-${Random.nextLong().toULong()}"
    private val delegate = FakeBucketApi(bucketId = BUCKET, onDownload = { BYTES })
    private var session: RequestSession = SIGNED_IN
    private val bucket =
        newTestBucketApi(
            delegate = delegate,
            baseDirectory = dataDirectory,
            requestSession = { session },
        )

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    /** Caches [PATH] while signed in, then makes Storage answer "not found" under [unsettled]. */
    private suspend fun cachedThenNotFoundUnder(unsettled: RequestSession) {
        bucket.downloadAuthenticated(PATH) {}
        assertNotNull(bucket.cachedFile(PATH))
        session = unsettled
        delegate.onDownload = { throw notFoundRestException() }
    }

    @Test
    fun a_not_found_while_the_session_restores_is_answered_from_the_cached_copy() = runTest {
        // Given
        cachedThenNotFoundUnder(RequestSession.Restoring)

        // When
        val bytes = bucket.downloadAuthenticated(PATH) {}

        // Then
        assertContentEquals(BYTES, bytes)
        assertNotNull(bucket.cachedFile(PATH))
    }

    @Test
    fun a_channel_download_not_found_while_the_session_restores_keeps_the_cached_copy() = runTest {
        // Given
        cachedThenNotFoundUnder(RequestSession.Restoring)
        val channel = ByteChannel()

        // When
        bucket.downloadAuthenticated(PATH, channel) {}

        // Then
        assertContentEquals(BYTES, channel.readRemaining().readByteArray())
        assertNotNull(bucket.cachedFile(PATH))
    }

    @Test
    fun a_not_found_after_a_failed_refresh_keeps_the_cached_copy() = runTest {
        // Given
        cachedThenNotFoundUnder(RequestSession.RefreshFailed)

        // When
        val bytes = bucket.downloadAuthenticated(PATH) {}

        // Then
        assertContentEquals(BYTES, bytes)
        assertNotNull(bucket.cachedFile(PATH))
    }

    @Test
    fun a_not_found_sent_unsettled_keeps_the_copy_though_the_session_settles_meanwhile() = runTest {
        // Given — the request leaves while the session restores, which settles before it answers
        cachedThenNotFoundUnder(RequestSession.Restoring)
        delegate.onDownload = {
            session = SIGNED_IN
            throw notFoundRestException()
        }

        // When
        val bytes = bucket.downloadAuthenticated(PATH) {}

        // Then
        assertContentEquals(BYTES, bytes)
        assertNotNull(bucket.cachedFile(PATH))
    }

    @Test
    fun a_not_found_answered_after_the_session_became_unsettled_keeps_the_cached_copy() = runTest {
        // Given — signed in when the request leaves; the refresh fails before Storage answers
        cachedThenNotFoundUnder(SIGNED_IN)
        delegate.onDownload = {
            session = RequestSession.RefreshFailed
            throw notFoundRestException()
        }

        // When
        val bytes = bucket.downloadAuthenticated(PATH) {}

        // Then
        assertContentEquals(BYTES, bytes)
        assertNotNull(bucket.cachedFile(PATH))
    }

    @Test
    fun a_not_found_while_the_session_restores_with_nothing_cached_is_thrown() = runTest {
        // Given
        session = RequestSession.Restoring
        delegate.onDownload = { throw notFoundRestException() }

        // When / Then
        assertFailsWith<NotFoundRestException> { bucket.downloadAuthenticated(PATH) {} }
        assertFailsWith<NotFoundRestException> { bucket.ensureCachedFile(PATH) }
        assertNull(bucket.cachedFile(PATH))
    }

    @Test
    fun ensuring_a_file_not_found_while_the_session_restores_keeps_its_cached_thumbnail() =
        runTest {
            // Given — a thumbnail cached while signed in, the original never downloaded
            val server = RecordingStorageServer().apply { objects["$BUCKET/$PATH"] = BYTES }
            val storageBucket =
                newTestBucketApi(
                    delegate =
                        server
                            .supabaseClient()
                            .also { it.installDownloadStatusCheck() }
                            .storage
                            .from(BUCKET),
                    baseDirectory = dataDirectory,
                    requestSession = { session },
                )
            val thumbnail = storageBucket.downloadAuthenticated(PATH) { transform { size(8, 8) } }
            session = RequestSession.Restoring
            server.objects.remove("$BUCKET/$PATH")

            // When
            assertFailsWith<NotFoundRestException> { storageBucket.ensureCachedFile(PATH) }

            // Then — the thumbnail is still answered from the cache
            server.online = false
            assertContentEquals(
                thumbnail,
                storageBucket.downloadAuthenticated(PATH) { transform { size(8, 8) } },
            )
        }

    @Test
    fun a_not_found_while_signed_in_still_drops_the_cached_copy() = runTest {
        // Given
        cachedThenNotFoundUnder(SIGNED_IN)

        // When / Then
        assertFailsWith<NotFoundRestException> { bucket.downloadAuthenticated(PATH) {} }
        assertNull(bucket.cachedFile(PATH))
        assertEquals(2, delegate.downloadInvocations)
    }

    private companion object {
        const val BUCKET = "photos"
        const val PATH = "u-1/a.png"
        val BYTES = "cached bytes".encodeToByteArray()
        val SIGNED_IN = RequestSession.SignedIn(QueueIdentity.User("u-1"))
    }
}
