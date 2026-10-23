// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import okio.Buffer
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import okio.Sink
import okio.Timeout

/**
 * The real file system with the failures a test can switch on: a disk that fills part-way through a
 * file, a refused rename, a refused delete and a refused look at a path. Every other operation
 * reaches the disk.
 */
internal class FakeFailingFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {

    /** When set, every file sink opened after it is set throws once that many bytes are written. */
    var failWritesAfterBytes: Long? = null

    /** When `true`, [atomicMove] throws. */
    var failMoves: Boolean = false

    /** When `true`, [delete] throws, so a recursive delete fails too. */
    var failDeletes: Boolean = false

    /**
     * When it accepts a path, [metadataOrNull] throws for that path, as a `stat` the system refuses
     * does, and so does every check that a path exists. `null` never fails.
     */
    var failMetadataOf: ((Path) -> Boolean)? = null

    /** Runs once a file sink is open, with the file on disk, on the thread that opened it. */
    var onSinkOpened: ((Path) -> Unit)? = null

    /** Runs as [atomicMove] starts, before it moves or refuses. */
    var onMove: ((source: Path, target: Path) -> Unit)? = null

    /** Runs as [metadataOrNull] starts, with the path, on the thread that asked. */
    var onMetadata: ((Path) -> Unit)? = null

    /** Every file a sink was opened on, in order. */
    val sinksOpened: MutableList<Path> = mutableListOf()

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        sinksOpened += file
        val real = super.sink(file, mustCreate)
        onSinkOpened?.invoke(file)
        val limit = failWritesAfterBytes ?: return real
        return object : Sink by real {
            private var written = 0L

            override fun write(source: Buffer, byteCount: Long) {
                val allowed = (limit - written).coerceAtLeast(0L)
                if (byteCount > allowed) {
                    if (allowed > 0) real.write(source, allowed)
                    written += allowed
                    real.flush()
                    throw IOException("No space left on device")
                }
                real.write(source, byteCount)
                written += byteCount
            }

            override fun timeout(): Timeout = real.timeout()
        }
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        onMetadata?.invoke(path)
        if (failMetadataOf?.invoke(path) == true) throw IOException("Permission denied")
        return super.metadataOrNull(path)
    }

    override fun delete(path: Path, mustExist: Boolean) {
        if (failDeletes) throw IOException("Operation not permitted")
        super.delete(path, mustExist)
    }

    override fun atomicMove(source: Path, target: Path) {
        onMove?.invoke(source, target)
        if (failMoves) throw IOException("Operation not permitted")
        super.atomicMove(source, target)
    }
}
