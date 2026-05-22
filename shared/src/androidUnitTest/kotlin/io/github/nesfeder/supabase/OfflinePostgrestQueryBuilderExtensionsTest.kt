package io.github.nesfeder.supabase

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class OfflinePostgrestQueryBuilderExtensionsTest {

    @Serializable
    data class TestData(val id: Int, val name: String)

    @Test
    fun `insert list calls insertWith with serializer`() = runTest {
        // Given
        val capture = CaptureQueryBuilder()
        val data = listOf(TestData(1, "A"), TestData(2, "B"))

        // When
        try {
            capture.insert(data)
            fail("Should have thrown TestException")
        } catch (e: TestException) {
            // Expected
        }

        // Then
        assertTrue(capture.insertWithListCalled)
        assertEquals(data, capture.lastValues)
        assertEquals(
            "io.github.nesfeder.supabase.OfflinePostgrestQueryBuilderExtensionsTest.TestData",
            capture.lastSerializer?.descriptor?.serialName
        )
    }

    @Test
    fun `insert value calls insertWith with serializer`() = runTest {
        // Given
        val capture = CaptureQueryBuilder()
        val data = TestData(1, "A")

        // When
        try {
            capture.insert(data)
            fail("Should have thrown TestException")
        } catch (e: TestException) {
            // Expected
        }

        // Then
        assertTrue(capture.insertWithValueCalled)
        assertEquals(data, capture.lastValue)
        assertEquals(
            "io.github.nesfeder.supabase.OfflinePostgrestQueryBuilderExtensionsTest.TestData",
            capture.lastSerializer?.descriptor?.serialName
        )
    }

    @Test
    fun `upsert list calls upsertWith with serializer`() = runTest {
        // Given
        val capture = CaptureQueryBuilder()
        val data = listOf(TestData(1, "A"), TestData(2, "B"))

        // When
        try {
            capture.upsert(data)
            fail("Should have thrown TestException")
        } catch (e: TestException) {
            // Expected
        }

        // Then
        assertTrue(capture.upsertWithListCalled)
        assertEquals(data, capture.lastValues)
        assertEquals(
            "io.github.nesfeder.supabase.OfflinePostgrestQueryBuilderExtensionsTest.TestData",
            capture.lastSerializer?.descriptor?.serialName
        )
    }

    @Test
    fun `upsert value calls upsertWith with serializer`() = runTest {
        // Given
        val capture = CaptureQueryBuilder()
        val data = TestData(1, "A")

        // When
        try {
            capture.upsert(data)
            fail("Should have thrown TestException")
        } catch (e: TestException) {
            // Expected
        }

        // Then
        assertTrue(capture.upsertWithValueCalled)
        assertEquals(data, capture.lastValue)
        assertEquals(
            "io.github.nesfeder.supabase.OfflinePostgrestQueryBuilderExtensionsTest.TestData",
            capture.lastSerializer?.descriptor?.serialName
        )
    }

    @Test
    fun `update value calls updateWith with serializer`() = runTest {
        // Given
        val capture = CaptureQueryBuilder()
        val data = TestData(1, "A")

        // When
        try {
            capture.update(data)
            fail("Should have thrown TestException")
        } catch (e: TestException) {
            // Expected
        }

        // Then
        assertTrue(capture.updateWithValueCalled)
        assertEquals(data, capture.lastValue)
        assertEquals(
            "io.github.nesfeder.supabase.OfflinePostgrestQueryBuilderExtensionsTest.TestData",
            capture.lastSerializer?.descriptor?.serialName
        )
    }
}
