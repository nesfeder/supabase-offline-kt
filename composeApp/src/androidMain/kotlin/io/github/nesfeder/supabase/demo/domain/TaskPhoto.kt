package io.github.nesfeder.supabase.demo.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the `public.task_photos` row in `demo/supabase/schema.sql`.
 */
@Serializable
data class TaskPhoto(
    val id: String? = null,
    @SerialName("task_id") val taskId: String,
    @SerialName("bucket_id") val bucketId: String = "task-photos",
    @SerialName("object_path") val objectPath: String,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
)
