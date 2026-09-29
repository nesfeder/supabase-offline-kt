// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.storage.resumable.CachePair
import io.github.jan.supabase.storage.resumable.Fingerprint
import io.github.jan.supabase.storage.resumable.ResumableCache
import io.github.jan.supabase.storage.resumable.ResumableCacheEntry

/**
 * A resumable-upload cache held in a map. supabase-kt's own `MemoryResumableCache` cannot list its
 * entries (it decodes each key as an entry and throws), so an upload continued through
 * `continuePreviousUploads` needs this one.
 */
internal class FakeResumableCache : ResumableCache {

    private val entries = mutableMapOf<Fingerprint, ResumableCacheEntry>()

    override suspend fun set(fingerprint: Fingerprint, entry: ResumableCacheEntry) {
        entries[fingerprint] = entry
    }

    override suspend fun get(fingerprint: Fingerprint): ResumableCacheEntry? = entries[fingerprint]

    override suspend fun remove(fingerprint: Fingerprint) {
        entries.remove(fingerprint)
    }

    override suspend fun clear() {
        entries.clear()
    }

    override suspend fun entries(): List<CachePair> = entries.map { (fingerprint, entry) ->
        fingerprint to entry
    }
}
