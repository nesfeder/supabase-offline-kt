// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import io.github.nesfeder.supabase.offline.buildlogic.catalogLibrary
import io.github.nesfeder.supabase.offline.buildlogic.catalogVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// supabase-offline.kmp-library: what every Kotlin Multiplatform library module shares. A module
// keeps its own namespace, host-test setup and dependencies.

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    extensions.configure<KotlinMultiplatformExtension> {
        explicitApi()

        iosArm64()
        iosSimulatorArm64()

        // Expect/actual classes are Beta; this silences the warning they raise.
        // See: https://youtrack.jetbrains.com/issue/KT-61573
        compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

        // The Android target is created by the module's `android { namespace = … }`.
        targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            compileSdk = catalogVersion("android-compileSdk").toInt()
            minSdk = catalogVersion("android-minSdk").toInt()
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }

        sourceSets.named("commonTest") {
            dependencies {
                implementation(catalogLibrary("kotlin-test"))
                implementation(catalogLibrary("kotlinx-coroutines-test"))
            }
        }
    }
}
