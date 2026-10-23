// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

/**
 * How a [BridgeClient] is set up: the project, where its data lives, and the app's callbacks.
 *
 * Every setting but the three in the constructor has a default; the client reads them once, when
 * [BridgeClient.open] builds it.
 *
 * @property supabaseUrl the project's URL, such as `https://xyz.supabase.co`.
 * @property supabaseKey the project's publishable (anon) key.
 * @property dataDirectory the directory for the database and the Storage file cache, created when
 *   missing. Always the caller's choice: the bridge never falls back to a platform default.
 */
public class BridgeClientOptions(
    public val supabaseUrl: String,
    public val supabaseKey: String,
    public val dataDirectory: String,
) {
    /** The PostgREST schema a description without one reads and writes; `public` by default. */
    public var schema: String = "public"

    /** Supplies the user's access token per request, or `null` to send the key alone. */
    public var accessTokenProvider: BridgeAccessTokenProvider? = null

    /**
     * Whether the client tells its users apart by the `sub` claim of the token
     * [accessTokenProvider] returns, read and never verified; `false` by default, and then one
     * queue and one cache serve every user. With `true`, each user's queued writes are sent only
     * with that user's token, the caches belong to one user at a time and are dropped when the
     * token names another, and the Realtime channel is joined again for the new user. A `null`
     * token is nobody signed in, and a token that names no user is no identity.
     */
    public var identityFromAccessToken: Boolean = false

    /**
     * The most the cached query results may hold, in bytes; [CACHE_SIZE_UNLIMITED] for no bound.
     */
    public var cacheSizeBytes: Long = DEFAULT_CACHE_SIZE_BYTES

    /** The most the Storage file cache may hold, in bytes; [CACHE_SIZE_UNLIMITED] for no bound. */
    public var fileCacheSizeBytes: Long = DEFAULT_FILE_CACHE_SIZE_BYTES

    /** Whether queued writes are sent by themselves when the network comes back; `true`. */
    public var autoSync: Boolean = true

    /**
     * Whether the app reports connectivity through [BridgeClient.setNetworkAvailable] in place of
     * the library's own monitor; `false` by default. Until the first report it reads as unknown.
     */
    public var reportsNetworkAvailability: Boolean = false

    /** Each table's primary-key columns, by table name; a table not named here has the key `id`. */
    public var primaryKeys: Map<String, List<String>> = emptyMap()

    /**
     * The tables the client keeps current through Realtime: it joins one channel for them while the
     * network is not known down and the subscription is on, applies each change, reads them again
     * on every join, and refreshes the channel's token before it expires through
     * [accessTokenProvider]. None by default, and then no channel is opened.
     */
    public var realtimeTables: List<BridgeRealtimeTable> = emptyList()

    /**
     * Each table's version column, by table name, keyed as [primaryKeys] is: a sync pass replays a
     * queued UPDATE or DELETE of one cached row only while the server's row still has the version
     * the device saw. None by default.
     */
    public var versionColumns: Map<String, String> = emptyMap()

    /**
     * Settles the conflicts such a replay finds, or `null` for the library's defaults: the local
     * change wins a moved row, and an UPDATE of a row that is gone is dropped.
     */
    public var conflictResolver: BridgeConflictResolver? = null

    /** Receives every log message of the library and of supabase-kt, or `null` to drop them. */
    public var logSink: BridgeLogSink? = null

    /** The least severe level that reaches [logSink]; [BridgeLogLevel.WARNING] by default. */
    public var logLevel: BridgeLogLevel = BridgeLogLevel.WARNING

    /** The defaults and the size that turns a cache's bound off. */
    public companion object {
        /** The size that turns eviction off, for either cache. */
        public const val CACHE_SIZE_UNLIMITED: Long = -1L

        /** The default of [cacheSizeBytes]: 100 MiB, as the library's. */
        public const val DEFAULT_CACHE_SIZE_BYTES: Long = 100L * 1024L * 1024L

        /** The default of [fileCacheSizeBytes]: 256 MiB, as the library's. */
        public const val DEFAULT_FILE_CACHE_SIZE_BYTES: Long = 256L * 1024L * 1024L
    }
}

/** Supplies the access token each request carries: the app's own session, behind a callback. */
public interface BridgeAccessTokenProvider {

    /**
     * Fetches the current access token and hands it to [callback], once, on any thread: a token,
     * `null` for the key alone, or a failure. The request waits until then.
     */
    public fun fetchAccessToken(callback: BridgeAccessTokenCallback)
}

/** The one-time answer to a [BridgeAccessTokenProvider.fetchAccessToken] call. */
public class BridgeAccessTokenCallback
internal constructor(private val answer: (token: String?, failure: String?) -> Unit) {

    /** Answers with [token], or with `null` to send the project key alone. */
    public fun succeed(token: String?) {
        answer(token, null)
    }

    /**
     * Answers with a failure: the request fails with [BridgeErrorKind.ACCESS_TOKEN] and [message].
     */
    public fun fail(message: String) {
        answer(null, message)
    }
}

/** Receives log messages; called on any thread, and never with a row, filter value or token. */
public interface BridgeLogSink {

    /**
     * One message, tagged with the component that wrote it. At [BridgeLogLevel.INFO] and above the
     * message names a failure by its type only.
     */
    public fun log(level: BridgeLogLevel, tag: String, message: String)
}

/** How severe a log message is, least to most; [NONE] as a setting turns logging off. */
public enum class BridgeLogLevel {
    /** Diagnostics; may carry rows, filter values and object paths. */
    DEBUG,

    /** A state change worth a breadcrumb. */
    INFO,

    /** Something recovered from, but unexpected. */
    WARNING,

    /** A failure. */
    ERROR,

    /** As a setting, nothing is logged. */
    NONE,
}
