// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.postgrest.RpcMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.RpcRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.ReadStrategy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A read, as the Swift side describes it: the table, the columns and the request's shape, applied
 * through `select(columns, readStrategy) { … }` or `selectAsFlow`.
 */
internal class ReadDescription(
    val table: String,
    val schema: String?,
    val columns: Columns,
    private val head: Boolean,
    private val shape: RequestShape,
    private val strategy: DescriptionObject?,
) {
    /** The request lambda: the same calls, in the same order, every time it runs. */
    fun applyTo(request: SelectRequestBuilder) {
        shape.applyTo(request)
        if (head) request.head = true
    }

    /** The one-shot strategy; `NetworkFirst()` when the description names none. */
    fun readStrategy(): ReadStrategy {
        val strategy = strategy ?: return ReadStrategy.NetworkFirst()
        val maxAge = strategy.optionalLong("maxAgeMillis")?.milliseconds
        val chosen =
            when (val kind = strategy.string("kind")) {
                "networkOnly" -> ReadStrategy.NetworkOnly.also { refuseMaxAge(maxAge, kind) }
                "networkFirst" -> ReadStrategy.NetworkFirst(maxAge)
                "cacheFirst" -> ReadStrategy.CacheFirst(maxAge)
                "cacheOnly" -> ReadStrategy.CacheOnly(maxAge)
                else -> unsupportedStrategy(strategy, kind)
            }
        strategy.requireNoOtherFields()
        return chosen
    }

    /** The live strategy; `CacheFirstThenNetwork` when the description names none. */
    fun flowReadStrategy(): FlowReadStrategy {
        val strategy = strategy ?: return FlowReadStrategy.CacheFirstThenNetwork
        val chosen =
            when (val kind = strategy.string("kind")) {
                "cacheFirstThenNetwork" -> FlowReadStrategy.CacheFirstThenNetwork
                "networkFirstThenCache" -> FlowReadStrategy.NetworkFirstThenCache
                "cacheOnly" -> FlowReadStrategy.CacheOnly
                else -> unsupportedStrategy(strategy, kind)
            }
        strategy.requireNoOtherFields()
        return chosen
    }

    /** Refuses a `maxAgeMillis` on a strategy that serves no cached rows. */
    private fun refuseMaxAge(maxAge: Duration?, kind: String) {
        if (maxAge != null) {
            throw InvalidDescriptionException("strategy \"$kind\" takes no maxAgeMillis")
        }
    }
}

/**
 * [text] as a [ReadDescription].
 *
 * @throws InvalidDescriptionException when a field is missing, unknown or of the wrong type.
 * @throws UnsupportedDescriptionException when it asks for what this build does not offer.
 */
internal fun readDescription(text: String): ReadDescription {
    val description = parseDescriptionObject(text, "query")
    description.requireVersion()
    val read =
        ReadDescription(
            table = description.string("table"),
            schema = description.optionalString("schema"),
            columns = Columns.raw(description.optionalString("columns") ?: "*"),
            head = description.boolean("head", default = false),
            shape = readRequestShape(description),
            strategy = description.optionalObject("strategy"),
        )
    description.requireNoOtherFields()
    return read
}

/**
 * A write, as the Swift side describes it; the body travels beside it as JSON text, so its numbers
 * are never re-encoded.
 */
internal class MutationDescription(
    val operation: MutationOperation,
    val table: String,
    val schema: String?,
    val strategy: MutationStrategy,
    private val returning: Columns?,
    private val onConflict: String?,
    private val ignoreDuplicates: Boolean,
    private val defaultToNull: Boolean,
    private val shape: RequestShape,
) {
    /** The request lambda of every operation: returned columns, then the shape. */
    fun applyTo(request: PostgrestRequestBuilder) {
        returning?.let(request::select)
        shape.applyTo(request)
        if (request is InsertRequestBuilder) request.defaultToNull = defaultToNull
        if (request is UpsertRequestBuilder) {
            request.onConflict = onConflict
            request.ignoreDuplicates = ignoreDuplicates
        }
    }
}

/**
 * [text] as a [MutationDescription]. `onConflict` and `ignoreDuplicates` belong to an upsert and
 * `defaultToNull` to an insert or upsert; anywhere else they are refused.
 */
internal fun mutationDescription(text: String): MutationDescription {
    val description = parseDescriptionObject(text, "mutation")
    description.requireVersion()
    val operation =
        when (val name = description.string("operation")) {
            "insert" -> MutationOperation.INSERT
            "upsert" -> MutationOperation.UPSERT
            "update" -> MutationOperation.UPDATE
            "delete" -> MutationOperation.DELETE
            else ->
                throw UnsupportedDescriptionException(
                    "${description.fieldPath("operation")}: \"$name\" is not a write this build " +
                        "offers"
                )
        }
    val isInsert = operation == MutationOperation.INSERT || operation == MutationOperation.UPSERT
    refuseUnless(description, "onConflict", operation == MutationOperation.UPSERT)
    refuseUnless(description, "ignoreDuplicates", operation == MutationOperation.UPSERT)
    refuseUnless(description, "defaultToNull", isInsert)
    val mutation =
        MutationDescription(
            operation = operation,
            table = description.string("table"),
            schema = description.optionalString("schema"),
            strategy = readMutationStrategy(description),
            returning = description.optionalString("returning")?.let(Columns::raw),
            onConflict = description.optionalString("onConflict"),
            ignoreDuplicates = description.boolean("ignoreDuplicates", default = false),
            defaultToNull = description.boolean("defaultToNull", default = true),
            shape = readRequestShape(description),
        )
    description.requireNoOtherFields()
    return mutation
}

/**
 * A call of a database function, as the Swift side describes it; its arguments travel beside it as
 * JSON text.
 */
internal class RpcDescription(
    val function: String,
    private val schema: String?,
    private val method: RpcMethod,
    private val shape: RequestShape,
) {
    /** The request lambda: schema, method, then the shape. */
    fun applyTo(request: RpcRequestBuilder) {
        schema?.let { request.schema = it }
        request.method = method
        shape.applyTo(request)
    }

    /**
     * Refuses [arguments] on a call by GET or HEAD: supabase-kt 3.6 reads the method before the
     * request lambda sets it, so it would send them in a body PostgREST ignores.
     */
    fun refuseArgumentsUnlessPost(arguments: String?) {
        if (arguments != null && method != RpcMethod.POST) {
            throw InvalidDescriptionException(
                "A function called by ${method.name} takes no arguments: supabase-kt would send " +
                    "them in a body the server ignores; call it by POST"
            )
        }
    }
}

/** [text] as an [RpcDescription]; `method` is `post` (the default), `get` or `head`. */
internal fun rpcDescription(text: String): RpcDescription {
    val description = parseDescriptionObject(text, "call")
    description.requireVersion()
    val method =
        when (val name = description.optionalString("method")) {
            null,
            "post" -> RpcMethod.POST
            "get" -> RpcMethod.GET
            "head" -> RpcMethod.HEAD
            else ->
                throw UnsupportedDescriptionException(
                    "${description.fieldPath("method")}: \"$name\" is not a method this build offers"
                )
        }
    val rpc =
        RpcDescription(
            function = description.string("function"),
            schema = description.optionalString("schema"),
            method = method,
            shape = readRequestShape(description),
        )
    description.requireNoOtherFields()
    return rpc
}

/** `strategy`: `queueIfOffline` (the default) or `onlineOnly`. */
private fun readMutationStrategy(description: DescriptionObject): MutationStrategy =
    when (val name = description.optionalString("strategy")) {
        null,
        "queueIfOffline" -> MutationStrategy.QueueIfOffline
        "onlineOnly" -> MutationStrategy.OnlineOnly
        else ->
            throw UnsupportedDescriptionException(
                "${description.fieldPath("strategy")}: \"$name\" is not a strategy this build offers"
            )
    }

/** Refuses [key] in [description] unless [allowed]. */
private fun refuseUnless(description: DescriptionObject, key: String, allowed: Boolean) {
    if (!allowed && description.has(key)) {
        throw InvalidDescriptionException(
            "${description.fieldPath(key)} does not apply to this operation"
        )
    }
}

/** Refuses a strategy [kind] this build does not offer. */
private fun unsupportedStrategy(strategy: DescriptionObject, kind: String): Nothing =
    throw UnsupportedDescriptionException(
        "${strategy.fieldPath("kind")}: \"$kind\" is not a strategy this build offers"
    )
