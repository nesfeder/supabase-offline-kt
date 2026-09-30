// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import java.util.Properties
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.skie)
    `maven-publish`
    id("supabase-offline.kmp-library")
    id("supabase-offline.published")
    id("supabase-offline.host-test-sqlite")
    id("supabase-offline.room")
}

kotlin {
    android {
        namespace = "io.github.nesfeder.supabase.offline"

        // Robolectric host tests (Android framework types on the JVM).
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    // The SQLite the iOS targets link: "bundled" (the default; every published artifact) or
    // "system", the OS libsqlite3 the Swift package links. The bundled library exports the same
    // sqlite3_* symbols, so only a build without it reaches the system one: the choice is per
    // build. The iOS suite on the system SQLite:
    //
    // ./gradlew :supabase-offline-kt:iosSimulatorArm64Test -Psupabase.offline.iosSqlite=system
    val iosSqlite = providers.gradleProperty("supabase.offline.iosSqlite").getOrElse("bundled")
    check(iosSqlite == "bundled" || iosSqlite == "system") {
        "supabase.offline.iosSqlite must be \"bundled\" or \"system\", was \"$iosSqlite\""
    }
    val iosUsesSystemSqlite = iosSqlite == "system"

    val xcFramework = XCFramework("SupabaseOffline")
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "SupabaseOffline"
            isStatic = true

            // Export dependencies that appear in public API surface
            export(libs.supabase.postgrest.kt)
            export(libs.supabase.storage.kt)
            export(libs.kotlinx.serialization.json)

            xcFramework.add(this)
        }
        if (iosUsesSystemSqlite) {
            binaries.all {
                linkerOpts("-lsqlite3")
                // Room 2.8.4 was built against androidx.sqlite 2.6; this variant links 2.7.1. Any
                // ABI gap between them has to fail the link, not surface as a crash at runtime.
                freeCompilerArgs += "-Xpartial-linkage-loglevel=ERROR"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project.dependencies.platform(libs.supabase.bom))
            api(libs.supabase.auth.kt)
            api(libs.supabase.postgrest.kt)
            api(libs.supabase.storage.kt)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.ktor.client.core)
            // okio.Path appears in the public API (SupabaseOfflineConfig.offlineStoragePath).
            api(libs.okio)
            implementation(libs.androidx.room.runtime)
        }

        androidMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }

        iosMain {
            kotlin.srcDir(
                if (iosUsesSystemSqlite) "src/iosSystemSqliteMain/kotlin"
                else "src/iosBundledSqliteMain/kotlin"
            )
            dependencies {
                // The system variant needs androidx.sqlite 2.7: in 2.6.x the native driver refers
                // to
                // sqlite3_load_extension, which the iOS libsqlite3 does not export, and every open
                // fails enabling extension loading (SQLITE_MISUSE). Resolving 2.7 also lifts the
                // variant's androidx.sqlite core to 2.7; the bundled build is untouched.
                if (iosUsesSystemSqlite) {
                    // Strictly: a newer androidx.sqlite arriving through Room must fail resolution
                    // rather than silently change the SQLite layer this variant was verified on.
                    val systemSqlite = libs.versions.sqlite.system.get()
                    listOf(libs.androidx.sqlite.framework.system, libs.androidx.sqlite.system)
                        .forEach { library ->
                            implementation(library.get().module.toString()) {
                                version { strictly(systemSqlite) }
                            }
                        }
                } else {
                    implementation(libs.androidx.sqlite.bundled)
                }
            }
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }

        iosTest {
            kotlin.srcDir(
                if (iosUsesSystemSqlite) "src/iosSystemSqliteTest/kotlin"
                else "src/iosBundledSqliteTest/kotlin"
            )
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.test.core.ktx)
        }
    }
}

// The system-SQLite variant builds the Swift package and runs the iOS suite on Apple's SQLite. Its
// iOS publications would hand a Maven consumer Apple's SQLite without the -lsqlite3 it needs, in
// place of the bundled SQLite every published version carries, so no publish task may run with it.
if (providers.gradleProperty("supabase.offline.iosSqlite").orNull == "system") {
    tasks.withType<AbstractPublishToMaven>().configureEach {
        doFirst {
            throw GradleException(
                "Publishing is disabled with -Psupabase.offline.iosSqlite=system: that build is for the Swift " +
                    "package and the system-SQLite test run only. Publish from a build without the property."
            )
        }
    }
}

// The GitHub Packages repository, configured only when github.properties is present.
val githubProps = runCatching {
    Properties().apply {
        load(rootProject.file("github.properties").inputStream())
    }
}
    .getOrNull()

if (githubProps != null) {
    publishing {
        repositories {
            maven {
                url = uri("https://maven.pkg.github.com/nesfeder/supabase-offline-kt")
                credentials {
                    username = githubProps.getProperty("username")
                    password = githubProps.getProperty("token")
                }
            }
        }
    }
}
