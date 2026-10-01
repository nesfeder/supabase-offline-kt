// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlin.time.Instant

/** The two tables the sample reads and writes, by their names in the schema. */
object Tables {
    /** `public.tasks`. */
    const val TASKS: String = "tasks"

    /** `public.task_photos`. */
    const val TASK_PHOTOS: String = "task_photos"
}

/** The longest title the schema accepts (`tasks_title_length`). */
const val TASK_TITLE_MAX_LENGTH: Int = 200

/** The longest notes the schema accepts (`tasks_notes_length`). */
const val TASK_NOTES_MAX_LENGTH: Int = 2_000

/**
 * A row of `tasks`. The app makes every [id], so a task created offline can be opened, edited and
 * given photos before it reaches the server.
 */
data class Task(
    val id: String,
    val userId: String,
    val title: String,
    val notes: String?,
    val isDone: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** True while a change to this task waits in the mutation queue. */
    val hasPendingMutations: Boolean = false,
)

/**
 * A row of `task_photos`: one JPEG in the private `task-photos` bucket at [objectPath], which is
 * always `<user id>/<task id>/<photo id>.jpg` in lower case.
 */
data class TaskPhoto(
    val id: String,
    val userId: String,
    val taskId: String,
    val objectPath: String,
    val width: Int?,
    val height: Int?,
    val createdAt: Instant,
    /** True while this photo's row waits in the mutation queue. */
    val hasPendingMutations: Boolean = false,
)

/** The only object path the schema allows for a photo; the ids are lower-case UUID text. */
fun photoObjectPath(userId: String, taskId: String, photoId: String): String =
    "$userId/$taskId/$photoId.jpg"

/**
 * Whether the server would refuse [title]: blank (spaces, tabs or line breaks only) or too long.
 */
fun isTitleRefusedByServer(title: String): Boolean =
    title.trim(' ', '\t', '\r', '\n').isEmpty() || characterCount(title) > TASK_TITLE_MAX_LENGTH

/** Whether the server would refuse [notes]: longer than its limit. */
fun areNotesRefusedByServer(notes: String?): Boolean =
    notes != null && characterCount(notes) > TASK_NOTES_MAX_LENGTH

/**
 * How many characters the schema's `char_length` counts in [text]: code points, so an emoji made of
 * two UTF-16 units counts once, as it does on the server.
 */
fun characterCount(text: String): Int {
    var count = 0
    var index = 0
    while (index < text.length) {
        index += if (startsSurrogatePair(text, index)) 2 else 1
        count++
    }
    return count
}

/** The first [maxCharacters] characters of [text], counted as [characterCount] counts them. */
fun takeCharacters(text: String, maxCharacters: Int): String {
    var count = 0
    var index = 0
    while (index < text.length && count < maxCharacters) {
        index += if (startsSurrogatePair(text, index)) 2 else 1
        count++
    }
    return text.substring(0, index)
}

private fun startsSurrogatePair(text: String, index: Int): Boolean =
    text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()
