// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure as internalIsNetworkOrConnectivityFailure

/**
 * Public error-classification API for consumers of the offline SDK.
 *
 * These predicates let a caller reason about a thrown Supabase/Ktor failure — retry a transient
 * connectivity blip vs. surface a hard server error, downgrade an expected-offline failure to a
 * warning instead of a crash-reported error — without reaching into the SDK's `internal` packages.
 * They are thin, stable delegates over the internal implementations so the internal classification
 * can evolve without breaking consumers.
 */

/**
 * Returns `true` when this throwable is very likely a network / connectivity failure (request or
 * connect/socket timeout, transport IO error, or a wrapped cause of one) rather than an application
 * or server-side error.
 *
 * Cancellation and any throwable carrying an HTTP status (a real 4xx/5xx server response) are
 * deliberately excluded — those are not transport failures.
 *
 * @return `true` for connectivity/transport failures, `false` otherwise.
 */
public fun Throwable.isNetworkOrConnectivityFailure(): Boolean =
    internalIsNetworkOrConnectivityFailure()
