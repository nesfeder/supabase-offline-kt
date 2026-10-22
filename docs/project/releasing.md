# Releasing

How supabase-offline-kt versions are published, and what you can check about a release you use.
Maintainers release by following
[`RELEASING.md`](https://github.com/nesfeder/supabase-offline-kt/blob/main/RELEASING.md) in the
repository.

## Where releases are published

Every release is one version number, published on every channel from the same commit:

| Channel | What | Where |
| --- | --- | --- |
| Maven Central | `io.github.nesfeder.supabase-offline:supabase-offline-kt` and `…:supabase-offline-kt-testing` | [central.sonatype.com](https://central.sonatype.com/namespace/io.github.nesfeder.supabase-offline) |
| Swift Package Manager | The package with the products `SupabaseOffline` and `SupabaseOfflineTesting`, tagged `X.Y.Z` | [nesfeder/supabase-offline-swift](https://github.com/nesfeder/supabase-offline-swift) |
| GitHub | The tag `X.Y.Z` and a release whose notes are the [changelog](changelog.md)'s section, holding the Swift package's framework | [Releases](https://github.com/nesfeder/supabase-offline-kt/releases) |

Each Maven artifact is a Kotlin Multiplatform library for Android, `iosArm64` and
`iosSimulatorArm64`. Gradle picks the right one from the Gradle module metadata, so a project names
only the artifact itself:

=== "Kotlin Multiplatform"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt:0.2.0")
            }
            commonTest.dependencies {
                implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:0.2.0")
            }
        }
    }
    ```

=== "Android"

    ```kotlin
    dependencies {
        implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt:0.2.0")
        testImplementation("io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:0.2.0")
    }
    ```

=== "Swift Package Manager"

    ```swift
    dependencies: [
        .package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.2.0")),
    ],
    targets: [
        .target(name: "App", dependencies: [.product(name: "SupabaseOffline", package: "supabase-offline-swift")]),
        .testTarget(name: "AppTests", dependencies: [.product(name: "SupabaseOfflineTesting", package: "supabase-offline-swift")]),
    ]
    ```

    In an Xcode project, **File > Add Package Dependencies…** with the same URL. The package needs
    iOS 16 and Xcode 26 or later. It runs on arm64 simulators only: a simulator build that
    includes x86_64 (such as Any iOS Simulator Device) fails to link, so build for a specific
    simulator, or with `ARCHS=arm64`.

Which versions you can take without reading their notes is on the
[Compatibility](compatibility.md) page.

## What is in each artifact

Next to the library itself, every Maven artifact carries:

- a POM with the project's license (MIT), URL and source repository;
- a sources jar;
- a documentation jar holding the HTML API reference of its public packages;
- a PGP signature (`.asc`) of every file, and its MD5 and SHA-1 checksums.

The Swift package's repository holds, at each tag, its `Package.swift`, the Swift sources of its two
products, a README and the license: one commit per release. The framework the products are built
on, `SupabaseOfflineCore.xcframework.zip`, is an asset of this repository's GitHub Release for the
same version; the manifest names it by URL with its SHA-256 checksum, which Xcode checks when it
downloads it. The framework is static, links Apple's SQLite and carries a privacy manifest in each
slice, as the `SupabaseOffline` target does; it is not code-signed.

## How a release is made

A release runs as a GitHub Actions workflow that a maintainer starts by hand on `main`. In order:

1. **Checks, with nothing published.** The version must be a release version with its own
   changelog section and its row in the compatibility page's supabase-kt table, and must not be
   tagged yet. A script rates every change to the public API, the stored data and the pinned
   dependencies since the last release, and refuses a version number too small for them. Then the
   tests and API checks of both modules run, and both are published to a directory inside the build
   and checked file by file: the POMs, the sources and documentation jars, the checksums, and the
   exact dependencies each artifact declares.
2. **The Swift package's framework.** On GitHub's macOS runner, with no secret, the framework is
   built once from the same commit and checked: its header, the APIs its privacy manifest declares,
   the Swift package's tests on a simulator against it, and the check that the package's public API
   names nothing of the framework. It is zipped once, and kept on a draft GitHub Release, which is
   not public.
3. **Maven Central.** The tree the Swift package's repository will hold is staged and checked
   first. Then the signed artifacts are uploaded and released, and the workflow waits until
   Central reports them published. This goes first because it is the step that cannot be undone:
   until it succeeds nothing is public.
4. **The Swift package.** Its tree, with the manifest naming the framework's checksum, is pushed to
   its repository as one commit tagged `X.Y.Z`. A tag is never moved: if it exists, it must already
   hold exactly this tree.
5. **GitHub.** The tag `X.Y.Z` is pushed at the commit that was checked and published, and the draft
   GitHub Release, with the changelog's section, the merged pull requests and the framework, is
   published.
6. **The documentation site** is built from the tag and published, with both API references: the
   Kotlin one under `/api/kotlin/`, which the site's own build generates with Dokka from the commit
   it builds, and the Swift one under `/swift/`, which the macOS job built against the framework it
   tested. Between releases, a maintainer can publish a change to these pages from `main` with a
   second workflow: it builds the pages and the Kotlin reference from `main`, and places the Swift
   API reference the latest release built under `/swift/` again. It takes that reference only from
   a run of this repository's own release workflow, started on `main`, that succeeded and was a
   real release rather than a rehearsal, and checks that before it downloads anything.
7. **The next version.** A branch moves the version on to the next `-SNAPSHOT` and opens a new
   `Unreleased` section in the changelog, and the maintainer opens its pull request.

A version that reached Maven Central is never rebuilt or published again: if a later step fails,
a second workflow finishes only what is missing, from the framework the release built. A defect
found after a release is fixed in a new release; nothing published is withdrawn.

## Checking a release

- **Signatures.** Maven Central verifies every signature against the project's public key on the
  public keyservers before it publishes anything. You can check them again with any OpenPGP tool.
- **The source.** Each release's tag points at the commit its artifacts were built from, and its
  sources jars hold that commit's Kotlin sources. The Swift package's tag holds that commit's Swift
  sources; the GitHub Release names the commit its framework was built from.
- **The framework.** The checksum in the Swift package's `Package.swift` is the SHA-256 of the
  framework asset of the GitHub Release; `shasum -a 256 SupabaseOfflineCore.xcframework.zip` gives
  the same value.
- **The changes.** The GitHub Release and the changelog list what changed, with anything breaking
  under **Breaking**.

## Snapshots

Snapshots of `main` are not published yet. When they are, they will be on Maven Central's snapshot
repository, at the next version with a `-SNAPSHOT` suffix, and kept for about 90 days:

```kotlin
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent { snapshotsOnly() }
    }
}
```

A snapshot can change or disappear at any time; use one only to try a fix before its release.
There are no Swift package snapshots.
