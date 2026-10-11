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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A call naming an object whose name holds a `%` not followed by two hex digits cannot be sent: the
 * pinned supabase-kt puts the name in the URL as it is, and the HTTP client cannot build that URL.
 * It is refused as an invalid argument before anything is sent, not reported as a connectivity
 * failure, so an app does not think it is offline.
 */
class StorageBarePercentNameTest {

    private val dataDirectory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-bare-percent-${Random.nextLong().toULong()}"
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
    fun a_call_naming_an_object_with_a_bare_percent_is_refused_and_sends_nothing() = runTest {
        listOf("100%.png", "a%", "a%4", "%zz.png").forEach { name ->
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
    fun a_name_whose_percent_begins_an_escape_is_sent_as_before() = runTest {
        // When — a name the URL can carry, whichever object the server reads it as
        val failure = runCatching { bucket.downloadAuthenticated("a%41.png") {} }.exceptionOrNull()

        // Then — sent, and answered by the server, not refused on the device
        assertEquals(1, server.requests.size)
        assertFalse(failure is IllegalArgumentException, "$failure")
        client.close()
    }

    private companion object {
        const val BUCKET = "photos"
        val BYTES = "bytes".encodeToByteArray()
    }
}
