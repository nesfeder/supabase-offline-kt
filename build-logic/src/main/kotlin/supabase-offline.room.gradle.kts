// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import androidx.room.gradle.RoomExtension
import io.github.nesfeder.supabase.offline.buildlogic.ExtractHostSqliteNative
import io.github.nesfeder.supabase.offline.buildlogic.HostSqliteNativeArguments
import io.github.nesfeder.supabase.offline.buildlogic.SchemaDirectoryArgument
import io.github.nesfeder.supabase.offline.buildlogic.catalogLibrary
import io.github.nesfeder.supabase.offline.buildlogic.hostSqliteNativeEntry
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// supabase-offline.room: this module owns a Room database. It applies KSP and Room, runs the Room
// compiler for every target, exports the schema to schemas/, and gives the Android host tests a
// SQLite they can load and the location of that schema.

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

// The Android driver ships only Android binaries, which a host JVM cannot load. Its loader takes
// an explicit binary instead, so the host tests get the one in the JVM artifact of the same driver.
private val hostSqliteNative: Configuration =
    configurations.create("hostSqliteNative") {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies.add(
    hostSqliteNative.name,
    dependencies.variantOf(catalogLibrary("androidx-sqlite-bundled-jvm")) { artifactType("jar") },
)

private val extractHostSqliteNative =
    tasks.register<ExtractHostSqliteNative>("extractHostSqliteNative") {
        driverJar.from(hostSqliteNative)
        nativeEntry.set(hostSqliteNativeEntry())
        outputDirectory.set(layout.buildDirectory.dir("host-sqlite-native"))
    }

tasks
    .withType<Test>()
    .matching { it.name == "testAndroidHostTest" }
    .configureEach {
        jvmArgumentProviders.add(
            HostSqliteNativeArguments(
                directory = extractHostSqliteNative.flatMap { it.outputDirectory },
                libraryName = hostSqliteNativeEntry().substringAfterLast('/'),
            )
        )
        jvmArgumentProviders.add(SchemaDirectoryArgument(schemaDirectory.asFile))
    }
