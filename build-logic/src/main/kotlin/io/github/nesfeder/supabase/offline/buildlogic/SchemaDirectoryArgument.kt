// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.buildlogic

import java.io.File
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Passes the checked-in Room schemas to the schema continuity test as [SCHEMA_DIRECTORY_PROPERTY],
 * so the test never depends on its working directory.
 */
internal class SchemaDirectoryArgument(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val directory: File
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf("-D$SCHEMA_DIRECTORY_PROPERTY=${directory.absolutePath}")

    private companion object {
        /** The system property `OfflineDatabaseSchemaContinuityTest` reads. */
        const val SCHEMA_DIRECTORY_PROPERTY = "supabase.offline.schemaDirectory"
    }
}
