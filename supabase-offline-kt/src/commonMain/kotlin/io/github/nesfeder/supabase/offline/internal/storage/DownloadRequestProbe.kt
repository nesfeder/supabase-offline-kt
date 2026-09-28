@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.ktor.http.Url
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Query parameters a download URL can carry that do not change the bytes it returns. */
private val PARAMETERS_THAT_KEEP_THE_BYTES: Set<String> = setOf("cacheNonce", "download")

/**
 * Learns which variant of an object a download fetched — the original, or an image transform — by
 * reading the request supabase-kt builds.
 *
 * supabase-kt keeps a download's transform in internal state no other module can read. What it does
 * expose is request overrides, which it runs on the request it is about to send, after the URL is
 * set. [wrap] appends one that records that URL: a transformed download goes to
 * `…/render/image/…?width=…` rather than `…/object/…`, and its query names the transform. The
 * override runs whenever the request is built — before any bytes arrive, and even when the send
 * then fails offline — so a download's cache entry is known in both of the places it is needed.
 */
internal class DownloadRequestProbe {

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
     * The cache entry of what the download fetched.
     *
     * @return the entry, or `null` when the request was never built (so the variant is unknown)
     *   or the object cannot be cached.
     */
    fun entryIn(cache: StorageFileCache, bucketId: String, objectPath: String): StorageCacheEntry? =
        when (val seen = observation.load()) {
            Observation.None -> null
            Observation.Original -> cache.entryFor(bucketId, objectPath)
            is Observation.Transformed -> cache.variantEntryFor(bucketId, objectPath, seen.query)
        }

    private fun record(url: Url) {
        observation.store(
            if ("/render/image/" in url.encodedPath) Observation.Transformed(canonicalTransform(url)) else Observation.Original,
        )
    }

    private fun canonicalTransform(url: Url): String =
        url.parameters.names()
            .filter { it !in PARAMETERS_THAT_KEEP_THE_BYTES }
            .sorted()
            .joinToString("&") { name -> "$name=${url.parameters.getAll(name).orEmpty().joinToString(",")}" }
            .ifEmpty { "render" }
}
