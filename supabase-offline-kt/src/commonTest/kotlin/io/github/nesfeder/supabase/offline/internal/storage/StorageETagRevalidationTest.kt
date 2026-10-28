// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.toByteArray
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * A download asks the server about the copy it has cached, with `If-None-Match` and the ETag the
 * copy was sent under: a 304 answers the call from the copy and moves its `fetchedAt` to now, a 200
 * replaces the copy and its ETag. Runs the real supabase-kt bucket against
 * [RecordingStorageServer], which answers ETags and 304s as Storage does.
 */
class StorageETagRevalidationTest {

    private lateinit var dataDirectory: Path
    private lateinit var server: RecordingStorageServer
    private val cachedFileDao = InMemoryCachedFileDao()
    private var clock = 1_000L

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-etag-${Random.nextLong().toULong()}"
        server = RecordingStorageServer()
        server.answersETags = true
        server.objects["$BUCKET/$PATH"] = CONTENT.encodeToByteArray()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun newClient(): OfflineClient =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            cachedFileDao = cachedFileDao,
            nowMillis = { clock },
            baseDirectoryProvider = { dataDirectory },
        )

    private val contentETag: String
        get() = server.eTagOf(CONTENT.encodeToByteArray())

    /** The `If-None-Match` of each download request, in order. */
    private fun conditions(): List<String?> =
        server.requests.filter { it.method == "GET" }.map { it.ifNoneMatch }

    private suspend fun storedETag(path: String = PATH): String? =
        cachedFileDao.getByKey(cachedFileKeyOf(BUCKET, path))?.eTag

    /** The text of [cached]'s file. */
    private fun readCached(cached: CachedFile?): String =
        FileSystem.SYSTEM.read(assertNotNull(cached).path.toPath()) { readUtf8() }

    @Test
    fun a_download_records_the_etag_its_answer_carried() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)

        // When — a bytes download, a channel download of another object, and ensureCachedFile
        server.objects["$BUCKET/$OTHER"] = OTHER_CONTENT.encodeToByteArray()
        server.objects["$BUCKET/$THIRD"] = THIRD_CONTENT.encodeToByteArray()
        bucket.downloadAuthenticated(PATH) {}
        val channel = ByteChannel()
        bucket.downloadAuthenticated(OTHER, channel) {}
        channel.toByteArray()
        bucket.ensureCachedFile(THIRD)

        // Then — each copy's row holds the ETag its answer carried
        assertEquals(contentETag, storedETag())
        assertEquals(server.eTagOf(OTHER_CONTENT.encodeToByteArray()), storedETag(OTHER))
        assertEquals(server.eTagOf(THIRD_CONTENT.encodeToByteArray()), storedETag(THIRD))
        client.close()
    }

    @Test
    fun a_304_answers_a_bytes_download_from_the_copy_and_moves_its_fetchedAt() = runTest {
        // Given — the object cached at 1 000
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}
        val cachedPath = assertNotNull(bucket.cachedFile(PATH)).path

        // When — downloaded again later, the object unchanged on the server
        clock = 5_000L
        val bytes = bucket.downloadAuthenticated(PATH) {}

        // Then — the request asked about the copy, the server answered 304, and the call got the
        // copy's bytes, whose fetchedAt is now the time of the 304
        assertEquals(listOf(null, contentETag), conditions())
        assertEquals(1, server.notModifiedAnswers)
        assertEquals(CONTENT, bytes.decodeToString())
        val cached = assertNotNull(bucket.cachedFile(PATH))
        assertEquals(5_000L, cached.fetchedAt.toEpochMilliseconds())
        assertEquals(cachedPath, cached.path)
        assertEquals(contentETag, storedETag())
        client.close()
    }

    @Test
    fun a_304_answers_a_channel_download_from_the_copy_and_moves_its_fetchedAt() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}

        // When
        clock = 7_000L
        val channel = ByteChannel()
        bucket.downloadAuthenticated(PATH, channel) {}

        // Then
        assertEquals(CONTENT, channel.toByteArray().decodeToString())
        assertEquals(listOf(null, contentETag), conditions())
        assertEquals(1, server.notModifiedAnswers)
        assertEquals(
            7_000L,
            assertNotNull(bucket.cachedFile(PATH)).fetchedAt.toEpochMilliseconds(),
        )
        client.close()
    }

    @Test
    fun a_copy_cached_by_ensureCachedFile_is_revalidated_by_the_next_download() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.ensureCachedFile(PATH)

        // When
        clock = 2_000L
        val bytes = bucket.downloadPublic(PATH) {}

        // Then
        assertEquals(CONTENT, bytes.decodeToString())
        assertEquals(listOf(null, contentETag), conditions())
        assertEquals(1, server.notModifiedAnswers)
        assertEquals(
            2_000L,
            assertNotNull(bucket.cachedFile(PATH)).fetchedAt.toEpochMilliseconds(),
        )
        client.close()
    }

    @Test
    fun a_transform_is_asked_about_with_its_own_etag() = runTest {
        // Given — the original and a thumbnail cached, each under its own ETag
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}
        val thumbnail = bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }

        // When
        val again = bucket.downloadAuthenticated(PATH) { transform { size(64, 64) } }

        // Then — the thumbnail's request named the thumbnail's ETag, and got its bytes back
        assertEquals(listOf(null, null, server.eTagOf(thumbnail)), conditions())
        assertEquals(thumbnail.decodeToString(), again.decodeToString())
        assertEquals(1, server.notModifiedAnswers)
        client.close()
    }

    @Test
    fun a_200_replaces_the_copy_and_its_etag() = runTest {
        // Given — the object cached, then changed on the server behind the client
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}
        server.objects["$BUCKET/$PATH"] = CHANGED.encodeToByteArray()

        // When
        clock = 3_000L
        val bytes = bucket.downloadAuthenticated(PATH) {}

        // Then — asked about the old copy, the server sent the new one, which replaced it
        assertEquals(listOf(null, contentETag), conditions())
        assertEquals(0, server.notModifiedAnswers)
        assertEquals(CHANGED, bytes.decodeToString())
        val cached = assertNotNull(bucket.cachedFile(PATH))
        assertEquals(CHANGED, FileSystem.SYSTEM.read(cached.path.toPath()) { readUtf8() })
        assertEquals(3_000L, cached.fetchedAt.toEpochMilliseconds())
        assertEquals(server.eTagOf(CHANGED.encodeToByteArray()), storedETag())
        client.close()
    }

    @Test
    fun a_download_after_an_eviction_or_a_write_asks_about_nothing() = runTest {
        // Given — a copy the next download asks about
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}
        bucket.downloadAuthenticated(PATH) {}
        assertEquals(listOf(null, contentETag), conditions())

        // When — its copies evicted, then downloaded; then written, then downloaded
        bucket.evictCachedFile(PATH)
        bucket.downloadAuthenticated(PATH) {}
        bucket.upload(PATH, CHANGED.encodeToByteArray()) { upsert = true }
        val afterTheWrite = bucket.downloadAuthenticated(PATH) {}

        // Then — neither download asked about a copy, and the last one cached the new bytes
        assertEquals(listOf(null, contentETag, null, null), conditions())
        assertEquals(CHANGED, afterTheWrite.decodeToString())
        assertEquals(server.eTagOf(CHANGED.encodeToByteArray()), storedETag())
        client.close()
    }

    @Test
    fun a_304_about_a_copy_written_over_meanwhile_is_not_served() = runTest {
        // Given — a cached copy, and a download asking about it held at the server once the server
        // has decided its answer: 304
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}
        server.downloadGate = CompletableDeferred()
        server.downloadHeld = CompletableDeferred()
        val download = async { bucket.downloadAuthenticated(PATH) {} }
        server.downloadHeld.await()

        // When — the object is written through the client before the 304 arrives
        bucket.upload(PATH, CHANGED.encodeToByteArray()) { upsert = true }
        server.downloadGate?.complete(Unit)
        val bytes = download.await()

        // Then — the 304 about the dropped copy was not served: the download asked again, about
        // nothing, got the new bytes, and cached them
        assertEquals(1, server.notModifiedAnswers)
        assertEquals(listOf(null, contentETag, null), conditions())
        assertEquals(CHANGED, bytes.decodeToString())
        assertEquals(CHANGED, readCached(bucket.cachedFile(PATH)))
        client.close()
    }

    @Test
    fun a_channel_download_whose_304_was_written_over_caches_what_it_fetches_again() = runTest {
        // Given — as for a bytes download: a 304 decided, held, and the object written meanwhile
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}
        server.downloadGate = CompletableDeferred()
        server.downloadHeld = CompletableDeferred()
        val channel = ByteChannel()
        val download = async { bucket.downloadAuthenticated(PATH, channel) {} }
        server.downloadHeld.await()

        // When
        bucket.upload(PATH, CHANGED.encodeToByteArray()) { upsert = true }
        server.downloadGate?.complete(Unit)
        download.await()

        // Then — the bytes fetched after the write are the cached copy
        assertEquals(listOf(null, contentETag, null), conditions())
        assertEquals(CHANGED, channel.toByteArray().decodeToString())
        assertEquals(CHANGED, readCached(bucket.cachedFile(PATH)))
        client.close()
    }

    @Test
    fun a_304_about_a_copy_whose_file_is_gone_fetches_the_object_whole() = runTest {
        // Given — a cached copy whose file was deleted behind the cache
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}
        FileSystem.SYSTEM.delete(assertNotNull(bucket.cachedFile(PATH)).path.toPath())

        // When
        clock = 4_000L
        val channel = ByteChannel()
        bucket.downloadAuthenticated(PATH, channel) {}

        // Then — asked about the copy, answered 304, the download asked again about nothing, and
        // cached what it got
        assertEquals(CONTENT, channel.toByteArray().decodeToString())
        assertEquals(1, server.notModifiedAnswers)
        assertEquals(listOf(null, contentETag, null), conditions())
        val cached = assertNotNull(bucket.cachedFile(PATH))
        assertEquals(4_000L, cached.fetchedAt.toEpochMilliseconds())
        assertEquals(CONTENT, FileSystem.SYSTEM.read(cached.path.toPath()) { readUtf8() })
        client.close()
    }

    @Test
    fun a_partial_download_and_one_with_its_own_condition_ask_about_no_copy() = runTest {
        // Given — a copy a whole download asks about
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}
        bucket.downloadAuthenticated(PATH) {}

        // When — a ranged download, and one whose caller asks about a version of its own
        bucket.downloadAuthenticated(PATH) {
            httpOverride { header(HttpHeaders.Range, "bytes=0-3") }
        }
        val ownCondition =
            bucket.downloadAuthenticated(PATH) {
                httpOverride { header(HttpHeaders.IfNoneMatch, "\"the-callers-own\"") }
            }

        // Then — the ranged request asked nothing, the caller's own condition went as it was, and
        // the server answered it whole
        assertEquals(listOf(null, contentETag, null, "\"the-callers-own\""), conditions())
        assertEquals(CONTENT, ownCondition.decodeToString())
        client.close()
    }

    @Test
    fun a_range_set_on_the_bucket_gets_its_range_from_a_channel_download() = runTest {
        // Given — a cached copy with an ETag, and a bucket whose every request carries a Range,
        // which supabase-kt adds to a channel download after the request's own options
        val client = newClient()
        client.storage.from(BUCKET).downloadAuthenticated(PATH) {}
        val ranged = client.storage.from(BUCKET).setHeader(HttpHeaders.Range, "bytes=0-3")

        // When
        val channel = ByteChannel()
        ranged.downloadAuthenticated(PATH, channel) {}

        // Then — the ranged request asked about no copy, so the server (which looks at
        // If-None-Match first) answered the range, not a 304 the whole copy would answer
        assertEquals(CONTENT.take(4), channel.toByteArray().decodeToString())
        assertEquals(listOf(null, null), conditions())
        assertEquals(0, server.notModifiedAnswers)
        client.close()
    }

    @Test
    fun the_callers_own_condition_set_on_the_bucket_never_confirms_the_cached_copy() = runTest {
        // Given — a cached copy, the object changed on the server since, and a bucket whose every
        // request asks about the new version
        val client = newClient()
        client.storage.from(BUCKET).downloadAuthenticated(PATH) {}
        server.objects["$BUCKET/$PATH"] = CHANGED.encodeToByteArray()
        val newETag = server.eTagOf(CHANGED.encodeToByteArray())
        val conditioned = client.storage.from(BUCKET).setHeader(HttpHeaders.IfNoneMatch, newETag)

        // When — a channel download and a bytes download, both answered 304 for the caller's own
        // condition
        clock = 5_000L
        conditioned.downloadAuthenticated(PATH, ByteChannel()) {}
        conditioned.downloadAuthenticated(PATH) {}

        // Then — each went out with the caller's condition alone, and the copy of the old version
        // was not taken for current: its fetchedAt stays
        assertEquals(listOf(null, newETag, newETag), conditions())
        assertEquals(
            1_000L,
            cachedFileDao.getByKey(cachedFileKeyOf(BUCKET, PATH))?.lastFetchedEpochMs,
        )
        client.close()
    }

    @Test
    fun a_moved_copy_keeps_its_etag() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.downloadAuthenticated(PATH) {}

        // When — moved through the client
        bucket.move(PATH, OTHER)
        val moved = cachedFileDao.getByKey(cachedFileKeyOf(BUCKET, OTHER))

        // Then
        assertEquals(contentETag, moved?.eTag)
        assertNull(cachedFileDao.getByKey(cachedFileKeyOf(BUCKET, PATH)))
        client.close()
    }

    private companion object {
        const val BUCKET = "media"
        const val PATH = "photos/a.png"
        const val OTHER = "photos/b.png"
        const val THIRD = "photos/c.png"
        const val CONTENT = "the original bytes"
        const val OTHER_CONTENT = "another object's bytes"
        const val THIRD_CONTENT = "a third object's bytes"
        const val CHANGED = "the new bytes"
    }
}
