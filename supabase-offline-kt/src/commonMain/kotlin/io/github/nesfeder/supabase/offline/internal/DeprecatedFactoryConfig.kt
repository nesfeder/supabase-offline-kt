// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:Suppress("DEPRECATION")

package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineExperimental
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig

/**
 * The plugin configuration the deprecated factories' [SupabaseOfflineConfig] describes: every
 * setting carried over, the data directory as its path's string.
 */
@OptIn(OfflineExperimental::class)
internal fun SupabaseOfflineConfig.toOfflineConfig(): Offline.Config =
    Offline.Config().also { config ->
        config.cacheSizeBytes = cacheSizeBytes
        config.fileCacheSizeBytes = fileCacheSizeBytes
        config.dataDirectory = offlineStoragePath?.toString()
        config.logger = logger
        config.minLogLevel = minLogLevel
        config.networkAvailability = networkAvailability
    }
