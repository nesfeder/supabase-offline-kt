// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge.internal

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** The description format's version; a description that names another is refused. */
internal const val DESCRIPTION_VERSION: Long = 1L

/**
 * [text] as the JSON object a description must be.
 *
 * @param what how an error names the description, such as `query`.
 * @throws InvalidDescriptionException when [text] is not a JSON object.
 */
internal fun parseDescriptionObject(text: String, what: String): DescriptionObject {
    val element =
        try {
            Json.parseToJsonElement(text)
        } catch (failure: SerializationException) {
            throw InvalidDescriptionException(
                "The $what is not valid JSON (${failure::class.simpleName})"
            )
        }
    if (element !is JsonObject) throw InvalidDescriptionException("The $what is not a JSON object")
    return DescriptionObject(element, what)
}

/**
 * [text] as JSON, for a body or a record the bridge passes on as it is.
 *
 * @throws InvalidDescriptionException when [text] is not valid JSON.
 */
internal fun parseJsonText(text: String, what: String): JsonElement =
    try {
        Json.parseToJsonElement(text)
    } catch (failure: SerializationException) {
        throw InvalidDescriptionException(
            "The $what is not valid JSON (${failure::class.simpleName})"
        )
    }

/**
 * One JSON object of a description, read strictly: a field of the wrong type, a missing required
 * one and, through [requireNoOtherFields], an unknown one are refused with the field's path, so the
 * Swift side and this one cannot drift apart silently.
 *
 * @property path where the object sits in the description, for error messages.
 */
internal class DescriptionObject(private val json: JsonObject, val path: String) {

    /** The fields read so far, for [requireNoOtherFields]. */
    private val readFields = mutableSetOf<String>()

    /** Whether [key] is present and not `null`. */
    fun has(key: String): Boolean = json[key].let { it != null && it !is JsonNull }

    /** Whether [key] is present at all, `null` included. */
    fun containsKey(key: String): Boolean = json.containsKey(key)

    /** The raw value of [key], or `null` when absent or `null`. */
    fun element(key: String): JsonElement? {
        readFields += key
        return json[key]?.takeUnless { it is JsonNull }
    }

    /** The string [key]; required. */
    fun string(key: String): String =
        optionalString(key) ?: throw InvalidDescriptionException("${fieldPath(key)} is required")

    /** The string [key], or `null` when absent. */
    fun optionalString(key: String): String? {
        val value = element(key) ?: return null
        if (value !is JsonPrimitive || !value.isString) wrongType(key, "a string")
        return value.content
    }

    /** The Boolean [key], or [default] when absent. */
    fun boolean(key: String, default: Boolean): Boolean {
        val value = element(key) ?: return default
        if (value !is JsonPrimitive || value.isString) wrongType(key, "true or false")
        return value.booleanOrNull ?: wrongType(key, "true or false")
    }

    /** The whole number [key], or `null` when absent. */
    fun optionalLong(key: String): Long? {
        val value = element(key) ?: return null
        if (value !is JsonPrimitive || value.isString) wrongType(key, "a whole number")
        return value.longOrNull ?: wrongType(key, "a whole number")
    }

    /** The whole number [key]; required. */
    fun long(key: String): Long =
        optionalLong(key) ?: throw InvalidDescriptionException("${fieldPath(key)} is required")

    /** The whole number [key] within `Int`, or `null` when absent. */
    fun optionalInt(key: String): Int? {
        val value = optionalLong(key) ?: return null
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            wrongType(key, "a whole number within 32 bits")
        }
        return value.toInt()
    }

    /** The object [key], or `null` when absent. */
    fun optionalObject(key: String): DescriptionObject? {
        val value = element(key) ?: return null
        if (value !is JsonObject) wrongType(key, "an object")
        return DescriptionObject(value, fieldPath(key))
    }

    /** The objects of the array [key]; empty when absent. */
    fun objects(key: String): List<DescriptionObject> {
        val value = element(key) ?: return emptyList()
        if (value !is JsonArray) wrongType(key, "an array of objects")
        return value.mapIndexed { index, item ->
            if (item !is JsonObject) wrongType("$key[$index]", "an object")
            DescriptionObject(item, "${fieldPath(key)}[$index]")
        }
    }

    /** The strings of the array [key], or `null` when absent. */
    fun optionalStrings(key: String): List<String>? {
        val value = element(key) ?: return null
        if (value !is JsonArray) wrongType(key, "an array of strings")
        return value.mapIndexed { index, item ->
            if (item !is JsonPrimitive || !item.isString) wrongType("$key[$index]", "a string")
            item.content
        }
    }

    /** The strings of the array [key]; required, `null` refused. */
    fun strings(key: String): List<String> =
        optionalStrings(key) ?: wrongType(key, "an array of strings")

    /** The whole numbers of the array [key], or `null` when absent. */
    fun optionalLongs(key: String): List<Long>? {
        val value = element(key) ?: return null
        if (value !is JsonArray) wrongType(key, "an array of whole numbers")
        return value.mapIndexed { index, item ->
            (item as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?: wrongType("$key[$index]", "a whole number")
        }
    }

    /** Refuses any field this object holds that the reader did not ask for. */
    fun requireNoOtherFields() {
        val unknown = json.keys - readFields
        if (unknown.isNotEmpty()) {
            throw InvalidDescriptionException(
                "${path}: unknown field ${unknown.sorted().joinToString { "\"$it\"" }}"
            )
        }
    }

    /** Refuses the description unless its `v` is [DESCRIPTION_VERSION]. */
    fun requireVersion() {
        val version = long("v")
        if (version != DESCRIPTION_VERSION) {
            throw UnsupportedDescriptionException(
                "${fieldPath("v")} is $version; this build reads version $DESCRIPTION_VERSION"
            )
        }
    }

    /** The dotted path of [key] in this object. */
    fun fieldPath(key: String): String = "$path.$key"

    /** Refuses [key] for not holding [expected]. */
    private fun wrongType(key: String, expected: String): Nothing =
        throw InvalidDescriptionException("${fieldPath(key)} must be $expected")
}
