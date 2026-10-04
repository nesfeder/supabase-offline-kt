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
import io.ktor.http.BadContentTypeFormatException
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

/**
 * An upload's options, read from `{"contentType", "upsert", "metadata"}`, each optional, before
 * anything is sent: supabase-kt applies them later, where a malformed one would be reported as a
 * failed request.
 */
internal class UploadOptions(
    private val contentType: ContentType?,
    private val upsert: Boolean,
    private val metadata: JsonObject?,
) {
    /** Sets these options on supabase-kt's builder. */
    fun applyTo(builder: UploadOptionBuilder) {
        contentType?.let { builder.contentType = it }
        builder.upsert = upsert
        metadata?.let { builder.userMetadata = it }
    }
}

/** [text] as [UploadOptions]; `null` text is no options. */
internal fun uploadOptionsOf(text: String?): UploadOptions {
    if (text == null) return UploadOptions(contentType = null, upsert = false, metadata = null)
    val options = parseDescriptionObject(text, "upload options")
    val contentType =
        options.optionalString("contentType")?.let { name ->
            try {
                ContentType.parse(name)
            } catch (_: BadContentTypeFormatException) {
                throw InvalidDescriptionException(
                    "${options.fieldPath("contentType")} is not a content type"
                )
            }
        }
    val metadata =
        options.element("metadata")?.let { value ->
            value as? JsonObject
                ?: throw InvalidDescriptionException(
                    "${options.fieldPath("metadata")} must be an object"
                )
        }
    val upload =
        UploadOptions(contentType, options.boolean("upsert", default = false), metadata)
    options.requireNoOtherFields()
    return upload
}

/**
 * An image transform, read from `{"width", "height", "resize", "quality", "format"}` before
 * anything is sent.
 */
internal class ImageTransform(
    private val width: Int?,
    private val height: Int?,
    private val quality: Int?,
    private val format: String?,
    private val resize: ImageTransformation.Resize?,
) {
    /** Sets this transform on supabase-kt's builder. */
    fun applyTo(transformation: ImageTransformation) {
        width?.let { transformation.width = it }
        height?.let { transformation.height = it }
        quality?.let { transformation.quality = it }
        format?.let { transformation.format = it }
        resize?.let { transformation.resize = it }
    }
}

/** [text] as an [ImageTransform]; `quality` must lie between 1 and 100, as supabase-kt asks. */
internal fun imageTransformOf(text: String): ImageTransform {
    val options = parseDescriptionObject(text, "image transform")
    val quality = options.optionalInt("quality")
    if (quality != null && quality !in 1..100) {
        throw InvalidDescriptionException("${options.fieldPath("quality")} must lie in 1...100")
    }
    val resize =
        when (val name = options.optionalString("resize")) {
            null -> null
            "cover" -> ImageTransformation.Resize.COVER
            "contain" -> ImageTransformation.Resize.CONTAIN
            "fill" -> ImageTransformation.Resize.FILL
            else ->
                throw UnsupportedDescriptionException(
                    "${options.fieldPath("resize")}: \"$name\" is not a resize mode this build " +
                        "offers"
                )
        }
    val transform =
        ImageTransform(
            width = options.optionalInt("width"),
            height = options.optionalInt("height"),
            quality = quality,
            format = options.optionalString("format"),
            resize = resize,
        )
    options.requireNoOtherFields()
    return transform
}

/**
 * A listing's options, read from `{"limit", "offset", "search", "sortBy": {"column", "order"}}`
 * before anything is sent.
 */
internal class ListOptions(
    private val limit: Int?,
    private val offset: Int?,
    private val search: String?,
    private val sortColumn: String?,
    private val sortOrder: SortOrder,
) {
    /** Sets these options on supabase-kt's filter. */
    fun applyTo(filter: StorageListFilter.Files) {
        limit?.let { filter.limit = it }
        offset?.let { filter.offset = it }
        search?.let { filter.search = it }
        sortColumn?.let { filter.sortBy(it, sortOrder) }
    }
}

/** [text] as [ListOptions]; `null` text is no options. */
internal fun listOptionsOf(text: String?): ListOptions {
    if (text == null) return ListOptions(null, null, null, null, SortOrder.ASC)
    val options = parseDescriptionObject(text, "list options")
    val sortBy = options.optionalObject("sortBy")
    val sortOrder =
        when (val name = sortBy?.optionalString("order")) {
            null,
            "asc" -> SortOrder.ASC
            "desc" -> SortOrder.DESC
            else ->
                throw UnsupportedDescriptionException(
                    "${sortBy.fieldPath("order")}: \"$name\" is not a sort order this build offers"
                )
        }
    val list =
        ListOptions(
            limit = options.optionalInt("limit"),
            offset = options.optionalInt("offset"),
            search = options.optionalString("search"),
            sortColumn = sortBy?.string("column"),
            sortOrder = sortOrder,
        )
    sortBy?.requireNoOtherFields()
    options.requireNoOtherFields()
    return list
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
