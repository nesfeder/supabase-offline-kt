// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.Offline

/**
 * Opens [config]'s client for [supabase] over the platform's database, in
 * [Offline.Config.dataDirectory] or the platform's default location.
 *
 * @throws IllegalStateException when another open client already uses the database or the
 *   directory.
 */
internal expect fun openPlatformClient(
    supabase: SupabaseClient,
    config: Offline.Config,
): OfflineClient
