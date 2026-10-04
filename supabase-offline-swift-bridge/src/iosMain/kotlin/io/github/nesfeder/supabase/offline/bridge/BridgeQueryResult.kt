// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

/**
 * The answer to a one-shot read or a function call: the response body and where it came from.
 *
 * @property data the response body as the server sent it, or the cached rows with the user's queued
 *   writes applied: JSON text for a JSON read, the text itself for CSV.
 * @property count the total a `count` asked for, from the response's `Content-Range`; `null` when
 *   none was asked for or sent.
 * @property isFromCache whether the rows are the device's copy rather than the server's answer.
 * @property fetchedAtEpochMillis when the rows were fetched from the server, by the device's clock,
 *   in milliseconds since 1970; `null` for an answer the library did not stamp.
 * @property hasPendingMutations whether a queued write changed one of the rows.
 */
public class BridgeQueryResult
internal constructor(
    public val data: String,
    public val count: Long?,
    public val isFromCache: Boolean,
    public val fetchedAtEpochMillis: Long?,
    public val hasPendingMutations: Boolean,
)

/**
 * One emission of a live read ([BridgeClient.observe]): the rows as they stand, and how the
 * refresh behind them stands.
 *
 * @property data the rows as JSON text with the user's queued writes applied, or `null` while
 *   nothing is known for the query; `[]` when the server returned no rows.
 * @property isFromCache whether the rows are the device's copy rather than this read's answer.
 * @property fetchedAtEpochMillis when the rows were fetched, in milliseconds since 1970, or `null`.
 * @property hasPendingMutations whether a queued write changed one of the rows shown.
 * @property refresh how the refresh behind the read stands.
 */
public class BridgeQuerySnapshot
internal constructor(
    public val data: String?,
    public val isFromCache: Boolean,
    public val fetchedAtEpochMillis: Long?,
    public val hasPendingMutations: Boolean,
    public val refresh: BridgeRefreshState,
)

/**
 * How the refresh behind a live read stands.
 *
 * @property kind the state.
 * @property cause for [BridgeRefreshKind.STALLED], the failure that stalled the refresh.
 */
public class BridgeRefreshState
internal constructor(public val kind: BridgeRefreshKind, public val cause: BridgeError?)

/** The states of a live read's refresh, as the library names them. */
public enum class BridgeRefreshKind {
    /** No refresh is running or waiting. */
    IDLE,

    /** A refresh is running, or about to be tried again. */
    REFRESHING,

    /** The refresh waits for the network, which is known to be down; nothing is failing. */
    PAUSED,

    /** The refresh keeps failing; it is still tried, at most a minute apart. */
    STALLED,
}
