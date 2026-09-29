// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflinePostgrestQueryBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

/**
 * The reified `insert` / `upsert` / `update` extensions must route onto the matching typed verb
 * with the serializer of the element type, forwarding their other arguments untouched.
 */
class OfflinePostgrestExtensionsTest {

    private val rowSerialName = ExtensionTestRow.serializer().descriptor.serialName

    @Test
    fun insert_list_calls_insertWith_with_the_element_serializer() = runTest {
        // Given
        val builder = RecordingOfflinePostgrestQueryBuilder()
        val rows = listOf(ExtensionTestRow(1, "A"), ExtensionTestRow(2, "B"))

        // When
        val outcome = builder.insert(rows)

        // Then
        val call = builder.calls.single()
        assertEquals("insertWith(list)", call.verb)
        assertEquals(rows, call.values)
        assertEquals(rowSerialName, call.serializer.descriptor.serialName)
        assertEquals(MutationStrategy.QueueIfOffline, call.mutationStrategy)
        assertEquals(MutationOutcome.Queued, outcome)
    }

    @Test
    fun insert_value_calls_insertWith_with_the_value_serializer() = runTest {
        // Given
        val builder = RecordingOfflinePostgrestQueryBuilder()
        val row = ExtensionTestRow(1, "A")

        // When
        builder.insert(row, mutationStrategy = MutationStrategy.OnlineOnly)

        // Then
        val call = builder.calls.single()
        assertEquals("insertWith(value)", call.verb)
        assertEquals(listOf(row), call.values)
        assertEquals(rowSerialName, call.serializer.descriptor.serialName)
        assertEquals(MutationStrategy.OnlineOnly, call.mutationStrategy)
    }

    @Test
    fun upsert_list_calls_upsertWith_with_the_element_serializer() = runTest {
        // Given
        val builder = RecordingOfflinePostgrestQueryBuilder()
        val rows = listOf(ExtensionTestRow(1, "A"), ExtensionTestRow(2, "B"))

        // When
        builder.upsert(rows)

        // Then
        val call = builder.calls.single()
        assertEquals("upsertWith(list)", call.verb)
        assertEquals(rows, call.values)
        assertEquals(rowSerialName, call.serializer.descriptor.serialName)
    }

    @Test
    fun upsert_value_calls_upsertWith_with_the_value_serializer() = runTest {
        // Given
        val builder = RecordingOfflinePostgrestQueryBuilder()
        val row = ExtensionTestRow(1, "A")

        // When
        builder.upsert(row)

        // Then
        val call = builder.calls.single()
        assertEquals("upsertWith(value)", call.verb)
        assertEquals(listOf(row), call.values)
        assertEquals(rowSerialName, call.serializer.descriptor.serialName)
    }

    @Test
    fun update_value_calls_updateWith_with_the_value_serializer_and_delta_columns() = runTest {
        // Given
        val builder = RecordingOfflinePostgrestQueryBuilder()
        val row = ExtensionTestRow(1, "A")
        val deltas = listOf(NumericDeltaColumn(column = "id", snapshotValue = 0.0))

        // When
        builder.update(row, deltaColumns = deltas)

        // Then
        val call = builder.calls.single()
        assertEquals("updateWith(value)", call.verb)
        assertEquals(listOf(row), call.values)
        assertEquals(rowSerialName, call.serializer.descriptor.serialName)
        assertEquals(deltas, call.deltaColumns)
    }
}

/** Row type for [OfflinePostgrestExtensionsTest]. */
@Serializable internal data class ExtensionTestRow(val id: Int, val name: String)
