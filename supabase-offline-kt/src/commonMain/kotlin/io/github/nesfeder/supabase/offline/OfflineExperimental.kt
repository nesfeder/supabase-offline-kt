// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * Marks API whose shape or behaviour may still change in a minor release.
 *
 * Using it is a warning, not an error: opt in with `@OptIn(OfflineExperimental::class)` once you
 * have read what the marked declaration says about how it may change. A release that settles it
 * removes the marker, which needs no change at the call site.
 */
@RequiresOptIn(
    message =
        "This supabase-offline-kt API is experimental: its behaviour may change in a minor " +
            "release. Read its documentation before relying on it.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class OfflineExperimental
