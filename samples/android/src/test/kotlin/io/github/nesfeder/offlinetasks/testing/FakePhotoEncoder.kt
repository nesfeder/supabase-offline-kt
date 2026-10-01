// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.testing

import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.PhotoEncoder
import io.github.nesfeder.offlinetasks.data.PhotoUnreadableException
import java.io.IOException

/** A [PhotoEncoder] that returns a small fixed JPEG, or fails for [unreadableUri]. */
class FakePhotoEncoder(private val unreadableUri: String? = null) : PhotoEncoder {
    /** Every URI it was asked to encode, in order. */
    val encodedUris = mutableListOf<String>()

    override suspend fun encode(contentUri: String): EncodedPhoto {
        if (contentUri == unreadableUri) throw PhotoUnreadableException(IOException("unreadable"))
        encodedUris += contentUri
        return EncodedPhoto(jpeg = byteArrayOf(-1, -40, -1, -39), width = 4, height = 3)
    }
}
