// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.testing

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.Clock
import okio.Buffer
import okio.FileHandle
import okio.FileMetadata
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.Timeout

/**
 * A file system held in memory, for the in-memory client's Storage file cache: nothing it does
 * reaches the device's disk. It covers what the file cache uses (directories, whole-file sources
 * and sinks, moves, deletes, metadata); file handles and symlinks are refused.
 *
 * Safe across threads: the tree is one immutable map, replaced whole by each change.
 *
 * @param clock stamps every write, as a disk stamps a file's modification time.
 */
internal class InMemoryFileSystem(private val clock: Clock) : FileSystem() {

    /** One entry of the tree. */
    private sealed interface Node {
        /** A directory; its children are the entries whose parent is its path. */
        data object Directory : Node

        /** A file: its bytes and when it was last written or marked used. */
        class File(val bytes: ByteArray, val lastModifiedAtMillis: Long) : Node
    }

    /** Every entry by its absolute, normalized path; the root always exists. */
    private val nodes = AtomicReference<Map<Path, Node>>(mapOf(ROOT to Node.Directory))

    /**
     * Sets [path]'s modification time to [epochMillis], as the file cache marks a file it used;
     * nothing happens when [path] is not a file.
     */
    fun markUsed(path: Path, epochMillis: Long) {
        val key = path.absolute()
        nodes.update { tree ->
            val file = tree[key] as? Node.File ?: return@update tree
            tree + (key to Node.File(file.bytes, epochMillis))
        }
    }

    override fun canonicalize(path: Path): Path {
        val key = path.absolute()
        if (key !in nodes.load()) throw FileNotFoundException("no such file: $path")
        return key
    }

    override fun metadataOrNull(path: Path): FileMetadata? =
        when (val node = nodes.load()[path.absolute()]) {
            null -> null
            Node.Directory -> FileMetadata(isDirectory = true)
            is Node.File ->
                FileMetadata(
                    isRegularFile = true,
                    size = node.bytes.size.toLong(),
                    lastModifiedAtMillis = node.lastModifiedAtMillis,
                )
        }

    override fun list(dir: Path): List<Path> =
        listOrNull(dir) ?: throw FileNotFoundException("no such directory: $dir")

    override fun listOrNull(dir: Path): List<Path>? {
        val key = dir.absolute()
        val tree = nodes.load()
        if (tree[key] != Node.Directory) return null
        return tree.keys.filter { it.parent == key }.sorted()
    }

    override fun openReadOnly(file: Path): FileHandle =
        throw IOException("an in-memory file system opens no file handles")

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
        throw IOException("an in-memory file system opens no file handles")

    override fun source(file: Path): Source {
        val node = nodes.load()[file.absolute()]
        if (node !is Node.File) throw FileNotFoundException("no such file: $file")
        return Buffer().write(node.bytes)
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        val key = file.absolute()
        nodes.update { tree ->
            requireDirectory(tree, key.parent, file)
            when (tree[key]) {
                Node.Directory -> throw IOException("a directory is in the way: $file")
                is Node.File -> if (mustCreate) throw IOException("already exists: $file")
                null -> Unit
            }
            tree + (key to Node.File(ByteArray(0), clock.now().toEpochMilliseconds()))
        }
        return AppendingSink(key)
    }

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        val key = file.absolute()
        nodes.update { tree ->
            requireDirectory(tree, key.parent, file)
            when (tree[key]) {
                Node.Directory -> throw IOException("a directory is in the way: $file")
                is Node.File -> tree
                null ->
                    if (mustExist) throw FileNotFoundException("no such file: $file")
                    else tree + (key to Node.File(ByteArray(0), clock.now().toEpochMilliseconds()))
            }
        }
        return AppendingSink(key)
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        val key = dir.absolute()
        nodes.update { tree ->
            when (tree[key]) {
                Node.Directory ->
                    if (mustCreate) throw IOException("already exists: $dir") else return
                is Node.File -> throw IOException("a file is in the way: $dir")
                null -> {
                    requireDirectory(tree, key.parent, dir)
                    tree + (key to Node.Directory)
                }
            }
        }
    }

    override fun atomicMove(source: Path, target: Path) {
        val from = source.absolute()
        val to = target.absolute()
        if (from == to) return
        nodes.update { tree ->
            val moved = tree[from] ?: throw FileNotFoundException("no such file: $source")
            requireDirectory(tree, to.parent, target)
            if (tree[to] == Node.Directory && tree.keys.any { it.parent == to }) {
                throw IOException("the target is a directory that is not empty: $target")
            }
            val subtree = tree.filterKeys { it == from || it.isUnder(from) }
            val rest = tree - subtree.keys - to
            rest +
                (to to moved) +
                subtree
                    .filterKeys { it != from }
                    .mapKeys { (path, _) -> to / path.relativeTo(from) }
        }
    }

    override fun delete(path: Path, mustExist: Boolean) {
        val key = path.absolute()
        nodes.update { tree ->
            when (tree[key]) {
                null ->
                    if (mustExist) throw FileNotFoundException("no such file: $path") else return
                Node.Directory ->
                    if (tree.keys.any { it.parent == key }) {
                        throw IOException("the directory is not empty: $path")
                    }
                is Node.File -> Unit
            }
            if (key == ROOT) throw IOException("the root cannot be deleted")
            tree - key
        }
    }

    override fun createSymlink(source: Path, target: Path): Unit =
        throw IOException("an in-memory file system holds no symlinks")

    /** Fails unless [directory] exists as a directory in [tree]; [path] names the caller's path. */
    private fun requireDirectory(tree: Map<Path, Node>, directory: Path?, path: Path) {
        if (directory == null || tree[directory] != Node.Directory) {
            throw FileNotFoundException("no such directory for $path")
        }
    }

    /** Appends every write to the file at [key] at once, as a disk does. */
    private inner class AppendingSink(private val key: Path) : Sink {
        private var closed = false

        override fun write(source: Buffer, byteCount: Long) {
            if (closed) throw IOException("closed")
            val bytes = source.readByteArray(byteCount)
            nodes.update { tree ->
                val file = tree[key] as? Node.File ?: throw FileNotFoundException("deleted: $key")
                tree + (key to Node.File(file.bytes + bytes, clock.now().toEpochMilliseconds()))
            }
        }

        override fun flush() = Unit

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }

    private companion object {
        /** The root directory, which always exists. */
        val ROOT: Path = "/".toPath()

        /** This path made absolute against [ROOT] and normalized. */
        fun Path.absolute(): Path = if (isAbsolute) normalized() else (ROOT / this).normalized()

        /** Whether this path lies strictly inside [directory]. */
        fun Path.isUnder(directory: Path): Boolean {
            var current = parent
            while (current != null) {
                if (current == directory) return true
                current = current.parent
            }
            return false
        }
    }
}
