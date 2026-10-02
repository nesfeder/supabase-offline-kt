// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * Marks what supabase-offline-kt keeps for itself: hooks its own artifacts use, and the interfaces
 * only the library may implement.
 *
 * Nothing it marks is part of the supported API. It can change or disappear in any release, and
 * implementing a marked interface outside the library breaks with the next member it gains. Tests
 * build a client with the `supabase-offline-kt-testing` artifact instead.
 */
@RequiresOptIn(
    message =
        "This API is internal to supabase-offline-kt and can change at any time. Tests build a " +
            "client with the supabase-offline-kt-testing artifact.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class OfflineInternal
