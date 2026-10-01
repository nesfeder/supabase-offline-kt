// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.logging

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Level gating and lazy message construction of the SDK's internal [EngineLogger]. */
class EngineLoggerTest {

    @Test
    fun nullLogger_doesNotCrash() {
        // Given
        val logger = EngineLogger(null, OfflineLogLevel.DEBUG)

        // When
        logger.debug { "test" }
        logger.info { "test" }
        logger.warning { "test" }
        logger.error { "test" }

        // Then -- no exception thrown
    }

    @Test
    fun allLevelsForwarded_whenMinLevelIsDebug() {
        // Given
        val recorder = RecordingOfflineLogger()
        val logger = recorder.asEngineLogger(OfflineLogLevel.DEBUG)

        // When
        logger.debug { "d" }
        logger.info { "i" }
        logger.warning { "w" }
        logger.error { "e" }

        // Then
        assertEquals(4, recorder.entries.size)
        assertEquals(RecordingOfflineLogger.Entry(OfflineLogLevel.DEBUG, "d"), recorder.entries[0])
        assertEquals(RecordingOfflineLogger.Entry(OfflineLogLevel.INFO, "i"), recorder.entries[1])
        assertEquals(
            RecordingOfflineLogger.Entry(OfflineLogLevel.WARNING, "w"),
            recorder.entries[2],
        )
        assertEquals(RecordingOfflineLogger.Entry(OfflineLogLevel.ERROR, "e"), recorder.entries[3])
    }

    @Test
    fun levelFiltering_minWarning_suppressesDebugAndInfo() {
        // Given
        val recorder = RecordingOfflineLogger()
        val logger = recorder.asEngineLogger(OfflineLogLevel.WARNING)

        // When
        logger.debug { "d" }
        logger.info { "i" }
        logger.warning { "w" }
        logger.error { "e" }

        // Then
        assertEquals(2, recorder.entries.size)
        assertEquals(OfflineLogLevel.WARNING, recorder.entries[0].level)
        assertEquals(OfflineLogLevel.ERROR, recorder.entries[1].level)
    }

    @Test
    fun levelFiltering_minError_suppressesAllButError() {
        // Given
        val recorder = RecordingOfflineLogger()
        val logger = recorder.asEngineLogger(OfflineLogLevel.ERROR)

        // When
        logger.debug { "d" }
        logger.info { "i" }
        logger.warning { "w" }
        logger.error { "e" }

        // Then
        assertEquals(1, recorder.entries.size)
        assertEquals(OfflineLogLevel.ERROR, recorder.entries[0].level)
    }

    @Test
    fun levelFiltering_minInfo_suppressesDebug() {
        // Given
        val recorder = RecordingOfflineLogger()
        val logger = recorder.asEngineLogger(OfflineLogLevel.INFO)

        // When
        logger.debug { "d" }
        logger.info { "i" }
        logger.warning { "w" }
        logger.error { "e" }

        // Then
        assertEquals(3, recorder.entries.size)
        assertEquals(OfflineLogLevel.INFO, recorder.entries[0].level)
    }

    @Test
    fun lazyEvaluation_messageLambdaNotCalled_whenFiltered() {
        // Given
        val recorder = RecordingOfflineLogger()
        val logger = recorder.asEngineLogger(OfflineLogLevel.ERROR)
        var debugEvaluated = false
        var infoEvaluated = false
        var warningEvaluated = false

        // When
        logger.debug {
            debugEvaluated = true
            "d"
        }
        logger.info {
            infoEvaluated = true
            "i"
        }
        logger.warning {
            warningEvaluated = true
            "w"
        }

        // Then
        assertEquals(false, debugEvaluated)
        assertEquals(false, infoEvaluated)
        assertEquals(false, warningEvaluated)
        assertTrue(recorder.entries.isEmpty())
    }

    @Test
    fun lazyEvaluation_messageLambdaCalled_whenNotFiltered() {
        // Given
        val recorder = RecordingOfflineLogger()
        val logger = recorder.asEngineLogger(OfflineLogLevel.DEBUG)
        var called = false

        // When
        logger.debug {
            called = true
            "msg"
        }

        // Then
        assertEquals(true, called)
        assertEquals("msg", recorder.entries.single().message)
    }

    @Test
    fun lazyEvaluation_nullLogger_messageLambdaNotCalled() {
        // Given
        val logger = EngineLogger(null, OfflineLogLevel.DEBUG)
        var called = false

        // When
        logger.debug {
            called = true
            "msg"
        }

        // Then
        assertEquals(false, called)
    }

    @Test
    fun convenienceMethods_mapToCorrectLevel() {
        // Given
        val recorder = RecordingOfflineLogger()
        val logger = recorder.asEngineLogger()

        // When
        logger.debug { "a" }
        logger.info { "b" }
        logger.warning { "c" }
        logger.error { "d" }

        // Then
        assertEquals(OfflineLogLevel.DEBUG, recorder.entries[0].level)
        assertEquals(OfflineLogLevel.INFO, recorder.entries[1].level)
        assertEquals(OfflineLogLevel.WARNING, recorder.entries[2].level)
        assertEquals(OfflineLogLevel.ERROR, recorder.entries[3].level)
    }

    @Test
    fun logMethod_usesExplicitLevel() {
        // Given
        val recorder = RecordingOfflineLogger()
        val logger = recorder.asEngineLogger()

        // When
        logger.log(OfflineLogLevel.WARNING) { "explicit" }

        // Then
        assertEquals(1, recorder.entries.size)
        assertEquals(
            RecordingOfflineLogger.Entry(OfflineLogLevel.WARNING, "explicit"),
            recorder.entries[0],
        )
    }
}
