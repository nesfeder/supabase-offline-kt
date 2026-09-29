// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.buildlogic

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Path, inside the JVM driver artifact, of the SQLite binary for the machine running the build. */
internal fun hostSqliteNativeEntry(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val arm = arch == "aarch64" || arch == "arm64"
    return when {
        "mac" in os ->
            if (arm) "natives/osx_arm64/libsqliteJni.dylib"
            else "natives/osx_x64/libsqliteJni.dylib"
        "linux" in os ->
            if (arm) "natives/linux_arm64/libsqliteJni.so" else "natives/linux_x64/libsqliteJni.so"
        "windows" in os -> "natives/windows_x64/sqliteJni.dll"
        else -> error("No host SQLite binary for $os/$arch")
    }
}

/** Copies the host SQLite binary out of the JVM driver artifact. */
internal abstract class ExtractHostSqliteNative : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val driverJar: ConfigurableFileCollection

    @get:Input abstract val nativeEntry: Property<String>

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Inject abstract val archives: ArchiveOperations

    @get:Inject abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun extract() {
        val entry = nativeEntry.get()
        fileSystem.sync {
            driverJar.forEach { jar -> from(archives.zipTree(jar)) { include(entry) } }
            eachFile { path = name }
            includeEmptyDirs = false
            into(outputDirectory)
        }
    }
}
