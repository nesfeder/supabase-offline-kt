// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.result.PostgrestResult

/**
 * Honest result of an offline-aware mutation issued through the verbs on
 * [OfflinePostgrestQueryBuilder].
 *
 * A mutation either reaches the server or is queued locally when the network is unavailable.
 * Collapsing both into a bare [PostgrestResult] would report a queued write as a synthetic empty
 * result, indistinguishable from "the server executed the mutation and returned no rows" — a caller
 * that reads the written row back would decode the empty placeholder, which either throws or maps
 * to a null domain object. [MutationOutcome] makes the distinction explicit so those callers can
 * construct the domain object from the DTO they just sent instead of decoding a placeholder.
 */
public sealed interface MutationOutcome {

    /**
     * The mutation executed on the server.
     *
     * @property result the live PostgREST response, ready to decode exactly as a legacy verb's
     *   return value.
     */
    public data class Applied(val result: PostgrestResult) : MutationOutcome

    /**
     * The mutation could not reach the server (network unavailable) and was queued locally; it will
     * replay on the next successful sync. There is no server response to decode.
     */
    public data object Queued : MutationOutcome

    /**
     * The server response when the mutation [Applied], or `null` when it was [Queued].
     *
     * A drop-in bridge for call sites that only need the "result or null when queued" shape, e.g.
     * `upsert(…).resultOrNull()?.decodeAs<Foo>()`.
     *
     * @return the [Applied.result], or `null` for [Queued].
     */
    public fun resultOrNull(): PostgrestResult? = (this as? Applied)?.result
}
