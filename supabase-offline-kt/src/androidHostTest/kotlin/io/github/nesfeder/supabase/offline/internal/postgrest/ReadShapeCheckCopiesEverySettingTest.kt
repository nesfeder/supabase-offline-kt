// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.Postgrest
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The read-shape check builds each read through a PostgREST instance of its own, configured as the
 * app's (`readShapePostgrestOf`): `copy()` carries the settings of `Postgrest.Config`'s
 * constructor, and the rest are copied by hand. A setting supabase-kt adds outside the constructor
 * would be carried by neither, so every setting of the class and its superclasses is listed here,
 * and a new one fails the build until it is copied.
 */
class ReadShapeCheckCopiesEverySettingTest {

    @Test
    fun every_setting_of_the_postgrest_config_is_one_the_check_copies() {
        // Given — every setting supabase-kt's PostgREST config holds, on the JVM
        val settings =
            generateSequence<Class<*>>(Postgrest.Config::class.java) { type -> type.superclass }
                .takeWhile { type -> type != Any::class.java }
                .flatMap { type -> type.declaredFields.asSequence() }
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .map { field -> field.name }
                .toSet()

        // When
        val unknown = settings - CARRIED_BY_COPY - COPIED_BY_HAND
        val gone = (CARRIED_BY_COPY + COPIED_BY_HAND) - settings

        // Then
        assertTrue(
            unknown.isEmpty(),
            "supabase-kt's Postgrest.Config has settings the read-shape check does not know of: " +
                "$unknown. For each one declared outside the Config constructor, copy it by hand " +
                "in readShapePostgrestOf (internal/postgrest/ReadShape.kt), unless it is the token " +
                "or a retry setting, and add it to ReadShapeProbeContractTest's copy test; then " +
                "list it here.",
        )
        assertTrue(
            gone.isEmpty(),
            "supabase-kt's Postgrest.Config no longer has $gone: drop it from " +
                "readShapePostgrestOf, ReadShapeProbeContractTest and this list.",
        )
    }

    private companion object {
        /** The settings of `Postgrest.Config`'s constructor, which `copy()` carries. */
        val CARRIED_BY_COPY =
            setOf(
                "defaultSchema",
                "propertyConversionMethod",
                "requireValidSession",
                "urlLengthLimit",
                "timeout",
                "maxRetries",
            )

        /**
         * The settings declared outside it, in the config and in supabase-kt's `MainConfig`, which
         * `readShapePostgrestOf` copies by hand, or sets itself (the token).
         */
        val COPIED_BY_HAND =
            setOf("serializer", "customUrl", "jwtToken", "logLevel", "loggingFactory")
    }
}
