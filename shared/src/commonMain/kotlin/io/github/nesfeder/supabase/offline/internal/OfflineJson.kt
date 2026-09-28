package io.github.nesfeder.supabase.offline.internal

import kotlinx.serialization.json.Json

/**
 * The single [Json] instance shared across the offline SDK's commonMain code.
 *
 * Cache-payload parsing, mutation-overlay round-trips, and filter-descriptor encode/decode all
 * route through this one instance, so the serializer cache is shared and the configuration can
 * never drift between call sites. Each `from()` query builder, the offline store, and the filter
 * engine previously constructed their own private instance configured with `ignoreUnknownKeys`,
 * allocating a fresh empty serializer cache on the hottest read path.
 *
 * `ignoreUnknownKeys = true` reproduces the historical per-site configuration exactly, so cached
 * payloads decode identically. It is deliberately a plain [Json] rather than the shared
 * network-decode instance, whose value coercion would change offline overlay decode semantics.
 */
internal val OfflineJson: Json = Json { ignoreUnknownKeys = true }
