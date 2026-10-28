// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * An object and a folder of the same name, `album` and `album/cover.png`, map onto one place on
 * disk: a file and a directory of the same name. Whichever is cached second is stored under its
 * hashed file instead, its row records that, and both are served, kept apart and dropped as any
 * other cached object is. On the real file system, which is where the two names collide, looked at
 * as okio on iOS looks at it ([NativeLikeFileSystem]), so the JVM suite sees what the iOS one does.
 * Names that differ only in case collide the same way on a case-insensitive volume.
 */
class StorageObjectAndFolderTest {

    private lateinit var root: Path
    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var delegate: FakeBucketApi

    @BeforeTest
    fun setUp() {
        root =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-object-and-folder-${Random.nextLong().toULong()}"
        dataDirectory = root / "data"
        store = newTestStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
        goOnline()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }

    private fun bucket(directory: Path = dataDirectory) =
        bucketOn(NativeLikeFileSystem(FileSystem.SYSTEM), directory)

    private fun bucketOn(fileSystem: FileSystem, directory: Path = dataDirectory) =
        newTestBucketApi(
            delegate = delegate,
            baseDirectory = directory,
            store = store,
            cache =
                newTestFileCache(baseDirectory = directory, store = store, fileSystem = fileSystem),
        )

    private fun goOnline() {
        delegate.onDownload = { path -> assertNotNull(CONTENTS[path], path).encodeToByteArray() }
    }

    private fun goOffline() {
        delegate.onDownload = { throw IOException("offline") }
    }

    /** Where [path] is stored under its own name. */
    private fun plainFile(path: String, directory: Path = dataDirectory): Path =
        path.split('/').fold(directory / STORAGE_DIRECTORY_NAME / BUCKET) { parent, segment ->
            parent / segment
        }

    /** Where [path] is stored when its own name is taken. */
    private fun hashedFile(path: String, directory: Path = dataDirectory): Path =
        hashedFilePath(directory, cachedFileKeyOf(BUCKET, path))

    private fun read(file: CachedFile): String =
        FileSystem.SYSTEM.read(file.path.toPath()) { readUtf8() }

    private suspend fun recordedPath(path: String): String? =
        store.getStorageByKey(BUCKET, path)?.localPath

    /** Asserts that [path] is served from the cache alone, as every read does it, from [file]. */
    private suspend fun assertServedOffline(path: String, file: Path) {
        val bucket = bucket(file.dataDirectoryOf())
        assertEquals(CONTENTS[path], bucket.downloadAuthenticated(path) {}.decodeToString(), path)
        val channel = ByteChannel(autoFlush = true)
        bucket.downloadAuthenticated(path, channel) {}
        assertEquals(CONTENTS[path], channel.readRemaining().readByteArray().decodeToString(), path)
        val cached = assertNotNull(bucket.cachedFile(path), path)
        assertEquals(file.toString(), cached.path, path)
        assertEquals(CONTENTS[path], read(bucket.ensureCachedFile(path)), path)
    }

    /**
     * The data directory [file] is cached under: `<data>/storage/…` or `<data>/storage-hashed/…`.
     */
    private fun Path.dataDirectoryOf(): Path =
        generateSequence(this) { it.parent }
            .first { it.name == STORAGE_DIRECTORY_NAME || it.name == HASHED_DIRECTORY_NAME }
            .parent!!

    @Test
    fun an_object_under_the_name_of_a_cached_object_is_cached_under_its_hashed_file() = runTest {
        // Given — album is a file where album/cover.png needs a directory
        bucket().ensureCachedFile(ALBUM)

        // When
        val cover = bucket().ensureCachedFile(COVER)

        // Then — stored and recorded under its hashed file; album keeps its own
        assertEquals(hashedFile(COVER).toString(), cover.path)
        assertEquals(CONTENTS[COVER], read(cover))
        assertEquals(cover.path, recordedPath(COVER))
        assertEquals(CONTENTS[ALBUM], read(assertNotNull(bucket().cachedFile(ALBUM))))
        assertEquals(plainFile(ALBUM).toString(), recordedPath(ALBUM))
    }

    @Test
    fun an_object_named_like_a_cached_folder_is_cached_under_its_hashed_file() = runTest {
        // Given — album is a directory where the object album needs a file
        bucket().ensureCachedFile(COVER)

        // When
        val album = bucket().ensureCachedFile(ALBUM)

        // Then
        assertEquals(hashedFile(ALBUM).toString(), album.path)
        assertEquals(CONTENTS[ALBUM], read(album))
        assertEquals(album.path, recordedPath(ALBUM))
        assertEquals(plainFile(COVER).toString(), recordedPath(COVER))
    }

    @Test
    fun both_are_served_offline_when_the_object_was_cached_first() = runTest {
        // Given
        bucket().downloadAuthenticated(ALBUM) {}
        bucket().downloadAuthenticated(COVER) {}

        // When
        goOffline()

        // Then — every read: bytes, a channel, the cached file
        assertServedOffline(ALBUM, plainFile(ALBUM))
        assertServedOffline(COVER, hashedFile(COVER))
    }

    @Test
    fun both_are_served_offline_when_the_folder_was_cached_first() = runTest {
        // Given
        bucket().downloadAuthenticated(COVER) {}
        bucket().downloadAuthenticated(ALBUM) {}

        // When
        goOffline()

        // Then
        assertServedOffline(COVER, plainFile(COVER))
        assertServedOffline(ALBUM, hashedFile(ALBUM))
    }

    @Test
    fun a_channel_download_under_a_cached_objects_name_is_fetched_once_and_kept() = runTest {
        // Given
        bucket().ensureCachedFile(ALBUM)
        val channel = ByteChannel(autoFlush = true)

        // When
        bucket().downloadAuthenticated(COVER, channel) {}

        // Then — one request, whose bytes the caller got and the cache kept
        assertEquals(CONTENTS[COVER], channel.readRemaining().readByteArray().decodeToString())
        assertEquals(listOf(ALBUM, COVER), delegate.channelDownloadPaths)
        assertEquals(hashedFile(COVER).toString(), assertNotNull(bucket().cachedFile(COVER)).path)
    }

    @Test
    fun writing_the_object_leaves_the_copy_stored_under_its_name() = runTest {
        // Given
        bucket().ensureCachedFile(ALBUM)
        bucket().ensureCachedFile(COVER)

        // When — album is replaced on the server
        bucket().update(ALBUM, "NEW".encodeToByteArray()) {}

        // Then — album's copy is gone; the hashed copy under its name is not
        assertNull(bucket().cachedFile(ALBUM))
        assertFalse(FileSystem.SYSTEM.exists(plainFile(ALBUM)))
        goOffline()
        assertServedOffline(COVER, hashedFile(COVER))
    }

    @Test
    fun writing_the_object_under_the_name_leaves_the_object() = runTest {
        // Given
        bucket().ensureCachedFile(ALBUM)
        bucket().ensureCachedFile(COVER)

        // When
        bucket().update(COVER, "NEW".encodeToByteArray()) {}

        // Then — the hashed copy is gone with its row; album is served still
        assertFalse(FileSystem.SYSTEM.exists(hashedFile(COVER)))
        assertNull(recordedPath(COVER))
        goOffline()
        assertServedOffline(ALBUM, plainFile(ALBUM))
    }

    @Test
    fun evicting_a_hashed_object_deletes_its_file_and_row_and_leaves_the_folder() = runTest {
        // Given
        bucket().ensureCachedFile(COVER)
        bucket().ensureCachedFile(ALBUM)

        // When
        bucket().evictCachedFile(ALBUM)

        // Then — the hashed file and its row are gone; the folder's object is served still
        assertFalse(FileSystem.SYSTEM.exists(hashedFile(ALBUM)))
        assertNull(recordedPath(ALBUM))
        assertNull(bucket().cachedFile(ALBUM))
        goOffline()
        assertServedOffline(COVER, plainFile(COVER))
    }

    @Test
    fun evicting_a_hashed_object_under_the_name_deletes_its_file_and_row() = runTest {
        // Given
        bucket().ensureCachedFile(ALBUM)
        bucket().ensureCachedFile(COVER)

        // When
        bucket().evictCachedFile(COVER)

        // Then
        assertFalse(FileSystem.SYSTEM.exists(hashedFile(COVER)))
        assertNull(recordedPath(COVER))
        assertNull(bucket().cachedFile(COVER))
        assertNotNull(bucket().cachedFile(ALBUM))
    }

    @Test
    fun an_object_two_folders_under_a_cached_object_is_cached_and_evicted() = runTest {
        // Given — album is a file where album/2024/ needs two directories
        bucket().ensureCachedFile(ALBUM)
        val nested = bucket().ensureCachedFile(NESTED)
        assertEquals(hashedFile(NESTED).toString(), nested.path)

        // When
        bucket().evictCachedFile(NESTED)

        // Then — the hashed file and its row are gone; album is served still
        assertFalse(FileSystem.SYSTEM.exists(hashedFile(NESTED)))
        assertNull(recordedPath(NESTED))
        goOffline()
        assertServedOffline(ALBUM, plainFile(ALBUM))
    }

    @Test
    fun the_size_bound_evicts_a_hashed_copy_with_its_row() = runTest {
        // Given — album, hashed, is the least recently used of three files over a 10-byte bound
        var now = 0L
        val cache =
            newTestFileCache(
                baseDirectory = dataDirectory,
                store = store,
                maxSizeBytes = 10,
                nowMillis = { now },
                fileSystem = NativeLikeFileSystem(FileSystem.SYSTEM),
            )
        val bucket =
            newTestBucketApi(
                delegate = delegate,
                baseDirectory = dataDirectory,
                store = store,
                cache = cache,
            )
        delegate.onDownload = { ByteArray(4) { 7 } }
        now = 1 * MINUTE
        bucket.downloadAuthenticated(COVER) {}
        now = 2 * MINUTE
        bucket.downloadAuthenticated(ALBUM) {}
        now = 3 * MINUTE
        assertNotNull(bucket.cachedFile(COVER))
        assertTrue(FileSystem.SYSTEM.exists(hashedFile(ALBUM)))

        // When
        now = 4 * MINUTE
        bucket.downloadAuthenticated(OTHER) {}

        // Then — album's hashed file and row went; the others stayed
        assertFalse(FileSystem.SYSTEM.exists(hashedFile(ALBUM)))
        assertNull(recordedPath(ALBUM))
        assertNotNull(bucket.cachedFile(COVER))
        assertNotNull(bucket.cachedFile(OTHER))
        assertEquals(8, store.cachedFileDao.totalSizeBytes())
    }

    @Test
    fun clearFileCache_deletes_a_hashed_copy_and_its_row() = runTest {
        // Given — through a whole client, so the call is the public one
        val server = RecordingStorageServer()
        CONTENTS.forEach { (path, content) ->
            server.objects["$BUCKET/$path"] = content.encodeToByteArray()
        }
        val cachedFileDao = InMemoryCachedFileDao()
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                cachedFileDao = cachedFileDao,
                baseDirectoryProvider = { dataDirectory },
                fileSystem = NativeLikeFileSystem(FileSystem.SYSTEM),
            )
        client.storage.from(BUCKET).ensureCachedFile(ALBUM)
        val cover = client.storage.from(BUCKET).ensureCachedFile(COVER)
        assertTrue(HASHED_DIRECTORY_NAME in cover.path)

        // When
        client.storage.clearFileCache()

        // Then — no file and no row
        assertFalse(FileSystem.SYSTEM.exists(cover.path.toPath()))
        assertEquals(emptyList(), cachedFileDao.all())
        assertEquals(
            emptyList(),
            FileSystem.SYSTEM.listRecursively(dataDirectory)
                .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
                .toList(),
        )
        assertNull(client.storage.from(BUCKET).cachedFile(COVER))
        client.close()
    }

    @Test
    fun a_hashed_copy_downloaded_again_stays_where_its_path_was_handed_out() = runTest {
        // Given — cover.png went to its hashed file, whose path was handed out; then album, in the
        // way of its own file, was evicted
        bucket().ensureCachedFile(ALBUM)
        val handedOut = bucket().ensureCachedFile(COVER).path
        bucket().evictCachedFile(ALBUM)

        // When — downloaded again
        bucket().downloadAuthenticated(COVER) {}

        // Then — still in the hashed file, so the path handed out reads the new copy
        assertEquals(hashedFile(COVER).toString(), handedOut)
        assertEquals(handedOut, recordedPath(COVER))
        assertEquals(CONTENTS[COVER], FileSystem.SYSTEM.read(handedOut.toPath()) { readUtf8() })
        assertFalse(FileSystem.SYSTEM.exists(plainFile(COVER)))
        goOffline()
        assertServedOffline(COVER, hashedFile(COVER))
    }

    @Test
    fun a_download_into_the_own_file_deletes_a_hashed_file_a_crash_left() = runTest {
        // Given — album's row and file in its own place, and a hashed file of album's beside them
        val file = plainFile(ALBUM)
        FileSystem.SYSTEM.createDirectories(file.parent!!)
        FileSystem.SYSTEM.write(file) { writeUtf8("OLD!!") }
        store.addStorage(BUCKET, ALBUM, file, 5, null)
        FileSystem.SYSTEM.createDirectories(hashedFile(ALBUM).parent!!)
        FileSystem.SYSTEM.write(hashedFile(ALBUM)) { writeUtf8("STALE") }

        // When
        bucket().downloadAuthenticated(ALBUM) {}

        // Then — the new copy is in the own file, and the hashed one is gone
        assertEquals(file.toString(), recordedPath(ALBUM))
        assertFalse(FileSystem.SYSTEM.exists(hashedFile(ALBUM)))
        goOffline()
        assertServedOffline(ALBUM, file)
    }

    @Test
    fun a_bytes_download_under_a_cached_objects_name_returns_its_bytes_and_keeps_them() = runTest {
        // Given
        bucket().ensureCachedFile(ALBUM)

        // When
        val bytes = bucket().downloadAuthenticated(COVER) {}

        // Then
        assertEquals(CONTENTS[COVER], bytes.decodeToString())
        assertEquals(hashedFile(COVER).toString(), recordedPath(COVER))
    }

    @Test
    fun names_that_differ_only_in_case_keep_their_own_bytes_on_a_case_insensitive_volume() =
        runTest {
            // Given — a volume where photo and PHOTO are one file, as on the iOS simulator or a Mac
            val bucket =
                bucketOn(
                    NativeLikeFileSystem(
                        CaseInsensitiveFileSystem(FileSystem.SYSTEM, dataDirectory)
                    )
                )
            bucket.ensureCachedFile(LOWER)

            // When
            val upper = bucket.ensureCachedFile(UPPER)

            // Then — the second went to its hashed file; each keeps its own bytes, and evicting it
            // leaves the first's file
            assertEquals(hashedFile(UPPER).toString(), upper.path)
            assertEquals(CONTENTS[UPPER], read(upper))
            assertEquals(CONTENTS[LOWER], read(assertNotNull(bucket.cachedFile(LOWER))))
            bucket.evictCachedFile(UPPER)
            assertEquals(CONTENTS[LOWER], read(assertNotNull(bucket.cachedFile(LOWER))))
        }

    @Test
    fun a_row_an_earlier_build_wrote_at_the_plain_path_still_serves_its_file() = runTest {
        // Given — an earlier build cached album at its own name, and recorded that
        val file = plainFile(ALBUM)
        FileSystem.SYSTEM.createDirectories(file.parent!!)
        FileSystem.SYSTEM.write(file) { writeUtf8(CONTENTS.getValue(ALBUM)) }
        store.addStorage(BUCKET, ALBUM, file, CONTENTS.getValue(ALBUM).length.toLong(), null)

        // When — this build caches the object under its name
        bucket().ensureCachedFile(COVER)

        // Then — both are served
        goOffline()
        assertServedOffline(ALBUM, plainFile(ALBUM))
        assertServedOffline(COVER, hashedFile(COVER))
    }

    @Test
    fun a_row_whose_place_became_a_folder_is_a_miss_and_the_object_goes_to_its_hashed_file() =
        runTest {
            // Given — an earlier build's row for album, whose file is gone
            FileSystem.SYSTEM.createDirectories(plainFile(ALBUM).parent!!)
            store.addStorage(BUCKET, ALBUM, plainFile(ALBUM), 5, null)
            assertNull(bucket().cachedFile(ALBUM))
            // ... and then a folder of that name in its place
            bucket().ensureCachedFile(COVER)
            assertNull(bucket().cachedFile(ALBUM))

            // When
            val album = bucket().ensureCachedFile(ALBUM)

            // Then — stored in its hashed file; the folder is left as it is
            assertEquals(hashedFile(ALBUM).toString(), album.path)
            assertEquals(album.path, recordedPath(ALBUM))
            assertTrue(FileSystem.SYSTEM.metadata(plainFile(ALBUM)).isDirectory)
            goOffline()
            assertServedOffline(ALBUM, hashedFile(ALBUM))
            assertServedOffline(COVER, plainFile(COVER))
        }

    @Test
    fun a_download_over_a_row_of_another_mapping_goes_to_the_hashed_file_when_a_folder_is_there() =
        runTest {
            // Given — album's row records a file of a mapping this version does not use, and a
            // folder of album's name is cached
            val outdated = dataDirectory / STORAGE_DIRECTORY_NAME / ALBUM
            FileSystem.SYSTEM.createDirectories(outdated.parent!!)
            FileSystem.SYSTEM.write(outdated) { writeUtf8("OLD") }
            store.addStorage(BUCKET, ALBUM, outdated, 3, null)
            bucket().ensureCachedFile(COVER)

            // When — album is downloaded again, online
            bucket().downloadAuthenticated(ALBUM) {}

            // Then — the row records album's hashed file, which is served
            assertEquals(hashedFile(ALBUM).toString(), recordedPath(ALBUM))
            goOffline()
            assertServedOffline(ALBUM, hashedFile(ALBUM))
        }

    @Test
    fun a_hashed_copy_is_served_after_the_app_container_moves() = runTest {
        // Given — album went to its hashed file in container A; then the container moved, as iOS
        // moves it, and the row still records the path in A
        val before = root / "containerA" / "data"
        bucket(before).ensureCachedFile(COVER)
        bucket(before).ensureCachedFile(ALBUM)
        FileSystem.SYSTEM.atomicMove(root / "containerA", root / "containerB")
        val after = root / "containerB" / "data"

        // When
        goOffline()

        // Then — served from the hashed file in B
        assertEquals(hashedFile(ALBUM, before).toString(), recordedPath(ALBUM))
        assertServedOffline(ALBUM, hashedFile(ALBUM, after))
        assertServedOffline(COVER, plainFile(COVER, after))
    }

    @Test
    fun a_hashed_copy_moved_to_a_free_name_goes_to_its_own_file() = runTest {
        // Given
        bucket().ensureCachedFile(COVER)
        bucket().ensureCachedFile(ALBUM)

        // When
        bucket().move(ALBUM, MOVED)

        // Then — the destination has album's bytes at its own name; the hashed file is gone
        assertFalse(FileSystem.SYSTEM.exists(hashedFile(ALBUM)))
        assertNull(recordedPath(ALBUM))
        val moved = assertNotNull(bucket().cachedFile(MOVED))
        assertEquals(plainFile(MOVED).toString(), moved.path)
        assertEquals(CONTENTS[ALBUM], read(moved))
    }

    @Test
    fun a_copy_moved_onto_a_cached_folders_name_goes_to_its_hashed_file() = runTest {
        // Given
        bucket().ensureCachedFile(MOVED)
        bucket().ensureCachedFile(COVER)

        // When
        bucket().move(MOVED, ALBUM)

        // Then
        assertFalse(FileSystem.SYSTEM.exists(plainFile(MOVED)))
        val album = assertNotNull(bucket().cachedFile(ALBUM))
        assertEquals(hashedFile(ALBUM).toString(), album.path)
        assertEquals(CONTENTS[MOVED], read(album))
        assertEquals(album.path, recordedPath(ALBUM))
    }

    /**
     * The file system with okio's Kotlin/Native lookup: a path under a regular file fails with "Not
     * a directory" (`ENOTDIR`), where okio on the JVM reports nothing there.
     */
    private class NativeLikeFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        override fun metadataOrNull(path: Path): FileMetadata? {
            var parent = path.parent
            while (parent != null) {
                if (delegate.metadataOrNull(parent)?.isRegularFile == true) {
                    throw IOException("Not a directory: $path")
                }
                parent = parent.parent
            }
            return delegate.metadataOrNull(path)
        }
    }

    /**
     * A case-insensitive volume on any host: every path below [root] reaches the file its lowercase
     * form names.
     */
    private class CaseInsensitiveFileSystem(delegate: FileSystem, root: Path) :
        ForwardingFileSystem(delegate) {
        private val root = root.normalized()

        override fun onPathParameter(
            path: Path,
            functionName: String,
            parameterName: String,
        ): Path =
            if (path.normalized().isStrictlyInside(root)) {
                root / path.normalized().relativeTo(root).toString().lowercase()
            } else {
                path
            }
    }

    private companion object {
        const val BUCKET = "media"
        const val ALBUM = "album"
        const val COVER = "album/cover.png"
        const val NESTED = "album/2024/cover.png"
        const val OTHER = "other.png"
        const val MOVED = "single.png"
        const val LOWER = "photo"
        const val UPPER = "PHOTO"
        const val MINUTE = 60_000L

        val CONTENTS =
            mapOf(
                ALBUM to "ALBUM",
                COVER to "COVER",
                NESTED to "NESTED",
                OTHER to "OTHER",
                MOVED to "SINGLE",
                LOWER to "lower",
                UPPER to "UPPER",
            )
    }
}
