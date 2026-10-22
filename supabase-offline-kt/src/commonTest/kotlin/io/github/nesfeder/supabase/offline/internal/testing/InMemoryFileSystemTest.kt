// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import okio.Path.Companion.toPath
import okio.buffer

/**
 * The in-memory file system behaves as a disk does where the file cache can tell the difference: a
 * sink open on a file that is deleted under it keeps accepting writes, which reach nothing, as a
 * write to an open descriptor of an unlinked file does.
 */
class InMemoryFileSystemTest {

    private val fileSystem = InMemoryFileSystem(Clock.System)
    private val file = "/data/file.bin".toPath()

    @Test
    fun a_sink_keeps_accepting_writes_after_its_file_is_deleted() {
        // Given — a sink with some of a file written, then the file deleted under it
        fileSystem.createDirectories(file.parent!!)
        val sink = fileSystem.sink(file).buffer()
        sink.writeUtf8("ab")
        sink.flush()
        fileSystem.delete(file)

        // When — the writes go on, and a new file takes the path meanwhile
        sink.writeUtf8("cd")
        sink.flush()
        fileSystem.write(file) { writeUtf8("new") }
        sink.writeUtf8("ef")
        sink.close()

        // Then — nothing threw, and the writes since the delete reached no file
        assertEquals("new", fileSystem.read(file) { readUtf8() })
    }

    @Test
    fun a_deleted_file_stays_gone_however_much_its_sink_writes() {
        // Given
        fileSystem.createDirectories(file.parent!!)
        val sink = fileSystem.sink(file).buffer()
        fileSystem.delete(file)

        // When
        sink.writeUtf8("late")
        sink.close()

        // Then
        assertFalse(fileSystem.exists(file))
    }
}
