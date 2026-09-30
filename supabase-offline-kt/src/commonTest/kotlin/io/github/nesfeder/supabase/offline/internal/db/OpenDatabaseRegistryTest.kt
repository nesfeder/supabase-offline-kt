// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okio.FileSystem

/** A database whose client's close() returned before it was released tells the next client so. */
class OpenDatabaseRegistryTest {

    private val databasePath =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "registry-${Random.nextLong().toULong()}" /
            OFFLINE_DATABASE_FILE_NAME

    @Test
    fun a_database_still_closing_says_so_and_how_many_calls_hold_it() {
        // Given — a claim whose client's close() returned with two calls still in flight
        val registration = OpenDatabaseRegistry.register(databasePath)
        registration.markStillClosing { 2 }

        // When
        val failure =
            assertFailsWith<IllegalStateException> { OpenDatabaseRegistry.register(databasePath) }
        registration.release()

        // Then — the message says the client is closing, not that it should be closed, and the
        // database opens once released
        val message = failure.message.orEmpty()
        assertTrue("still closing" in message && "2 call" in message, "the message was: $message")
        OpenDatabaseRegistry.register(databasePath).release()
    }

    @Test
    fun a_database_whose_last_call_has_just_finished_says_it_is_about_to_be_released() {
        // Given — a claim still closing, with every call finished but the release not yet run
        val registration = OpenDatabaseRegistry.register(databasePath)
        registration.markStillClosing { 0 }

        // When
        val failure =
            assertFailsWith<IllegalStateException> { OpenDatabaseRegistry.register(databasePath) }
        registration.release()

        // Then — it does not claim calls it no longer has, and says how to wait for the release
        val message = failure.message.orEmpty()
        assertTrue(
            "still closing" in message && "0 call" !in message && "close() again" in message,
            "the message was: $message",
        )
    }

    @Test
    fun a_database_held_by_an_open_client_asks_for_it_to_be_closed() {
        // Given
        val registration = OpenDatabaseRegistry.register(databasePath)

        // When
        val failure =
            assertFailsWith<IllegalStateException> { OpenDatabaseRegistry.register(databasePath) }
        registration.release()

        // Then
        assertTrue(
            "already open" in failure.message.orEmpty(),
            "the message was: ${failure.message}",
        )
    }
}
