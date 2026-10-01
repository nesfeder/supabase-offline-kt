// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.platform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.PHOTO_MAX_BYTES
import io.github.nesfeder.offlinetasks.data.PhotoEncoder
import io.github.nesfeder.offlinetasks.data.PhotoUnreadableException
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The longest edge, in pixels, a photo is stored at. */
private const val MAX_EDGE_PIXELS = 2048

/** The JPEG quality every photo is encoded at. */
private const val JPEG_QUALITY = 88

/** How much smaller each attempt makes a photo that still does not fit the bucket's limit. */
private const val SHRINK_FACTOR = 0.75f

/**
 * Turns whatever the photo picker returns (HEIF, PNG, WebP, a camera JPEG) into the one format the
 * bucket accepts: a JPEG, upright, at most [MAX_EDGE_PIXELS] on its longest edge and under 5 MB.
 */
class JpegPhotoEncoder(private val contentResolver: ContentResolver) : PhotoEncoder {

    override suspend fun encode(contentUri: String): EncodedPhoto =
        try {
            encode(contentUri.toUri())
        } catch (unreadable: IOException) {
            throw PhotoUnreadableException(unreadable)
        }

    /**
     * Reads and re-encodes the image at [uri], off the main thread.
     *
     * @throws IOException when the image cannot be read or decoded.
     */
    private suspend fun encode(uri: Uri): EncodedPhoto =
        withContext(Dispatchers.Default) {
            var bitmap = decodeUpright(uri).onWhite()
            var jpeg = bitmap.toJpeg()
            while (jpeg.size > PHOTO_MAX_BYTES) {
                bitmap = bitmap.scaledBy(SHRINK_FACTOR)
                jpeg = bitmap.toJpeg()
            }
            EncodedPhoto(jpeg, bitmap.width, bitmap.height)
        }

    private fun decodeUpright(uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation itself.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) {
                decoder,
                info,
                _ ->
                val scale = scaleToFit(info.size.width, info.size.height)
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt(),
                    (info.size.height * scale).roundToInt(),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeWithBitmapFactory(uri)
        }

    private fun decodeWithBitmapFactory(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_EDGE_PIXELS) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded =
            openStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("The image could not be decoded")
        val rotation = openStream(uri).use { ExifInterface(it).rotationDegrees }
        val scale = scaleToFit(decoded.width, decoded.height)
        if (rotation == 0 && scale == 1f) return decoded
        val matrix =
            Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation.toFloat())
            }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private fun openStream(uri: Uri) =
        contentResolver.openInputStream(uri) ?: throw IOException("The image could not be opened")

    private fun scaleToFit(width: Int, height: Int): Float =
        (MAX_EDGE_PIXELS.toFloat() / max(width, height)).coerceAtMost(1f)

    /** A JPEG has no transparency, so a transparent image is laid on white, not black. */
    private fun Bitmap.onWhite(): Bitmap {
        if (!hasAlpha()) return this
        val opaque = createBitmap(width, height)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(this@onWhite, 0f, 0f, null)
        }
        return opaque
    }

    private fun Bitmap.toJpeg(): ByteArray =
        ByteArrayOutputStream().use { output ->
            compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            output.toByteArray()
        }

    private fun Bitmap.scaledBy(factor: Float): Bitmap =
        scale(
            (width * factor).roundToInt().coerceAtLeast(1),
            (height * factor).roundToInt().coerceAtLeast(1),
        )
}
