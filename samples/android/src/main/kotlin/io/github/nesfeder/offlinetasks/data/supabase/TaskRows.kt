// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A row of `tasks` as a read shows it. A task still queued shows exactly what was sent, so the
 * columns the server fills in (`updated_at`) are absent until it has the row.
 */
@Serializable
internal data class TaskRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    val notes: String? = null,
    val done: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * A new `tasks` row. The app makes the id and stamps `created_at`, so a task made offline is
 * complete and sorts right before it reaches the server; `updated_at` is the server's to set.
 */
@Serializable
internal data class NewTaskRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    val notes: String?,
    val done: Boolean,
    @SerialName("created_at") val createdAt: String,
)

/**
 * A `tasks` row exactly as the server holds it, every column and no defaults, as Realtime sends it:
 * applied to the cache, it replaces the cached row whole.
 */
@Serializable
internal data class TaskRecord(
    val id: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    val notes: String?,
    val done: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** The title and notes of a task, as autosave sends them; null notes clear them on the server. */
@Serializable internal data class TaskTextPatch(val title: String, val notes: String?)

/** A row of `task_photos` as a read shows it. */
@Serializable
internal data class TaskPhotoRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("object_path") val objectPath: String,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("created_at") val createdAt: String,
)

/** A new `task_photos` row, written once its file is in the bucket. */
@Serializable
internal data class NewTaskPhotoRow(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("object_path") val objectPath: String,
    val width: Int?,
    val height: Int?,
    @SerialName("created_at") val createdAt: String,
)

/** The domain task, marked pending when [pendingIds] holds its id. */
internal fun TaskRow.toTask(pendingIds: Set<String>): Task =
    Task(
        id = id,
        userId = userId,
        title = title,
        notes = notes,
        isDone = done,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt ?: createdAt),
        hasPendingMutations = id in pendingIds,
    )

/** The domain photo, marked pending when [pendingIds] holds its id. */
internal fun TaskPhotoRow.toTaskPhoto(pendingIds: Set<String>): TaskPhoto =
    TaskPhoto(
        id = id,
        userId = userId,
        taskId = taskId,
        objectPath = objectPath,
        width = width,
        height = height,
        createdAt = Instant.parse(createdAt),
        hasPendingMutations = id in pendingIds,
    )
