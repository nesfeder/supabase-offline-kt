@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.github.jan.supabase.storage.storage
import io.ktor.http.Url
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Query parameters a download URL can carry that do not change the bytes it returns. */
private val PARAMETERS_THAT_KEEP_THE_BYTES: Set<String> = setOf("cacheNonce", "download")

/**
 * The encoded path under which Storage serves image transforms, on a client whose Storage URL is
 * the project's own. [renderImagePathOf] resolves the real one from a client's configuration.
 */
internal const val DEFAULT_RENDER_IMAGE_PATH: String = "/storage/v1/render/image/"

/**
 * The encoded path under which [supabase]'s Storage serves image transforms, ending in `/`: under
 * its custom Storage URL when it has one.
 *
 * @param supabase a client with the Storage plugin installed.
 * @return the path prefix.
 */
internal fun renderImagePathOf(supabase: SupabaseClient): String =
    Url(supabase.storage.resolveUrl("render/image")).encodedPath.trimEnd('/') + "/"

/**
 * Learns which variant of an object a download fetched — the original, or an image transform — by
 * reading the request supabase-kt builds.
 *
 * supabase-kt keeps a download's transform in internal state no other module can read. What it does
 * expose is request overrides, which it runs on the request it is about to send, after the URL is
 * set. [wrap] appends one that records that URL: a transformed download goes to the Storage URL's
 * `render/image/…?width=…` rather than to its `object/…`, and its query names the transform. Only
 * a URL whose path starts with [renderImagePath] counts: an object's own path can contain
 * `render/image/` too.
 *
 * The override runs only once the request is built, after supabase-kt has resolved the access
 * token. When that fails — a third-party token provider refreshing offline, a session that is
 * required but expired — nothing is recorded. The download is then served from the original's
 * cached copy (a caller that asked for a thumbnail may get the full image, never the reverse), and
 * nothing it fetched is ever stored under a guessed variant.
 *
 * @param renderImagePath the encoded path prefix of a transformed download, ending in `/`.
 */
internal class DownloadRequestProbe(private val renderImagePath: String = DEFAULT_RENDER_IMAGE_PATH) {

    private sealed interface Observation {
        data object None : Observation
        data object Original : Observation
        class Transformed(val query: String) : Observation
    }

    private val observation = AtomicReference<Observation>(Observation.None)

    /**
     * The caller's [options] followed by the override that records the request.
     *
     * @param options the caller's download options.
     * @return options to pass to supabase-kt in their place.
     */
    fun wrap(options: DownloadOptionBuilder.() -> Unit): DownloadOptionBuilder.() -> Unit = {
        options()
        httpOverride { record(url.build()) }
    }

    /**
     * The cache entry to store what the download fetched in: the variant its request asked for.
     *
     * @return the entry, or `null` when the request was never seen — bytes whose variant is
     *   unknown are never stored — or the object cannot be cached.
     */
    fun entryToStore(cache: StorageFileCache, bucketId: String, objectPath: String): StorageCacheEntry? =
        when (val seen = observation.load()) {
            Observation.None -> null
            Observation.Original -> cache.entryFor(bucketId, objectPath)
            is Observation.Transformed -> cache.variantEntryFor(bucketId, objectPath, seen.query)
        }

    /**
     * The cache entry to serve when the download failed: the variant its request asked for, or the
     * original when the request was never built.
     *
     * @return the entry, or `null` when the object cannot be cached.
     */
    fun entryToServe(cache: StorageFileCache, bucketId: String, objectPath: String): StorageCacheEntry? =
        when (val seen = observation.load()) {
            Observation.None, Observation.Original -> cache.entryFor(bucketId, objectPath)
            is Observation.Transformed -> cache.variantEntryFor(bucketId, objectPath, seen.query)
        }

    private fun record(url: Url) {
        observation.store(
            if (url.encodedPath.startsWith(renderImagePath)) Observation.Transformed(canonicalTransform(url)) else Observation.Original,
        )
    }

    private fun canonicalTransform(url: Url): String =
        url.parameters.names()
            .filter { it !in PARAMETERS_THAT_KEEP_THE_BYTES }
            .sorted()
            .joinToString("&") { name -> "$name=${url.parameters.getAll(name).orEmpty().joinToString(",")}" }
            .ifEmpty { "render" }
}
