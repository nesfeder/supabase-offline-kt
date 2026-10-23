// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.github.jan.supabase.storage.storage
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
 * Learns which variant a download fetched, the original or an image transform, from the URL
 * supabase-kt builds: [wrap] appends a request override that records it. When the request is never
 * built, nothing is recorded: the original's copy is served, and nothing is stored. A request the
 * caller's own overrides turned into something other than a `GET` of the whole object (a `HEAD`, or
 * a `Range` or `If-Range` header) fetches part of it, or none: neither stored nor served.
 *
 * @param renderImagePath the encoded path prefix of a transformed download, ending in `/`.
 * @param beforeSend runs on the probe once the request is recorded, before it is sent; what it
 *   throws stops the request, with the variant known.
 */
internal class DownloadRequestProbe(
    private val renderImagePath: String = DEFAULT_RENDER_IMAGE_PATH,
    private val beforeSend: DownloadRequestProbe.() -> Unit = {},
) {

    /** What the probe saw of the request. */
    private sealed interface Observation {
        /** No request was built. */
        data object None : Observation

        /** The request fetched the untransformed object. */
        data object Original : Observation

        /** The request fetched the image transform whose canonical query is [query]. */
        class Transformed(val query: String) : Observation

        /** The request fetched part of the object, or none of it: not a `GET`, or a range. */
        data object Partial : Observation
    }

    /** What the probe saw, recorded on whichever thread supabase-kt builds the request. */
    private val observation = AtomicReference<Observation>(Observation.None)

    /**
     * The caller's [options] followed by the override that records the request.
     *
     * @param options the caller's download options.
     * @return options to pass to supabase-kt in their place.
     */
    fun wrap(options: DownloadOptionBuilder.() -> Unit): DownloadOptionBuilder.() -> Unit = {
        options()
        // Last, so it sees the method and headers the caller's own overrides set
        httpOverride {
            record(method, headers, url.build())
            this@DownloadRequestProbe.beforeSend()
        }
    }

    /**
     * The cache entry to store what the download fetched in: the variant its request asked for.
     *
     * @return the entry, or `null` when the request was never seen — bytes whose variant is unknown
     *   are never stored — or fetched part of the object, or the object cannot be cached.
     */
    fun entryToStore(
        cache: StorageFileCache,
        bucketId: String,
        objectPath: String,
    ): FileCacheEntry? =
        when (val seen = observation.load()) {
            Observation.None,
            Observation.Partial -> null
            Observation.Original -> cache.entryFor(bucketId, objectPath)
            is Observation.Transformed -> cache.variantEntryFor(bucketId, objectPath, seen.query)
        }

    /**
     * The cache entry to serve when the download failed: the variant its request asked for, or the
     * original when the request was never built.
     *
     * @return the entry, or `null` when the request asked for part of the object, or the object
     *   cannot be cached.
     */
    fun entryToServe(
        cache: StorageFileCache,
        bucketId: String,
        objectPath: String,
    ): FileCacheEntry? =
        when (val seen = observation.load()) {
            Observation.None,
            Observation.Original -> cache.entryFor(bucketId, objectPath)
            is Observation.Transformed -> cache.variantEntryFor(bucketId, objectPath, seen.query)
            Observation.Partial -> null
        }

    private fun record(method: HttpMethod, headers: HeadersBuilder, url: Url) {
        observation.store(
            when {
                method != HttpMethod.Get ||
                    headers.contains(HttpHeaders.Range) ||
                    headers.contains(HttpHeaders.IfRange) -> Observation.Partial
                url.encodedPath.startsWith(renderImagePath) ->
                    Observation.Transformed(canonicalTransformOf(url))
                else -> Observation.Original
            }
        )
    }
}

/**
 * The canonical query of the image transform a render URL asks for: the parameters that change the
 * bytes, sorted by name, or `render` when there are none. A transformed variant's cache entry is
 * named by it.
 *
 * @param url a render URL, as a download's request or supabase-kt's `authenticatedRenderUrl` has
 *   it.
 * @return the canonical query.
 */
internal fun canonicalTransformOf(url: Url): String =
    url.parameters
        .names()
        .filter { it !in PARAMETERS_THAT_KEEP_THE_BYTES }
        .sorted()
        .joinToString("&") { name ->
            "$name=${url.parameters.getAll(name).orEmpty().joinToString(",")}"
        }
        .ifEmpty { "render" }
