import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.skie)
    alias(libs.plugins.kover)
    `maven-publish`
}

kotlin {
    explicitApi()

    android {
        namespace = "io.github.nesfeder.supabase.offline"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        // Robolectric host tests (Android framework types on the JVM).
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    val xcFramework = XCFramework("SupabaseOffline")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SupabaseOffline"
            isStatic = true

            // Export dependencies that appear in public API surface
            export(libs.supabase.postgrest.kt)
            export(libs.supabase.storage.kt)
            export(libs.kotlinx.serialization.json)

            xcFramework.add(this)
        }
    }

    // Suppress Beta warning for expect/actual classes for all Kotlin targets in this module
    // See: https://youtrack.jetbrains.com/issue/KT-61573
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
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
            implementation(libs.androidx.sqlite.bundled)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.androidx.test.core.ktx)
        }
    }
}

// Host-native SQLite for the Robolectric host tests. The Android BundledSQLiteDriver ships only
// Android-ABI binaries, which a host JVM cannot load, but its loader accepts an explicit binary
// through the androidx.sqlite.driver.bundled.path / .name system properties. The binary comes from
// the JVM artifact of the same driver version and is used by the host tests only, so they can open
// the library's real Room database.
val hostSqliteNative: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

// KSP dependencies per target (Room compiler)
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    hostSqliteNative(variantOf(libs.androidx.sqlite.bundled.jvm) { artifactType("jar") })
}

/** Path, inside the JVM driver artifact, of the SQLite binary for the machine running the build. */
fun hostSqliteNativeEntry(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val arm = arch == "aarch64" || arch == "arm64"
    return when {
        "mac" in os -> if (arm) "natives/osx_arm64/libsqliteJni.dylib" else "natives/osx_x64/libsqliteJni.dylib"
        "linux" in os -> if (arm) "natives/linux_arm64/libsqliteJni.so" else "natives/linux_x64/libsqliteJni.so"
        "windows" in os -> "natives/windows_x64/sqliteJni.dll"
        else -> error("No host SQLite binary for $os/$arch")
    }
}

/** Copies the host SQLite binary out of the JVM driver artifact. */
abstract class ExtractHostSqliteNative : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val driverJar: ConfigurableFileCollection

    @get:Input
    abstract val nativeEntry: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

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

/** Points the Android driver's native loader at the extracted host binary. */
class HostSqliteNativeArguments(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    val directory: Provider<Directory>,
    @get:Input
    val libraryName: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Dandroidx.sqlite.driver.bundled.path=${directory.get().asFile.absolutePath}",
        "-Dandroidx.sqlite.driver.bundled.name=$libraryName",
    )
}

val extractHostSqliteNative = tasks.register<ExtractHostSqliteNative>("extractHostSqliteNative") {
    driverJar.from(hostSqliteNative)
    nativeEntry.set(hostSqliteNativeEntry())
    outputDirectory.set(layout.buildDirectory.dir("host-sqlite-native"))
}

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    jvmArgumentProviders.add(
        HostSqliteNativeArguments(
            directory = extractHostSqliteNative.flatMap { it.outputDirectory },
            libraryName = hostSqliteNativeEntry().substringAfterLast('/'),
        ),
    )
}

room {
    schemaDirectory("$projectDir/schemas")
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*.*Factory*",
                    "*.*Stubs*",
                    "*.*_Impl*",
                    "*.*Database*",
                    "*.*Entities*",
                    // Exclude synthetic/default interface implementation wrappers generated by Kotlin
                    "*.*DefaultImpls*",
                    // Exclude the interface-level bridge class that only contains delegation stubs
                    "io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder"
                )
            }
        }
    }
}

group = "io.github.nesfeder.supabase-offline-kt"
version = "1.0.0"

val githubProps = runCatching {
    Properties().apply {
        load(rootProject.file("github.properties").inputStream())
    }
}.onFailure {
    println("Warning: Could not load github.properties for publishing: ${it.message}. " +
            "Make sure to place the file in the root project directory with 'username' and 'token' properties.")
}.getOrNull()

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
