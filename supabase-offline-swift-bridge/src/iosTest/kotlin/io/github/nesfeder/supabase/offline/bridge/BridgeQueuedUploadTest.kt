// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.bridge.internal.toNSData
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An upload made with a strategy, its queue and its rejected list, through the bridge. */
class BridgeQueuedUploadTest {

    /** Whether Storage refuses uploads, as a policy does. */
    @Volatile private var refusing = false

    /** The object paths Storage holds, from the uploads it accepted. */
    private val stored = AtomicReference(emptySet<String>())

    private val server = RecordingServer { request ->
        when {
            // `info`, which the library reads when Storage refuses an upload whose earlier send may
            // have stored it: no test here refuses an upload Storage holds, so it is never there
            request.method == "GET" && request.path.startsWith("/storage/v1/object/info/") ->
                ScriptedAnswer.json(
                    400,
                    """{"statusCode":"404","error":"not_found","message":"Object not found"}""",
                )
            request.method == "POST" && request.path.startsWith("/storage/v1/object/") ->
                if (refusing) {
                    ScriptedAnswer.json(
                        400,
                        """{"statusCode":"403","error":"Unauthorized","message":"refused"}""",
                    )
                } else {
                    stored.update { paths -> paths + request.path }
                    ScriptedAnswer.json(200, """{"Key":"avatars/a.png","Id":"object-1"}""")
                }
            else -> RecordingServer.defaultAnswer(request)
        }
    }

    @Test
    fun an_upload_queued_offline_is_listed_answered_from_the_device_and_sent_later() = bridgeTest {
        // Given — offline
        val client = openTestClient(server)
        server.online = false

        // When
        val probe = CompletionProbe<BridgeUploadResult>()
        client.storage.upload(
            "avatars",
            "a.png",
            "image bytes".encodeToByteArray().toNSData(),
            "queueIfOffline",
            """{"contentType":"image/png"}""",
            probe.completion,
        )
        val outcome = assertNotNull(probe.awaitValue())
        val listed = CompletionProbe<List<BridgePendingUpload>>()
        client.storage.pendingUploads(listed.completion)
        val cached = CompletionProbe<BridgeCachedFile>()
        client.storage.cachedFile("avatars", "a.png", null, cached.completion)

        // Then — queued, listed, and its bytes on the device answer at once
        assertTrue(outcome.isQueued)
        assertNull(outcome.applied)
        val queued = assertNotNull(outcome.queued)
        assertEquals("avatars", queued.bucketId)
        assertEquals("a.png", queued.path)
        assertEquals(11L, queued.sizeBytes)
        assertEquals("image/png", queued.contentType)
        assertFalse(queued.upsert)
        assertEquals(listOf(queued.id), assertNotNull(listed.awaitValue()).map { it.id })
        assertEquals(11L, assertNotNull(cached.awaitValue()).sizeBytes)

        // When — the network is back
        server.online = true
        val synced = CompletionProbe<BridgeSyncReport>()
        client.syncPendingMutations(null, synced.completion)
        val report = assertNotNull(synced.awaitValue())
        val after = CompletionProbe<List<BridgePendingUpload>>()
        client.storage.pendingUploads(after.completion)

        // Then
        assertEquals(0, report.remainingCount)
        assertEquals(emptyList(), report.rejectedUploads)
        assertEquals(emptyList(), after.awaitValue())
        assertEquals(setOf("/storage/v1/object/avatars/a.png"), stored.load())
        client.closeAndWait()
    }

    @Test
    fun an_upload_sent_at_once_answers_with_storage_s_response() = bridgeTest {
        // Given
        val client = openTestClient(server)

        // When
        val probe = CompletionProbe<BridgeUploadResult>()
        client.storage.upload(
            "avatars",
            "a.png",
            "x".encodeToByteArray().toNSData(),
            "onlineOnly",
            null,
            probe.completion,
        )
        val outcome = assertNotNull(probe.awaitValue())

        // Then
        assertFalse(outcome.isQueued)
        assertNull(outcome.queued)
        val response = Json.parseToJsonElement(assertNotNull(outcome.applied)).jsonObject
        assertEquals("object-1", response.getValue("id").jsonPrimitive.content)
        client.closeAndWait()
    }

    @Test
    fun a_refused_upload_is_listed_with_storage_s_answer() = bridgeTest {
        // Given — queued offline, then refused by Storage
        val client = openTestClient(server)
        server.online = false
        val queued = CompletionProbe<BridgeUploadResult>()
        client.storage.upload(
            "avatars",
            "a.png",
            "x".encodeToByteArray().toNSData(),
            "queueIfOffline",
            null,
            queued.completion,
        )
        assertNotNull(queued.awaitValue())
        refusing = true
        server.online = true

        // When
        val synced = CompletionProbe<BridgeSyncReport>()
        client.syncPendingMutations(null, synced.completion)
        assertNotNull(synced.awaitValue())
        val listed = CompletionProbe<List<BridgeRejectedUpload>>()
        client.storage.rejectedUploads(listed.completion)

        // Then
        val refused = assertNotNull(listed.awaitValue()).single()
        assertEquals("a.png", refused.path)
        assertEquals(400, refused.serverError?.status)
        assertTrue(refused.isRetryable)
        client.closeAndWait()
    }

    @Test
    fun a_strategy_this_build_does_not_offer_is_refused_before_anything_is_sent() = bridgeTest {
        // Given
        val client = openTestClient(server)

        // When
        val probe = CompletionProbe<BridgeUploadResult>()
        client.storage.upload(
            "avatars",
            "a.png",
            "x".encodeToByteArray().toNSData(),
            "whenever",
            null,
            probe.completion,
        )

        // Then
        assertEquals(BridgeErrorKind.UNSUPPORTED, probe.awaitError().kind)
        assertTrue(server.requests.isEmpty())
        client.closeAndWait()
    }
}
