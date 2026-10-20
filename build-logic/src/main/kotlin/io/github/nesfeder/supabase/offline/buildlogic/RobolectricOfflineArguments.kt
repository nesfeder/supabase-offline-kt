// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.buildlogic

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Keeps Robolectric off the network: its offline resolver, reading the framework jars from
 * [directory] and nowhere else.
 *
 * @param directory where [SyncRobolectricFrameworkJars] put the jars.
 */
internal class RobolectricOfflineArguments(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    val directory: Provider<Directory>
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf(
            "-Drobolectric.offline=true",
            "-Drobolectric.dependency.dir=${directory.get().asFile.absolutePath}",
        )
}
