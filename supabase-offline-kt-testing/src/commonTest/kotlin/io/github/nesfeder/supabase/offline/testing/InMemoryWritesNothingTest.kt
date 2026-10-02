// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.offline
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * The in-memory client writes nothing to disk. A file the engine wrote through the device's file
 * system would land under the client's data directory, which no test platform lets a test create
 * (`/in-memory`), so the download would not be cached: the session below would fail at its first
 * file assertion, or, on a machine that allowed the write, at the last ones.
 */
class InMemoryWritesNothingTest {

    private val server = ScriptedServer(objectBytes = PHOTO)

    @Test
    fun a_full_session_leaves_the_file_system_untouched() = runTest {
        // Given — a client kept in memory
        val supabase = server.supabaseClient { inMemory() }
        val offline = supabase.offline
        val photos = offline.storage.from(BUCKET)

        // When — reads, a queued write, a download, a clear and a close
        offline.from(TABLE).select()
        server.online = false
        val cachedRead = offline.from(TABLE).select()
        val write =
            offline.from(TABLE).insert(JsonArray(listOf(buildJsonObject { put("title", "Tea") })))
        server.online = true
        val downloaded = photos.ensureCachedFile(PHOTO_PATH)
        server.online = false
        val servedOffline = photos.downloadAuthenticated(PHOTO_PATH)
        val cachedBeforeClear = photos.cachedFile(PHOTO_PATH)
        offline.clearLocalData()
        val cachedAfterClear = photos.cachedFile(PHOTO_PATH)
        supabase.close()

        // Then — every step worked, in memory
        assertEquals(ROWS, cachedRead.data)
        assertIs<MutationOutcome.Queued>(write)
        assertEquals(PHOTO.size.toLong(), downloaded.sizeBytes)
        assertContentEquals(PHOTO, servedOffline)
        assertEquals(downloaded.path, assertNotNull(cachedBeforeClear).path)
        assertNull(cachedAfterClear)
        // and nothing of it is on disk
        assertFalse(FileSystem.SYSTEM.exists(downloaded.path.toPath()))
        assertFalse(FileSystem.SYSTEM.exists("/in-memory".toPath()))
    }

    private companion object {
        const val TABLE = "todos"
        const val ROWS = """[{"id":1,"title":"Buy milk"}]"""
        const val BUCKET = "photos"
        const val PHOTO_PATH = "tasks/one.jpg"
        val PHOTO: ByteArray = "a photo".encodeToByteArray()
    }
}
