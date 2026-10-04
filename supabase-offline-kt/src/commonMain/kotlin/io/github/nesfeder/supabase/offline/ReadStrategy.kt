// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.time.Duration

/**
 * Where a one-shot `select` takes its rows from: the server, the device's cache, or one and then
 * the other.
 *
 * Only a list of rows is cached. A read of another shape (`single()`, `maybeSingle()` from
 * supabase-kt 3.8, `head`, a `count`, `csv()`, `geojson()`, `stripNulls()`, `explain()`) goes to
 * the server under every strategy but [CacheOnly].
 */
public sealed interface ReadStrategy {

    /** Asks the server, and fails as supabase-kt does when it cannot be reached. */
    public data object NetworkOnly : ReadStrategy

    /**
     * Asks the server, and serves the cached rows when it cannot be reached.
     *
     * With the query cached, a transport failure falls back at once (supabase-kt's retries of it
     * are skipped; a `503` or `520` is still retried on its schedule). With nothing cached, the
     * read retries and fails as [NetworkOnly] does.
     *
     * @property maxAge the oldest cached rows the fallback may serve, or `null` for any age. Older
     *   rows count as none, and the server's failure is thrown instead.
     */
    public class NetworkFirst(public val maxAge: Duration? = null) : ReadStrategy {

        override fun equals(other: Any?): Boolean = other is NetworkFirst && other.maxAge == maxAge

        override fun hashCode(): Int = maxAge.hashCode()

        override fun toString(): String = "NetworkFirst(maxAge=$maxAge)"
    }

    /**
     * Serves the cached rows when there are any, and asks the server otherwise.
     *
     * Cached rows that a write marked for fetching again are fetched again; when the server cannot
     * be reached, they are served after all.
     *
     * @property maxAge the oldest cached rows to serve, or `null` for any age. Older rows count as
     *   none.
     */
    public class CacheFirst(public val maxAge: Duration? = null) : ReadStrategy {

        override fun equals(other: Any?): Boolean = other is CacheFirst && other.maxAge == maxAge

        override fun hashCode(): Int = maxAge.hashCode()

        override fun toString(): String = "CacheFirst(maxAge=$maxAge)"
    }

    /**
     * Serves the cached rows and never sends a request. With nothing cached, or a read of a shape
     * the cache does not hold, it throws [CacheMissException].
     *
     * @property maxAge the oldest cached rows to serve, or `null` for any age. Older rows count as
     *   none.
     */
    public class CacheOnly(public val maxAge: Duration? = null) : ReadStrategy {

        override fun equals(other: Any?): Boolean = other is CacheOnly && other.maxAge == maxAge

        override fun hashCode(): Int = maxAge.hashCode()

        override fun toString(): String = "CacheOnly(maxAge=$maxAge)"
    }
}
