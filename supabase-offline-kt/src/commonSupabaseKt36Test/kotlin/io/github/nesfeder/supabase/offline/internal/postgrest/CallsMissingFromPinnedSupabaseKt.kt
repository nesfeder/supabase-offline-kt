// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

/**
 * The cache-key fixture's calls supabase-kt 3.6 cannot build: its `isdistinct` filter came in 3.8.
 */
internal val callsMissingFromPinnedSupabaseKt: Set<String> = setOf("isdistinct")
