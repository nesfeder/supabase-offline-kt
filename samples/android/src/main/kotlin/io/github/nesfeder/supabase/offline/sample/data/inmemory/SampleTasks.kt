// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.data.inmemory

import io.github.nesfeder.supabase.offline.sample.data.Task
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The tasks the in-memory server holds for the first user, as if they had been added on another
 * day, so the first launch has something to show.
 */
internal fun sampleTasks(userId: String, now: Instant, newId: () -> String): List<Task> =
    listOf(
            SampleTask("Water the tomatoes", "Before nine, the bed by the fence", isDone = false),
            SampleTask("Book the bike service", "Brakes squeak; ask about the chain too", false),
            SampleTask("Pick up the film from the lab", null, isDone = false),
            SampleTask("Return the library books", "Three of them, due Friday", isDone = true),
            SampleTask("Renew the parking permit", null, isDone = true),
        )
        .mapIndexed { index, sample ->
            val createdAt = now - 2.hours - (index * 47).minutes
            Task(
                id = newId(),
                userId = userId,
                title = sample.title,
                notes = sample.notes,
                isDone = sample.isDone,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }

/** The content of one sample task. */
private data class SampleTask(val title: String, val notes: String?, val isDone: Boolean)
