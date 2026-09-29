// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.buildlogic

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Points the Android driver's native loader at the extracted host binary.
 *
 * @param directory where [ExtractHostSqliteNative] put the binary.
 * @param libraryName the binary's file name.
 */
internal class HostSqliteNativeArguments(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) val directory: Provider<Directory>,
    @get:Input val libraryName: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf(
            "-Dandroidx.sqlite.driver.bundled.path=${directory.get().asFile.absolutePath}",
            "-Dandroidx.sqlite.driver.bundled.name=$libraryName",
        )
}
