// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.bridge.internal.toByteArray
import io.github.nesfeder.supabase.offline.bridge.internal.toNSData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSData

/** The offline bucket and its file cache, through the bridge. */
class BridgeStorageTest {

    private val server = RecordingServer { request ->
        when {
            request.method == "POST" && request.path.startsWith("/storage/v1/object/list/") ->
                ScriptedAnswer.json(
                    200,
                    """[{"name":"a.png","id":"1","updated_at":null,"created_at":null,"last_accessed_at":null,"metadata":null}]""",
                )
            request.method == "POST" && request.path.startsWith("/storage/v1/object/sign/") ->
                ScriptedAnswer.json(200, """{"signedURL":"/object/sign/avatars/a.png?token=t"}""")
            request.method == "POST" || request.method == "PUT" ->
                ScriptedAnswer.json(200, """{"Key":"avatars/a.png","Id":"object-1"}""")
            else -> RecordingServer.defaultAnswer(request)
        }
    }

    @Test
    fun an_upload_sends_the_bytes_and_answers_with_the_object() = bridgeTest {
        // Given
        val client = openTestClient(server)

        // When
        val probe = CompletionProbe<String>()
        client.storage.upload(
            "avatars",
            "a.png",
            "image bytes".encodeToByteArray().toNSData(),
            """{"contentType":"image/png","upsert":true}""",
            probe.completion,
        )
        val response = Json.parseToJsonElement(assertNotNull(probe.awaitValue())).jsonObject

        // Then
        assertEquals("a.png", response.getValue("path").jsonPrimitive.content)
        assertEquals("object-1", response.getValue("id").jsonPrimitive.content)
        val upload = server.requests.single()
        assertEquals("image bytes", upload.body)
        assertEquals("true", upload.header("x-upsert"))
        client.closeAndWait()
    }

    @Test
    fun a_download_is_cached_and_served_from_the_cache_offline() = bridgeTest {
        // Given — one download online
        val client = openTestClient(server)
        val online = CompletionProbe<NSData>()
        client.storage.download("avatars", "a.png", false, null, online.completion)
        val bytes = assertNotNull(online.awaitValue()).toByteArray().decodeToString()

        // When — the network drops
        server.online = false
        val offline = CompletionProbe<NSData>()
        client.storage.download("avatars", "a.png", false, null, offline.completion)
        val cached = CompletionProbe<BridgeCachedFile>()
        client.storage.cachedFile("avatars", "a.png", null, cached.completion)
        val ensured = CompletionProbe<BridgeCachedFile>()
        client.storage.ensureCachedFile("avatars", "a.png", false, ensured.completion)
        val missing = CompletionProbe<BridgeCachedFile>()
        client.storage.cachedFile("avatars", "never-downloaded.png", null, missing.completion)

        // Then
        assertEquals("a photo", bytes)
        assertEquals("a photo", assertNotNull(offline.awaitValue()).toByteArray().decodeToString())
        val file = assertNotNull(cached.awaitValue())
        assertEquals(7L, file.sizeBytes)
        assertEquals(file.path, ensured.awaitValue()?.path)
        assertEquals(null to null, missing.await())
        client.closeAndWait()
    }

    @Test
    fun a_transform_finds_its_own_copy_and_evictions_and_the_clear_drop_copies() = bridgeTest {
        // Given — the original and a thumbnail, each downloaded once
        val client = openTestClient(server)
        val thumbnail = """{"width":64,"height":64}"""
        for (transform in listOf(null, thumbnail)) {
            val download = CompletionProbe<NSData>()
            client.storage.download("avatars", "a.png", false, transform, download.completion)
            assertNotNull(download.awaitValue())
        }

        // When — each copy is looked up, the thumbnail evicted, then the file cache cleared
        val variant = cachedFileOf(client, thumbnail)
        val otherSize = cachedFileOf(client, """{"width":32,"height":32}""")
        val thumbnailEvicted = awaitErrorOnly {
            client.storage.evictCachedFile("avatars", "a.png", thumbnail, it)
        }
        val variantAfterEviction = cachedFileOf(client, thumbnail)
        val originalAfterEviction = cachedFileOf(client, null)
        val cleared = awaitErrorOnly { client.storage.clearFileCache(it) }
        val originalAfterClear = cachedFileOf(client, null)

        // Then — the thumbnail had a copy of its own, which went alone; the clear took the rest
        assertNotNull(variant)
        assertNull(otherSize)
        assertNull(thumbnailEvicted)
        assertNull(variantAfterEviction)
        assertNotNull(originalAfterEviction)
        assertTrue(variant.path != originalAfterEviction.path)
        assertNull(cleared)
        assertNull(originalAfterClear)
        client.closeAndWait()
    }

    /** The cached copy of `avatars/a.png` with [transform], or `null`, through the bridge. */
    private suspend fun cachedFileOf(client: BridgeClient, transform: String?): BridgeCachedFile? {
        val probe = CompletionProbe<BridgeCachedFile>()
        client.storage.cachedFile("avatars", "a.png", transform, probe.completion)
        val (file, error) = probe.await()
        assertNull(error)
        return file
    }

    @Test
    fun a_pin_holds_until_unpinned_and_a_path_that_cannot_be_cached_is_refused() = bridgeTest {
        // Given
        val client = openTestClient(server)

        // When — pinned before any download, then unpinned; and a path the cache cannot hold
        val pinned = awaitErrorOnly { client.storage.pinCachedFile("avatars", "a.png", it) }
        val whilePinned = isPinned(client, "a.png")
        val unpinned = awaitErrorOnly { client.storage.unpinCachedFile("avatars", "a.png", it) }
        val afterUnpin = isPinned(client, "a.png")
        val refused = awaitErrorOnly { client.storage.pinCachedFile("avatars", "../a.png", it) }

        // Then — nothing was sent
        assertNull(pinned)
        assertEquals(true, whilePinned)
        assertNull(unpinned)
        assertEquals(false, afterUnpin)
        assertEquals(BridgeErrorKind.INVALID_ARGUMENT, refused?.kind)
        assertTrue(server.requests.isEmpty())
        client.closeAndWait()
    }

    /** Whether `avatars/[path]` is pinned, through the bridge. */
    private suspend fun isPinned(client: BridgeClient, path: String): Boolean? {
        val probe = CompletionProbe<Boolean>()
        client.storage.isCachedFilePinned("avatars", path, probe.completion)
        val (pinned, error) = probe.await()
        assertNull(error)
        return pinned
    }

    @Test
    fun listings_urls_and_existence_answer_as_supabase_kt_does() = bridgeTest {
        // Given
        val client = openTestClient(server)

        // When
        val listed = CompletionProbe<String>()
        client.storage.list(
            "avatars",
            "",
            """{"limit":10,"sortBy":{"column":"name","order":"desc"}}""",
            listed.completion,
        )
        val signed = CompletionProbe<String>()
        client.storage.createSignedUrl(
            "avatars",
            "a.png",
            60,
            """{"width":100,"resize":"cover"}""",
            signed.completion,
        )
        val exists = CompletionProbe<Boolean>()
        client.storage.exists("avatars", "a.png", exists.completion)
        val publicUrl = client.storage.publicUrl("avatars", "a.png")
        val deleted = awaitErrorOnly { client.storage.delete("avatars", listOf("a.png"), it) }

        // Then
        val objects = Json.parseToJsonElement(assertNotNull(listed.awaitValue())).jsonArray
        assertEquals("a.png", objects.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertTrue(assertNotNull(signed.awaitValue()).contains("token=t"))
        assertEquals(true, exists.awaitValue())
        assertNull(publicUrl.error)
        assertTrue(publicUrl.value.orEmpty().endsWith("/storage/v1/object/public/avatars/a.png"))
        assertNull(deleted)
        val listing = server.requests.first { it.path.startsWith("/storage/v1/object/list/") }
        assertTrue(listing.body.contains("\"limit\":10"))
        client.closeAndWait()
    }

    @Test
    fun malformed_options_are_refused_before_anything_is_sent() = bridgeTest {
        // Given
        val client = openTestClient(server)

        // When
        val probe = CompletionProbe<String>()
        client.storage.upload(
            "avatars",
            "a.png",
            "x".encodeToByteArray().toNSData(),
            """{"upsert":"yes"}""",
            probe.completion,
        )
        val transform = CompletionProbe<NSData>()
        client.storage.download(
            "avatars",
            "a.png",
            false,
            """{"resize":"stretch"}""",
            transform.completion,
        )

        // Then
        assertEquals(BridgeErrorKind.INVALID_REQUEST, probe.awaitError().kind)
        assertEquals(BridgeErrorKind.UNSUPPORTED, transform.awaitError().kind)
        assertTrue(server.requests.isEmpty())
        client.closeAndWait()
    }
}
