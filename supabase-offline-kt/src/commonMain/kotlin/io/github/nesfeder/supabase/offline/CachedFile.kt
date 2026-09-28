package io.github.nesfeder.supabase.offline

import kotlin.time.Instant

/**
 * A Storage object held in the offline file cache.
 *
 * The file is the cache's own copy: read it, open it, hand it to an image loader, but do not move,
 * rename or write to it. The cache may delete it later — when the object is uploaded or updated
 * again through the offline bucket, when the server reports it gone, when the cache grows past its
 * size bound and this file is the least recently used, or when local data is cleared — so open it
 * soon after you receive it, or copy it if you need it to outlive those.
 *
 * @property path absolute path of the cached file on this device.
 * @property fetchedAt when the file was downloaded from the server. Nothing revalidates a cached
 *   file, so compare this with your own freshness rule and download again when it is too old.
 * @property sizeBytes size of the file, in bytes.
 */
@ConsistentCopyVisibility
public data class CachedFile internal constructor(
    public val path: String,
    public val fetchedAt: Instant,
    public val sizeBytes: Long,
)
