// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.buildlogic

import org.gradle.api.GradleException

/**
 * The opt-in markers named in `compat/abi-exclusions.txt`, whose declarations stay out of the ABI
 * records. Each rule is a line `annotation <fully qualified marker>`; `#` starts a comment.
 *
 * @param text the file's contents.
 * @param fileName how to name the file in an error.
 * @throws GradleException on any other line, so a malformed rule fails the build instead of being
 *   ignored.
 */
internal fun parseAbiExclusionMarkers(text: String, fileName: String): List<String> =
    text
        .lineSequence()
        .mapIndexedNotNull { index, line ->
            val rule = line.substringBefore('#').trim()
            if (rule.isEmpty()) return@mapIndexedNotNull null
            val parts = rule.split(Regex("\\s+"))
            if (parts.size != 2 || parts[0] != "annotation") {
                throw GradleException(
                    "$fileName:${index + 1}: expected `annotation <fully qualified marker>`, " +
                        "found \"$rule\""
                )
            }
            parts[1]
        }
        .toList()
