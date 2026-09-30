// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import kotlinx.serialization.json.Json

/**
 * The one [Json] for cached payloads, overlays and filter descriptors, so the serializer cache is
 * shared and the settings cannot drift. It ignores unknown keys and coerces no values: coercion
 * would change what the overlay decodes.
 */
internal val OfflineJson: Json = Json { ignoreUnknownKeys = true }
