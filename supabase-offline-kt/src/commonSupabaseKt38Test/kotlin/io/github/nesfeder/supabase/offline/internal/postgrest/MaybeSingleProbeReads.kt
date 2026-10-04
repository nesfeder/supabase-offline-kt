// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder

/** The `maybeSingle()` reads of `ReadShapeProbeContractTest`, a supabase-kt 3.8 read shape. */
internal val maybeSingleProbeReads: List<Pair<ReadShape, SelectRequestBuilder.() -> Unit>> =
    listOf(ReadShape.Uncacheable("a single object (single(), maybeSingle())") to { maybeSingle() })
