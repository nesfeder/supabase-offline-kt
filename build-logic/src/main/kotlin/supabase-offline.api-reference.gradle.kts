// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import org.gradle.api.GradleException
import org.jetbrains.dokka.gradle.DokkaExtension

// supabase-offline.api-reference: the documentation site's Kotlin API reference, one Dokka HTML
// site for every published module, which the root project applies. `:dokkaGenerate` writes it to
// build/dokka/html; the docs workflows place that at the site's /api/. Each module's own Dokka
// configuration (supabase-offline.published) decides what it documents.

// The build-tool floors, which every role plugin applies: Dokka's generator runs here too.
pluginManager.apply("supabase-offline.build-tool-floors")

pluginManager.apply("org.jetbrains.dokka")

/** A property of the root gradle.properties that the reference needs. */
private fun requiredGradleProperty(key: String): String =
    providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }
        ?: throw GradleException("The root gradle.properties has no $key, which $path needs")

extensions.configure<DokkaExtension> {
    moduleName.set(requiredGradleProperty("POM_NAME"))
    moduleVersion.set(requiredGradleProperty("VERSION_NAME"))
}

// The published modules, each documented by its own Dokka configuration and gathered here.
listOf(":supabase-offline-kt", ":supabase-offline-kt-testing").forEach { module ->
    dependencies.add("dokka", project(module))
}
