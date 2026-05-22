package io.github.nesfeder.supabase.demo.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the `public.tasks` row in `demo/supabase/schema.sql`.
 *
 * `id`, `createdAt`, and `updatedAt` are server-managed (`default gen_random_uuid()` /
 * `default now()` plus a `moddatetime` trigger), so they are nullable here to support both
 * "fresh row to insert" and "row read from server" cases through the same data class.
 */
@Serializable
data class Task(
    val id: String? = null,
    val title: String,
    val notes: String? = null,
    @SerialName("is_done") val isDone: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * Patch shape sent on UPDATE. PostgREST only writes the columns present in the JSON body,
 * so we keep `id` / `createdAt` out and let the trigger refresh `updated_at`.
 */
@Serializable
data class TaskPatch(
    val title: String? = null,
    val notes: String? = null,
    @SerialName("is_done") val isDone: Boolean? = null,
)
