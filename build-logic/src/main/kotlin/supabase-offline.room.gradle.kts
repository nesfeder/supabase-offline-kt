// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import androidx.room.gradle.RoomExtension
import io.github.nesfeder.supabase.offline.buildlogic.SchemaDirectoryArgument
import io.github.nesfeder.supabase.offline.buildlogic.catalogLibrary
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// supabase-offline.room: this module owns a Room database. It runs the Room compiler for every
// target, exports the schema to schemas/, and tells the Android host tests where that schema is.
// It applies KSP and Room itself, so a module lists neither.

pluginManager.apply("com.google.devtools.ksp")

pluginManager.apply("androidx.room")

private val schemaDirectory: Directory = layout.projectDirectory.dir("schemas")

extensions.configure<RoomExtension> { schemaDirectory(schemaDirectory.asFile.path) }

// Each target's main KSP configuration (kspAndroid, kspIosArm64, …), found from the targets so a
// new target cannot miss the compiler. Test and metadata configurations get none.
pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    val roomCompiler = catalogLibrary("androidx-room-compiler")
    extensions
        .getByType<KotlinMultiplatformExtension>()
        .targets
        .matching { it.platformType != KotlinPlatformType.common }
        .configureEach {
            val kspConfigurationName = "ksp" + name.replaceFirstChar(Char::uppercaseChar)
            configurations
                .matching { it.name == kspConfigurationName }
                .configureEach { project.dependencies.add(kspConfigurationName, roomCompiler) }
        }
}

tasks
    .withType<Test>()
    .matching { it.name == "testAndroidHostTest" }
    .configureEach {
        jvmArgumentProviders.add(SchemaDirectoryArgument(schemaDirectory.asFile))
    }
