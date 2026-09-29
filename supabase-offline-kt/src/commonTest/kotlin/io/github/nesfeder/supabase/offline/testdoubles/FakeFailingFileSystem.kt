// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import okio.Sink
import okio.Timeout

/**
 * The real file system, with failures a test can switch on: a disk that fills up part-way through
 * writing a file, and a rename the file system refuses. Every other operation reaches the disk.
 *
 * @property failWritesAfterBytes when set, every file sink opened from now on throws once that many
 *   bytes have been written to it.
 * @property failMoves when `true`, [atomicMove] throws.
 * @property onSinkOpened runs once a file sink is open, with the file already on disk, on the
 *   thread that opened it.
 * @property onMove runs as [atomicMove] starts, before it moves or refuses.
 */
internal class FakeFailingFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {

    var failWritesAfterBytes: Long? = null

    var failMoves: Boolean = false

    var onSinkOpened: ((Path) -> Unit)? = null

    var onMove: ((source: Path, target: Path) -> Unit)? = null

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

    override fun atomicMove(source: Path, target: Path) {
        onMove?.invoke(source, target)
        if (failMoves) throw IOException("Operation not permitted")
        super.atomicMove(source, target)
    }
}
