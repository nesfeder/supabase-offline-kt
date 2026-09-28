package io.github.nesfeder.supabase.offline

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Smoke tests for the [SupabaseResult] sealed type itself. Integration coverage of
 * [decodeListSafely] / [decodeSingleSafely] lives in feature-level tests that exercise a real
 * [io.github.jan.supabase.postgrest.result.PostgrestResult] (constructing one in commonTest
 * requires a Postgrest stub the project doesn't currently have — tracked as follow-up).
 */
class SupabaseResultTest {

    @Test
    fun ok_holds_value() {
        // Given / When
        val result: SupabaseResult<List<Int>> = SupabaseResult.Ok(listOf(1, 2, 3))

        // Then
        assertTrue(result is SupabaseResult.Ok)
        assertEquals(listOf(1, 2, 3), result.value)
    }

    @Test
    fun ok_with_empty_list_is_distinct_from_Empty() {
        // Given
        val emptyOk: SupabaseResult<List<Int>> = SupabaseResult.Ok(emptyList())
        val empty: SupabaseResult<List<Int>> = SupabaseResult.Empty

        // Then — these are intentionally not equal: "the query returned an empty array" is a
        // valid success, "the query returned no rows" is its own variant.
        assertTrue(emptyOk != empty)
    }

    @Test
    fun decode_error_carries_sample() {
        // Given
        val cause = SerializationException("expected array, got string")
        val sample = "\"[{...}]\"".take(DECODE_SAMPLE_LIMIT)

        // When
        val result = SupabaseResult.DecodeError(cause, sample)

        // Then
        assertEquals(cause, result.cause)
        assertEquals(sample, result.rawSample)
    }

    @Test
    fun network_error_carries_cause() {
        // Given
        val cause = RuntimeException("connection reset")

        // When
        val result = SupabaseResult.NetworkError(cause)

        // Then
        assertEquals(cause, result.cause)
    }

    @Test
    fun decode_error_diagnostic_routes_through_installed_logger() {
        // Given a logger installed on the SDK's pluggable logging seam
        val captured = mutableListOf<Pair<OfflineLogLevel, String>>()
        SupabaseResultLogging.logger = OfflineLogger { level, message -> captured += level to message }
        try {
            val decoded = SupabaseResult.DecodeError(
                cause = SerializationException("expected array, got string"),
                rawSample = "\"not-an-array\"",
            )

            // When the throw-path diagnostic is built
            val error = supabaseDecodeError(feature = "Score", op = "listScores", decoded = decoded)

            // Then it logs exactly once at DEBUG with the feature + op tags and raw sample intact
            assertEquals(1, captured.size)
            val (level, message) = captured.single()
            assertEquals(OfflineLogLevel.DEBUG, level)
            assertContains(message, "Score")
            assertContains(message, "listScores")
            assertContains(message, "not-an-array")
            // ...while the thrown exception itself carries no raw sample (crash-report hygiene)
            assertContains(error.message ?: "", "Score.listScores decode failed")
            assertFalse((error.message ?: "").contains("not-an-array"))
        } finally {
            SupabaseResultLogging.logger = null
        }
    }

    @Test
    fun decode_error_diagnostic_is_silent_without_a_logger() {
        // Given no logger installed — the standalone-consumer default
        SupabaseResultLogging.logger = null
        val decoded = SupabaseResult.DecodeError(
            cause = SerializationException("boom"),
            rawSample = "raw",
        )

        // When / Then building the diagnostic neither throws nor needs a logger, and still
        // produces the tagged exception
        val error = supabaseDecodeError(feature = "Insights", op = "observe", decoded = decoded)
        assertContains(error.message ?: "", "Insights.observe decode failed")
    }
}
