// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import io.github.nesfeder.supabase.offline.buildlogic.ExtractHostSqliteNative
import io.github.nesfeder.supabase.offline.buildlogic.HostSqliteNativeArguments
import io.github.nesfeder.supabase.offline.buildlogic.catalogLibrary
import io.github.nesfeder.supabase.offline.buildlogic.hostSqliteNativeEntry

// supabase-offline.host-test-sqlite: this module's Android host tests open SQLite through the
// bundled driver. The driver's Android artifact ships only Android binaries, which a host JVM
// cannot load; its loader takes an explicit binary instead, so the tests get the one for this
// machine from the JVM artifact of the same driver.

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
    }
