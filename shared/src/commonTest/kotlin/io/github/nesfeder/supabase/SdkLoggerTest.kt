package io.github.nesfeder.supabase

import io.github.nesfeder.supabase.internal.CapturingLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkLoggerTest {

    @Test
    fun nullLogger_doesNotCrash() {
        // Given
        val logger = SdkLogger(null, OfflineLogLevel.DEBUG)

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
        val capturing = CapturingLogger()
        val logger = capturing.asSdkLogger(OfflineLogLevel.DEBUG)

        // When
        logger.debug { "d" }
        logger.info { "i" }
        logger.warning { "w" }
        logger.error { "e" }

        // Then
        assertEquals(4, capturing.entries.size)
        assertEquals(CapturingLogger.Entry(OfflineLogLevel.DEBUG, "d"), capturing.entries[0])
        assertEquals(CapturingLogger.Entry(OfflineLogLevel.INFO, "i"), capturing.entries[1])
        assertEquals(CapturingLogger.Entry(OfflineLogLevel.WARNING, "w"), capturing.entries[2])
        assertEquals(CapturingLogger.Entry(OfflineLogLevel.ERROR, "e"), capturing.entries[3])
    }

    @Test
    fun levelFiltering_minWarning_suppressesDebugAndInfo() {
        // Given
        val capturing = CapturingLogger()
        val logger = capturing.asSdkLogger(OfflineLogLevel.WARNING)

        // When
        logger.debug { "d" }
        logger.info { "i" }
        logger.warning { "w" }
        logger.error { "e" }

        // Then
        assertEquals(2, capturing.entries.size)
        assertEquals(OfflineLogLevel.WARNING, capturing.entries[0].level)
        assertEquals(OfflineLogLevel.ERROR, capturing.entries[1].level)
    }

    @Test
    fun levelFiltering_minError_suppressesAllButError() {
        // Given
        val capturing = CapturingLogger()
        val logger = capturing.asSdkLogger(OfflineLogLevel.ERROR)

        // When
        logger.debug { "d" }
        logger.info { "i" }
        logger.warning { "w" }
        logger.error { "e" }

        // Then
        assertEquals(1, capturing.entries.size)
        assertEquals(OfflineLogLevel.ERROR, capturing.entries[0].level)
    }

    @Test
    fun levelFiltering_minInfo_suppressesDebug() {
        // Given
        val capturing = CapturingLogger()
        val logger = capturing.asSdkLogger(OfflineLogLevel.INFO)

        // When
        logger.debug { "d" }
        logger.info { "i" }
        logger.warning { "w" }
        logger.error { "e" }

        // Then
        assertEquals(3, capturing.entries.size)
        assertEquals(OfflineLogLevel.INFO, capturing.entries[0].level)
    }

    @Test
    fun lazyEvaluation_messageLambdaNotCalled_whenFiltered() {
        // Given
        val capturing = CapturingLogger()
        val logger = capturing.asSdkLogger(OfflineLogLevel.ERROR)
        var debugEvaluated = false
        var infoEvaluated = false
        var warningEvaluated = false

        // When
        logger.debug { debugEvaluated = true; "d" }
        logger.info { infoEvaluated = true; "i" }
        logger.warning { warningEvaluated = true; "w" }

        // Then
        assertEquals(false, debugEvaluated)
        assertEquals(false, infoEvaluated)
        assertEquals(false, warningEvaluated)
        assertTrue(capturing.entries.isEmpty())
    }

    @Test
    fun lazyEvaluation_messageLambdaCalled_whenNotFiltered() {
        // Given
        val capturing = CapturingLogger()
        val logger = capturing.asSdkLogger(OfflineLogLevel.DEBUG)
        var called = false

        // When
        logger.debug { called = true; "msg" }

        // Then
        assertEquals(true, called)
        assertEquals("msg", capturing.entries.single().message)
    }

    @Test
    fun lazyEvaluation_nullLogger_messageLambdaNotCalled() {
        // Given
        val logger = SdkLogger(null, OfflineLogLevel.DEBUG)
        var called = false

        // When
        logger.debug { called = true; "msg" }

        // Then
        assertEquals(false, called)
    }

    @Test
    fun convenienceMethods_mapToCorrectLevel() {
        // Given
        val capturing = CapturingLogger()
        val logger = capturing.asSdkLogger()

        // When
        logger.debug { "a" }
        logger.info { "b" }
        logger.warning { "c" }
        logger.error { "d" }

        // Then
        assertEquals(OfflineLogLevel.DEBUG, capturing.entries[0].level)
        assertEquals(OfflineLogLevel.INFO, capturing.entries[1].level)
        assertEquals(OfflineLogLevel.WARNING, capturing.entries[2].level)
        assertEquals(OfflineLogLevel.ERROR, capturing.entries[3].level)
    }

    @Test
    fun logMethod_usesExplicitLevel() {
        // Given
        val capturing = CapturingLogger()
        val logger = capturing.asSdkLogger()

        // When
        logger.log(OfflineLogLevel.WARNING) { "explicit" }

        // Then
        assertEquals(1, capturing.entries.size)
        assertEquals(CapturingLogger.Entry(OfflineLogLevel.WARNING, "explicit"), capturing.entries[0])
    }
}
