// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/** The [Offline] plugin, under the name the client had before it became a plugin. */
@Deprecated(
    "Renamed Offline: install it with install(Offline) and reach it as supabase.offline",
    ReplaceWith("Offline", "io.github.nesfeder.supabase.offline.Offline"),
)
public typealias SupabaseOfflineClient = Offline
