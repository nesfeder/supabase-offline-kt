// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

/**
 * Canonical empty JSON array — what PostgREST returns for a SELECT that matched nothing, and the
 * answer the SDK synthesizes when it refuses a response outright.
 *
 * Single definition for the whole module: the resolver serves it on a teardown-refused response,
 * the store overlays mutations onto it as an in-memory substrate, and [isEmptyJsonArrayPayload]
 * classifies against it. Three private copies of the same literal is three chances for one of them
 * to drift.
 */
internal const val EMPTY_JSON_ARRAY: String = "[]"

/**
 * Whether [payload] is a PostgREST SELECT body that carries no rows.
 *
 * Both shapes count: the canonical [EMPTY_JSON_ARRAY] a SELECT returns when nothing matches
 * (including when RLS filtered every row because the request went out without a usable JWT), and a
 * blank/whitespace-only body, which some proxies substitute for it. Whitespace around the array is
 * tolerated so a re-encoded or pretty-printed body is classified the same way.
 *
 * This is the single definition shared by the two seams that both have to agree on "is this
 * response empty": the cache-write guard in
 * [io.github.nesfeder.supabase.offline.internal.db.OfflineStore.addPayload], which never persists
 * an empty that wasn't attributed to an authenticated session, and the restore-window retries in
 * [io.github.nesfeder.supabase.offline.internal.postgrest.QueryReader], which re-issue a SELECT
 * whose emptiness may be a cold-start auth artifact. They were byte-identical private copies; a
 * divergence between them would let one seam retry a body the other happily cached.
 *
 * A `head` or count read, whose blank body is not "no rows", never reaches it: reads whose response
 * is not a row list bypass the cache (see `ReadShape`).
 *
 * @param payload Raw response body as returned by PostgREST (or read back from the cache).
 * @return `true` when the body carries no rows.
 */
internal fun isEmptyJsonArrayPayload(payload: String): Boolean {
    val trimmed = payload.trim()
    return trimmed.isEmpty() || trimmed == EMPTY_JSON_ARRAY
}
