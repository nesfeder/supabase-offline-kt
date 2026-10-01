// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.Offline.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.OfflineExperimental
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.ResponseTrust
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the engine is given for each setting `install(Offline)` writes in [Offline.Config]. */
@OptIn(OfflineExperimental::class)
class OfflineConfigMappingTest {

    @Test
    fun a_new_configuration_has_the_documented_defaults() {
        // When
        val config = Offline.createConfig {}

        // Then
        assertEquals(100L * 1024L * 1024L, config.cacheSizeBytes)
        assertEquals(256L * 1024L * 1024L, config.fileCacheSizeBytes)
        assertNull(config.dataDirectory)
        assertNull(config.networkAvailability)
        assertTrue(config.autoSync)
        assertTrue(config.primaryKeys.isEmpty())
        assertEquals(ResponseTrust.SettledSession, config.responseTrust)
        assertNull(config.logger)
        assertEquals(OfflineLogLevel.INFO, config.minLogLevel)
    }

    @Test
    fun the_response_trust_setting_chooses_the_engine_rule() {
        // Given
        val settled = Offline.createConfig {}
        val authenticatedOnly = Offline.createConfig {
            responseTrust = ResponseTrust.AuthenticatedSessionOnly
        }

        // Then
        assertEquals(ResponseTrustPolicy.SETTLED_SESSION, settled.responseTrustPolicy())
        assertEquals(
            ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY,
            authenticatedOnly.responseTrustPolicy(),
        )
    }

    @Test
    fun a_table_without_a_configured_key_is_keyed_by_id() {
        // Given
        val config = Offline.createConfig { primaryKey("memberships", "team_id", "user_id") }

        // When
        val keys = config.primaryKeysByTable()

        // Then
        assertEquals(listOf("team_id", "user_id"), keys.of("memberships"))
        assertEquals(listOf("id"), keys.of("todos"))
        assertEquals(listOf("id"), Offline.createConfig {}.primaryKeysByTable().of("todos"))
    }

    @Test
    fun the_cache_sizes_become_the_engine_bounds() {
        // Then — unlimited turns eviction off; a bound below the minimum counts as the minimum
        assertNull(
            Offline.createConfig { cacheSizeBytes = CACHE_SIZE_UNLIMITED }
                .toQueryCacheLimits()
                .maxPayloadBytes
        )
        assertEquals(
            4_096L,
            Offline.createConfig { cacheSizeBytes = 1L }.toQueryCacheLimits().maxPayloadBytes,
        )
        assertEquals(
            8_192L,
            Offline.createConfig { cacheSizeBytes = 8_192L }.toQueryCacheLimits().maxPayloadBytes,
        )
        assertNull(
            Offline.createConfig { fileCacheSizeBytes = CACHE_SIZE_UNLIMITED }
                .fileCacheBoundOrNull()
        )
        assertEquals(0L, Offline.createConfig { fileCacheSizeBytes = -5L }.fileCacheBoundOrNull())
        assertEquals(
            1_024L,
            Offline.createConfig { fileCacheSizeBytes = 1_024L }.fileCacheBoundOrNull(),
        )
    }
}
