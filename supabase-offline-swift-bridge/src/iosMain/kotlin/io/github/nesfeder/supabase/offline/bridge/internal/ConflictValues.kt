// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.nesfeder.supabase.offline.ConflictResolution
import io.github.nesfeder.supabase.offline.MutationConflict
import io.github.nesfeder.supabase.offline.bridge.BridgeConflict
import io.github.nesfeder.supabase.offline.bridge.BridgeConflictDecision
import io.github.nesfeder.supabase.offline.bridge.BridgeLogLevel
import io.github.nesfeder.supabase.offline.bridge.BridgeLogSink
import kotlinx.serialization.json.JsonObject

/** One answer of the app's resolver, as a conflict answer receives it. */
internal sealed interface ConflictReply {

    /** Resend the write against the server's row as it is now. */
    data object KeepLocal : ConflictReply

    /** Drop the write. */
    data object KeepServer : ConflictReply

    /** Send [columnsJson], once it is read as a JSON object, as the UPDATE's body. */
    data class Apply(val columnsJson: String) : ConflictReply

    /** Park the write. */
    data object Hold : ConflictReply

    /** The app's resolver failed; [message] is the app's and is never logged. */
    data class Failed(val message: String) : ConflictReply
}

/** The conflict as the app's resolver is shown it: rows as JSON text, the column sets as lists. */
internal fun MutationConflict.toBridge(): BridgeConflict =
    BridgeConflict(
        mutationId = mutationId,
        table = table,
        operation = operation.toBridge(),
        rowKeyJson = rowKey.toString(),
        baseJson = base.toString(),
        localJson = local?.toString(),
        serverJson = server?.toString(),
        changedOnServer = changedOnServer.toList(),
        conflictingColumns = conflictingColumns.toList(),
    )

/**
 * The resolution [reply] stands for. A failure, or columns that are not a JSON object of at least
 * one column, read as [ConflictResolution.Hold] and are logged at warn with [table] only.
 */
internal fun resolutionOf(
    reply: ConflictReply,
    table: String,
    logSink: BridgeLogSink?,
): ConflictResolution =
    when (reply) {
        ConflictReply.KeepLocal -> ConflictResolution.KeepLocal
        ConflictReply.KeepServer -> ConflictResolution.KeepServer
        ConflictReply.Hold -> ConflictResolution.Hold
        is ConflictReply.Failed -> {
            logSafely(
                logSink,
                BridgeLogLevel.WARNING,
                BRIDGE_LOG_TAG,
                "The conflict resolver failed for $table; the write is held",
            )
            ConflictResolution.Hold
        }
        is ConflictReply.Apply ->
            appliedColumnsOrNull(reply.columnsJson)?.let(ConflictResolution::apply)
                ?: ConflictResolution.Hold.also {
                    logSafely(
                        logSink,
                        BridgeLogLevel.WARNING,
                        BRIDGE_LOG_TAG,
                        "The conflict resolver's columns for $table are not a JSON object with a " +
                            "column; the write is held",
                    )
                }
    }

/** [text] as the columns of an Apply: a JSON object of at least one column, or `null`. */
private fun appliedColumnsOrNull(text: String): JsonObject? =
    try {
        (parseJsonText(text, "columns") as? JsonObject)?.takeIf { it.isNotEmpty() }
    } catch (_: InvalidDescriptionException) {
        // Text that is not JSON holds the write, as any other unusable answer does
        null
    }

/**
 * The resolution [decision] stands for, with [columnsJson] for APPLY, as the app settles an entry.
 *
 * @throws InvalidDescriptionException when [decision] is APPLY and [columnsJson] is not a JSON
 *   object.
 * @throws IllegalArgumentException when the object is empty, as the library refuses it.
 */
internal fun resolutionOf(
    decision: BridgeConflictDecision,
    columnsJson: String?,
): ConflictResolution =
    when (decision) {
        BridgeConflictDecision.KEEP_LOCAL -> ConflictResolution.KeepLocal
        BridgeConflictDecision.KEEP_SERVER -> ConflictResolution.KeepServer
        BridgeConflictDecision.HOLD -> ConflictResolution.Hold
        BridgeConflictDecision.APPLY -> {
            val text = columnsJson ?: throw InvalidDescriptionException("Apply needs its columns")
            val columns =
                parseJsonText(text, "columns") as? JsonObject
                    ?: throw InvalidDescriptionException("The columns must be a JSON object")
            ConflictResolution.apply(columns)
        }
    }
