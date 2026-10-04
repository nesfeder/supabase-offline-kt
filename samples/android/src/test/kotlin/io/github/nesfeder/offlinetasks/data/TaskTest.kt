// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskTest {

    @Test
    fun lengths_are_counted_in_characters_as_the_server_counts_them() {
        // Given — a title of 200 emoji, each two UTF-16 units but one character
        val emoji = "🚀"
        val title = emoji.repeat(TASK_TITLE_MAX_LENGTH)

        // Then — 200 characters: within the limit, and cut there without splitting one
        assertEquals(TASK_TITLE_MAX_LENGTH, characterCount(title))
        assertFalse(isTitleRefusedByServer(title))
        assertTrue(isTitleRefusedByServer(title + emoji))
        assertEquals(title, takeCharacters(title + emoji, TASK_TITLE_MAX_LENGTH))
    }

    @Test
    fun a_photo_lives_at_the_one_path_the_schema_allows() {
        // Then — `<user id>/<task id>/<photo id>.jpg`, as the row's check and Storage require
        assertEquals("u/t/p.jpg", photoObjectPath(userId = "u", taskId = "t", photoId = "p"))
    }
}
