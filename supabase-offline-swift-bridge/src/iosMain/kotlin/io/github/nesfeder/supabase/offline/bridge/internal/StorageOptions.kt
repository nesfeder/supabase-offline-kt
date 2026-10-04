// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.nesfeder.supabase.offline.bridge.internal

import io.github.jan.supabase.storage.FileObject
import io.github.jan.supabase.storage.FileObjectV2
import io.github.jan.supabase.storage.FileUploadResponse
import io.github.jan.supabase.storage.ImageTransformation
import io.github.jan.supabase.storage.SignedUrl
import io.github.jan.supabase.storage.SortOrder
import io.github.jan.supabase.storage.StorageListFilter
import io.github.jan.supabase.storage.UploadOptionBuilder
import io.github.jan.supabase.storage.UploadSignedUrl
import io.ktor.http.ContentType
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Applies an upload's options, `{"contentType", "upsert", "metadata"}`, each optional. */
internal fun UploadOptionBuilder.applyUploadOptions(text: String?) {
    if (text == null) return
    val options = parseDescriptionObject(text, "upload options")
    options.optionalString("contentType")?.let { contentType = ContentType.parse(it) }
    upsert = options.boolean("upsert", default = false)
    options.element("metadata")?.let { metadata ->
        userMetadata =
            metadata as? JsonObject
                ?: throw InvalidDescriptionException("${options.fieldPath("metadata")} must be an object")
    }
    options.requireNoOtherFields()
}

/** Applies an image transform, `{"width", "height", "resize", "quality", "format"}`. */
internal fun ImageTransformation.applyTransform(text: String) {
    val options = parseDescriptionObject(text, "image transform")
    options.optionalInt("width")?.let { width = it }
    options.optionalInt("height")?.let { height = it }
    options.optionalInt("quality")?.let { quality = it }
    options.optionalString("format")?.let { format = it }
    when (val resize = options.optionalString("resize")) {
        null -> Unit
        "cover" -> cover()
        "contain" -> contain()
        "fill" -> fill()
        else ->
            throw UnsupportedDescriptionException(
                "${options.fieldPath("resize")}: \"$resize\" is not a resize mode this build offers"
            )
    }
    options.requireNoOtherFields()
}

/** Applies a listing's options, `{"limit", "offset", "search", "sortBy": {"column", "order"}}`. */
internal fun StorageListFilter.Files.applyListOptions(text: String?) {
    if (text == null) return
    val options = parseDescriptionObject(text, "list options")
    options.optionalInt("limit")?.let { limit = it }
    options.optionalInt("offset")?.let { offset = it }
    options.optionalString("search")?.let { search = it }
    options.optionalObject("sortBy")?.let { sortBy ->
        val order =
            when (val name = sortBy.optionalString("order")) {
                null,
                "asc" -> SortOrder.ASC
                "desc" -> SortOrder.DESC
                else ->
                    throw UnsupportedDescriptionException(
                        "${sortBy.fieldPath("order")}: \"$name\" is not a sort order this build offers"
                    )
            }
        sortBy(sortBy.string("column"), order)
        sortBy.requireNoOtherFields()
    }
    options.requireNoOtherFields()
}

/** An upload's response, `{"id", "path", "key"}`. */
internal fun encodeUploadResponse(response: FileUploadResponse): String =
    buildJsonObject {
            put("id", response.id)
            put("path", response.path)
            put("key", response.key)
        }
        .toString()

/** A signed upload URL, `{"url", "path", "token"}`. */
internal fun encodeUploadSignedUrl(signed: UploadSignedUrl): String =
    buildJsonObject {
            put("url", signed.url)
            put("path", signed.path)
            put("token", signed.token)
        }
        .toString()

/** Signed download URLs, as supabase-kt serializes them. */
internal fun encodeSignedUrls(urls: List<SignedUrl>): String =
    Json.encodeToString(ListSerializer(SignedUrl.serializer()), urls)

/** A listing, as supabase-kt serializes its file objects. */
internal fun encodeFileObjects(objects: List<FileObject>): String =
    Json.encodeToString(ListSerializer(FileObject.serializer()), objects)

/** One object's details, as supabase-kt serializes them. */
internal fun encodeFileObject(details: FileObjectV2): String =
    Json.encodeToString(FileObjectV2.serializer(), details)

/** The bytes of this [NSData], copied once. */
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val copied = ByteArray(size)
    if (size > 0) copied.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return copied
}

/** These bytes as [NSData], copied once. */
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData()
    else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
