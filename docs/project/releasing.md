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
| GitHub | The tag `X.Y.Z` and a release whose notes are the [changelog](changelog.md)'s section | [Releases](https://github.com/nesfeder/supabase-offline-kt/releases) |
| Swift Package Manager | Not published yet | — |

Each Maven artifact is a Kotlin Multiplatform library for Android, `iosArm64` and
`iosSimulatorArm64`. Gradle picks the right one from the Gradle module metadata, so a project names
only the artifact itself:

=== "Kotlin Multiplatform"

    ```kotlin
    kotlin {
        sourceSets {
            commonMain.dependencies {
                implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt:0.1.0")
            }
            commonTest.dependencies {
                implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:0.1.0")
            }
        }
    }
    ```

=== "Android"

    ```kotlin
    dependencies {
        implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt:0.1.0")
        testImplementation("io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:0.1.0")
    }
    ```

Which versions you can take without reading their notes is on the
[Compatibility](compatibility.md) page.

## What is in each artifact

Next to the library itself, every artifact carries:

- a POM with the project's license (MIT), URL and source repository;
- a sources jar;
- a documentation jar holding the HTML API reference of its public packages;
- a PGP signature (`.asc`) of every file, and its MD5 and SHA-1 checksums.

## How a release is made

A release runs as a GitHub Actions workflow that a maintainer starts by hand on `main`. In order:

1. **Checks, with nothing published.** The version must be a release version with its own
   changelog section and its row in the compatibility page's supabase-kt table, and must not be
   tagged yet. A script rates every change to the public API, the stored data and the pinned
   dependencies since the last release, and refuses a version number too small for them. Then the
   tests and API checks of both modules run, and both are published to a directory inside the build
   and checked file by file: the POMs, the sources and documentation jars, the checksums, and the
   exact dependencies each artifact declares.
2. **Maven Central.** The signed artifacts are uploaded and released, and the workflow waits until
   Central reports them published. This goes first because it is the step that cannot be undone:
   until it succeeds nothing is public.
3. **GitHub.** The tag `X.Y.Z` is pushed at the commit that was checked and published, and the
   GitHub Release is created from the changelog, with the merged pull requests listed beneath it.
4. **The next version.** A branch moves the version on to the next `-SNAPSHOT` and opens a new
   `Unreleased` section in the changelog, and the maintainer opens its pull request.

A version that reached Maven Central is never rebuilt or published again: if a later step fails,
a second workflow finishes only what is missing. A defect found after a release is fixed in a new
release; nothing published is withdrawn.

## Checking a release

- **Signatures.** Maven Central verifies every signature against the project's public key on the
  public keyservers before it publishes anything. You can check them again with any OpenPGP tool.
- **The source.** Each release's tag points at the commit its artifacts were built from, and its
  sources jars hold that commit's Kotlin sources.
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
