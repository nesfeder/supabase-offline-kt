// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import java.util.Properties

// Offline Tasks, the Android sample: a Jetpack Compose app on Material 3 Expressive. It is built on
// every CI run that touches a build input and never published.

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    // The build-tool floors on this module's lint tool, as on every module of the build.
    id("supabase-offline.build-tool-floors")
}

// The sample project's URL and publishable key, from the git-ignored local.properties at the
// repository root (local.properties.example). Blank when absent, as on CI: the app then runs
// without a backend and says so.
val localProperties: Properties =
    Properties().apply {
        providers
            .fileContents(isolated.rootProject.projectDirectory.file("local.properties"))
            .asText
            .orNull
            ?.let { load(it.reader()) }
    }

/** [value] as a Java string literal for a BuildConfig field. */
fun javaStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.nesfeder.offlinetasks"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.nesfeder.offlinetasks"
        minSdk = libs.versions.android.sample.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "SUPABASE_URL",
            javaStringLiteral(localProperties.getProperty("SUPABASE_URL").orEmpty()),
        )
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            javaStringLiteral(localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY").orEmpty()),
        )
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }

    // Robolectric renders the Compose UI tests on the JVM, which needs the merged resources.
    testOptions { unitTests { isIncludeAndroidResources = true } }

    lint {
        // Zero new issues: anything lint finds that is not in the baseline fails the build.
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
        // Checks that compare with the newest release on the network turn red by the calendar,
        // not by a change; Dependabot tracks versions instead.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
        disable += setOf("OldTargetApi")
    }
}

dependencies {
    // The library, from this build; the sample adopts it in a later change.
    implementation(projects.supabaseOfflineKt)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth.kt)
    implementation(libs.supabase.postgrest.kt)
    implementation(libs.supabase.storage.kt)
    implementation(libs.ktor.client.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.androidx.compose.material3.navigationSuite)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.testExt.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
