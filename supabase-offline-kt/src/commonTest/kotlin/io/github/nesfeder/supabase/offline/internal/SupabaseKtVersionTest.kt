// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the plugin tells which supabase-kt an app runs: the version supabase-kt itself reports, read
 * at install, against the one this build was tested with, generated from the version catalog.
 */
class SupabaseKtVersionTest {

    @Test
    fun the_running_supabase_kt_reports_the_version_this_build_was_tested_with() {
        // Given
        val supabase = newTestSupabaseClient()
        var reported: String? = null
        var delivered = false

        // When
        supabase.readSupabaseKtVersion { version ->
            reported = version
            delivered = true
        }

        // Then — read at once, and this build runs the supabase-kt it names
        assertTrue(delivered, "the version was not read before the call returned")
        assertEquals(TESTED_SUPABASE_KT_VERSION, reported)
    }

    @Test
    fun another_minor_is_logged_as_one_warning_naming_both_versions() {
        // Given — a build that says it was tested against an earlier minor
        val logs = RecordingLogSink()
        val supabase = newTestSupabaseClient()

        // When
        supabase.warnUnlessTestedSupabaseKt(logs.asEngineLogger(), tested = "3.7.2")

        // Then
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue(TESTED_SUPABASE_KT_VERSION in warning, warning)
        assertTrue("3.7 (3.7.2)" in warning, warning)
    }

    @Test
    fun the_tested_minor_is_no_warning() {
        // Given
        val logs = RecordingLogSink()

        // When
        newTestSupabaseClient().warnUnlessTestedSupabaseKt(logs.asEngineLogger())

        // Then
        assertEquals(emptyList(), logs.entries)
    }

    @Test
    fun a_patch_difference_or_an_unknown_version_is_no_warning() {
        // Given / When / Then
        assertNull(untestedSupabaseKtWarning(running = "3.8.4", tested = "3.8.0"))
        assertNull(untestedSupabaseKtWarning(running = null, tested = "3.8.0"))
        assertNotNull(untestedSupabaseKtWarning(running = "3.9.0-beta1", tested = "3.8.0"))
        assertNotNull(untestedSupabaseKtWarning(running = "4.8.0", tested = "3.8.0"))
    }

    @Test
    fun the_version_is_read_from_the_client_info_header() {
        // Given / When / Then
        assertEquals(
            "3.8.0",
            supabaseKtVersionIn("supabase-kt/3.8.0; platform=Android; runtime=kotlin"),
        )
        assertEquals("3.9.1", supabaseKtVersionIn("supabase-kt/3.9.1"))
        assertNull(supabaseKtVersionIn("postgrest-swift/1.0.0"))
    }
}
