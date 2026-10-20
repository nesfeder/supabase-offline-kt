// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.buildlogic

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Puts the Android framework jars Robolectric loads where its offline resolver looks: one
 * directory, each jar at its top level under the name Gradle resolved it with
 * (`android-all-instrumented-<version>.jar`), which is the name the resolver asks for.
 */
internal abstract class SyncRobolectricFrameworkJars : DefaultTask() {
    /** The jars Gradle resolved (`org.robolectric:android-all-instrumented`). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    /** Holds the jars alone. */
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Inject abstract val fileSystem: FileSystemOperations

    /** Replaces [outputDirectory]'s contents with the jars. */
    @TaskAction
    fun sync() {
        fileSystem.sync {
            from(jars)
            into(outputDirectory)
        }
    }
}
