// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.utils.io.ByteChannel
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A call naming an object whose name holds a lone UTF-16 surrogate cannot send that name:
 * supabase-kt encodes it as UTF-8, in the URL or in a JSON body, and a lone surrogate has no UTF-8
 * form. On the JVM the encoder sends a `?` in its place, so the request would reach another object;
 * on Kotlin/Native the URL's encoder throws an `IOException` before sending, which would read as a
 * lost connection. The library refuses such a call as an invalid argument before anything is sent,
 * on every platform, so an app neither touches another object nor thinks it is offline.
 */
class StorageLoneSurrogateNameTest {

    private val dataDirectory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-lone-surrogate-${Random.nextLong().toULong()}"
    private val server = RecordingStorageServer()
    private val client: OfflineClient =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            baseDirectoryProvider = { dataDirectory },
        )
    private val bucket: OfflineBucketApi
        get() = client.storage.from(BUCKET)

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    /** Every call that puts [name] in its URL, each with what it throws. */
    private suspend fun failuresOfEveryCallNaming(name: String): List<Throwable> =
        listOf<suspend () -> Unit>(
                { bucket.downloadAuthenticated(name) {} },
                { bucket.downloadPublic(name) {} },
                { bucket.downloadAuthenticated(name, ByteChannel()) {} },
                { bucket.downloadPublic(name, ByteChannel()) {} },
                { bucket.upload(name, BYTES) },
                { bucket.update(name, BYTES) },
                { bucket.uploadToSignedUrl(name, "token", BYTES) },
                { bucket.createSignedUploadUrl(name) },
                { bucket.createSignedUrl(name, 60.seconds) },
                { bucket.info(name) },
                { bucket.exists(name) },
            )
            .map { call -> assertFailsWith<IllegalArgumentException>(name) { call() } }

    @Test
    fun a_call_naming_an_object_with_a_lone_surrogate_is_refused_and_sends_nothing() = runTest {
        listOf("x\uD800.png", "x\uDC00.png", "\uD800", "a\uD83D/b.png").forEach { name ->
            // When
            val failures = failuresOfEveryCallNaming(name)

            // Then — an invalid argument naming no object, never a connectivity failure
            failures.forEach { failure ->
                assertFalse(failure.isNetworkOrConnectivityFailure(), "$name: $failure")
                assertFalse(name in failure.message.orEmpty(), "$name: ${failure.message}")
            }
        }
        assertEquals(emptyList(), server.requests)
        client.close()
    }

    @Test
    fun a_delete_naming_an_object_with_a_lone_surrogate_is_refused_and_keeps_the_cache() = runTest {
        // Given — a cached object, deleted in one call with a name the request cannot carry
        server.objects["$BUCKET/$CACHED"] = BYTES
        bucket.downloadAuthenticated(CACHED) {}
        val sent = server.requests.size

        // When
        val failure =
            assertFailsWith<IllegalArgumentException> {
                bucket.delete(listOf(CACHED, "x\uD800.txt"))
            }

        // Then — nothing sent, so neither `x?.txt` nor the cached object is deleted
        assertFalse(failure.isNetworkOrConnectivityFailure(), "$failure")
        assertEquals(sent, server.requests.size)
        assertNotNull(bucket.cachedFile(CACHED))
        client.close()
    }

    @Test
    fun a_move_to_or_from_a_name_with_a_lone_surrogate_is_refused_and_keeps_the_cache() = runTest {
        // Given — a cached object
        server.objects["$BUCKET/$CACHED"] = BYTES
        bucket.downloadAuthenticated(CACHED) {}
        val sent = server.requests.size

        // When — moved to, and from, a name the request body cannot carry
        val failures =
            listOf(
                assertFailsWith<IllegalArgumentException> {
                    bucket.move(CACHED, "x\uD800.txt")
                },
                assertFailsWith<IllegalArgumentException> {
                    bucket.move("x\uDC00.txt", CACHED)
                },
            )

        // Then — nothing sent, so no `x?.txt` is made, and the cached copy stays where it was
        failures.forEach { failure ->
            assertFalse(failure.isNetworkOrConnectivityFailure(), "$failure")
        }
        assertEquals(sent, server.requests.size)
        assertNotNull(bucket.cachedFile(CACHED))
        assertNull(bucket.cachedFile("x?.txt"))
        client.close()
    }

    @Test
    fun every_call_sending_a_name_with_a_lone_surrogate_in_its_body_is_refused() = runTest {
        val name = "x\uD800.txt"

        // When
        val failures =
            listOf<suspend () -> Unit>(
                    { bucket.copy(name, "a.txt") },
                    { bucket.copy("a.txt", name) },
                    { bucket.createSignedUrls(60.seconds, listOf("a.txt", name)) },
                    { bucket.list(name) },
                )
                .map { call -> assertFailsWith<IllegalArgumentException> { call() } }

        // Then — invalid arguments, and nothing sent
        failures.forEach { failure ->
            assertFalse(failure.isNetworkOrConnectivityFailure(), "$failure")
        }
        assertEquals(emptyList(), server.requests)
        client.close()
    }

    @Test
    fun a_name_whose_surrogates_pair_up_is_sent_by_its_own_name() = runTest {
        // Given — an emoji outside the Basic Multilingual Plane, one surrogate pair
        val name = "smile-\uD83D\uDE00.png"
        server.objects["$BUCKET/$name"] = BYTES

        // When
        val downloaded = bucket.downloadAuthenticated(name) {}

        // Then — sent, percent-encoded as UTF-8, and answered with its own object
        assertEquals(
            "/storage/v1/object/authenticated/$BUCKET/smile-%F0%9F%98%80.png",
            server.requests.single().encodedPath,
        )
        assertEquals("bytes", downloaded.decodeToString())
        client.close()
    }

    private companion object {
        const val BUCKET = "photos"
        const val CACHED = "a.txt"
        val BYTES = "bytes".encodeToByteArray()
    }
}
