// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Encoder

/**
 * [delegate], counting how many values it encodes: a test hands it to a component that encodes to
 * measure, and reads how often it did.
 */
internal class CountingSerializer<T>(private val delegate: KSerializer<T>) :
    KSerializer<T> by delegate {

    /** How many values were encoded through this serializer. */
    var encodings: Int = 0
        private set

    override fun serialize(encoder: Encoder, value: T) {
        encodings++
        delegate.serialize(encoder, value)
    }
}
