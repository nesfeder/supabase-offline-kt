// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.RealtimeFilter
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.RealtimeTables
import io.github.nesfeder.supabase.offline.bridge.BridgeError
import io.github.nesfeder.supabase.offline.bridge.BridgeErrorKind
import io.github.nesfeder.supabase.offline.bridge.BridgeRealtimeFilter
import io.github.nesfeder.supabase.offline.bridge.BridgeRealtimeStatus
import io.github.nesfeder.supabase.offline.bridge.BridgeRealtimeStatusKind
import io.github.nesfeder.supabase.offline.bridge.BridgeRealtimeTable

/** Names [tables] in the library's `realtime { }` block, as the app named them. */
internal fun RealtimeTables.name(tables: List<BridgeRealtimeTable>) {
    for (named in tables) {
        table(named.table, named.schema, named.filter?.toRealtimeFilter())
    }
}

/**
 * This filter as the library takes it: `in` takes `values`, every other operator `value`, as the
 * server evaluates them (`owner=in.(a,b)`, never `owner=in.a` or `id=eq.[1]`).
 *
 * @throws InvalidDescriptionException when the operator is not one the library's filters name, when
 *   both a value and values are given or neither, when `in` is given a value or no values, or when
 *   another operator is given values; the library refuses an operator Realtime cannot evaluate. The
 *   message never carries a value.
 */
internal fun BridgeRealtimeFilter.toRealtimeFilter(): RealtimeFilter {
    val comparison =
        FilterOperator.entries.firstOrNull { candidate -> candidate.identifier == operatorName }
            ?: throw InvalidDescriptionException(
                "A Realtime filter's operator must be eq, neq, gt, gte, lt, lte or in, not $operatorName"
            )
    val single = value
    val list = values
    if (single != null && list != null) {
        throw InvalidDescriptionException("A Realtime filter takes a value or values, not both")
    }
    val compared: Any =
        if (comparison == FilterOperator.IN) {
            when {
                list == null ->
                    throw InvalidDescriptionException(
                        if (single == null) "A Realtime in filter needs values"
                        else "A Realtime in filter takes values, not a value"
                    )
                list.isEmpty() ->
                    throw InvalidDescriptionException(
                        "A Realtime in filter needs at least one value"
                    )
                else -> list
            }
        } else {
            single
                ?: throw InvalidDescriptionException(
                    if (list == null) "A Realtime filter needs a value"
                    else "A Realtime $operatorName filter takes one value, not values"
                )
        }
    return RealtimeFilter(column, comparison, compared)
}

/** The channel's status; a stalled one carries what ended the channel as an error. */
internal fun RealtimeStatus.toBridge(): BridgeRealtimeStatus =
    when (this) {
        RealtimeStatus.Off -> BridgeRealtimeStatus(BridgeRealtimeStatusKind.OFF, null)
        RealtimeStatus.Paused -> BridgeRealtimeStatus(BridgeRealtimeStatusKind.PAUSED, null)
        RealtimeStatus.Subscribing ->
            BridgeRealtimeStatus(BridgeRealtimeStatusKind.SUBSCRIBING, null)
        RealtimeStatus.Subscribed -> BridgeRealtimeStatus(BridgeRealtimeStatusKind.SUBSCRIBED, null)
        is RealtimeStatus.Stalled ->
            BridgeRealtimeStatus(BridgeRealtimeStatusKind.STALLED, stallCauseOf(cause))
    }

/**
 * What stalled the channel, as an error. The library reports a join the server refused, or a
 * channel it closed, as an `IllegalStateException` of its own with the reason in its text: that is
 * the server's doing, not a defect, so it crosses as [BridgeErrorKind.SERVER] with the text, where
 * the general classification would hide the text behind [BridgeErrorKind.UNEXPECTED]. Every other
 * cause, such as a socket that could not be opened, is classified as any failure is.
 */
private fun stallCauseOf(cause: Throwable): BridgeError {
    val error = bridgeErrorOf(cause)
    if (error.kind != BridgeErrorKind.UNEXPECTED || cause !is IllegalStateException) return error
    val type = cause::class.simpleName ?: "IllegalStateException"
    return bridgeError(BridgeErrorKind.SERVER, cause.message ?: "The Realtime channel ended", type)
}
