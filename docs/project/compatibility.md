# Compatibility and versioning

This page says what may change between two versions of supabase-offline-kt, and how the version
number tells you. The Kotlin artifacts and the Swift package are released together from this
repository and carry the same version.

## Before 1.0

Versions are `0.MINOR.PATCH`.

- **A patch release (0.1.0 → 0.1.1) only fixes things.** It never changes the public API, the
  format of the data the library stores on the device, the supabase-kt minor it is built against,
  or the lowest Kotlin, Android or iOS version it supports. You can take any patch release without
  reading its notes.
- **A minor release (0.1.x → 0.2.0) may change anything**, including in ways that break your build
  or change behaviour you rely on. Every such change is listed under **Breaking** in the
  [changelog](changelog.md), with what to do about it.
- Where it can, the library deprecates before it removes: a deprecated API keeps working for at
  least one minor release, and its deprecation message names the replacement.

Pin the minor you have tested:

=== "Gradle"

    ```kotlin
    implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt:0.1.0")
    ```

    Gradle takes the version you name. If another library in your build asks for a newer
    supabase-offline-kt, Gradle picks the newer one, which before 1.0 can be a breaking minor.

=== "Swift Package Manager"

    ```swift
    .package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.1.0"))
    ```

    Use `.upToNextMinor`, not `from:`. SwiftPM's `from: "0.1.0"` accepts every version up to 1.0.0,
    and so every breaking 0.x minor.

## From 1.0

Semantic versioning in the usual sense:

| Release | What it may contain |
| --- | --- |
| Patch (1.2.3 → 1.2.4) | Fixes only, as before 1.0 |
| Minor (1.2 → 1.3) | New API; deprecations; a new supabase-kt minor; a raised minimum Kotlin, Android or iOS version; a new database schema version with its migration; changes to documented defaults that keep a way to get the old behaviour |
| Major (1 → 2) | Anything that breaks source or binary compatibility, or changes documented behaviour with no way back |

## What the promise covers

1. **The Kotlin API**: every public declaration in `io.github.nesfeder.supabase.offline` and
   `io.github.nesfeder.supabase.offline.testing`, on Android, `iosArm64` and `iosSimulatorArm64`.
   Both source compatibility (your code still compiles) and binary compatibility (a library
   compiled against the old version still links) count.
2. **The Swift API** of the `SupabaseOffline` and `SupabaseOfflineTesting` products.
3. **The data on the device**:
    - the database file, its location and its schema version;
    - the mutations waiting in the mutation queue, and the list of rejected mutations;
    - the cached files and where they are kept;
    - the cache keys, so an upgrade does not empty a warm cache.

    Upgrading never loses a queued mutation. Going back to an older version across a schema
    version is not supported, and loses the mutations queued since; a release that moves the
    schema version says so at the top of its changelog entry. See
    [On-device data](../reference/on-device-data.md).
4. **Documented behaviour**: the defaults and guarantees on the [Behaviours](../behaviours/index.md)
   pages, such as the order in which queued mutations replay.
5. **The supabase-kt version** the release is built and tested against (below).

## What it does not cover

- Anything in a package whose name contains `internal`, and anything that needs an opt-in at
  error level, such as `@InternalOfflineTestingApi`. These exist for the library's own testing
  artifact and Swift package, and can change in any release.
- API marked experimental, with an opt-in at warning level. It can change in any minor release; the
  changelog still lists the change.
- Implementing the library's interfaces yourself, unless their documentation says you may. A new
  member of an interface does not break code that calls it, only code that implements it. Use
  `supabase-offline-kt-testing` for test doubles.
- Log messages, exception messages, and timings.
- The framework inside the Swift package. It is not a product and is not API, even though an app
  can import it by name.

## supabase-kt

supabase-offline-kt is built and tested against one supabase-kt minor. It is compiled against that
minor's code, and parts of that code are copied into it at compile time, so using it with a
different supabase-kt minor can fail at runtime even when your app compiles.

| supabase-offline-kt | supabase-kt |
| --- | --- |
| Unreleased | 3.8.0 |

Each release adds its row. Moving to a new supabase-kt minor is a minor release of
supabase-offline-kt, even when nothing else changes, because it changes which supabase-kt your app
must use. A supabase-kt patch can arrive in a patch release.

Every Monday a scheduled job looks for a newer supabase-kt minor, builds this library against it,
runs its Android host tests, and reports the result in one issue labelled
[`upstream-pin`](https://github.com/nesfeder/supabase-offline-kt/labels/upstream-pin). A maintainer
adopts that minor deliberately, in a pull request that records it in this table, adds an entry to
the changelog, and closes the issue.

## How this is enforced

- The public API of each Kotlin artifact is recorded in its repository, and a pull request that
  changes a declaration's signature without updating the record fails its checks. A reviewer reads
  every change to the record.
- A script rates each pull request from the records, the database schemas, the tests that pin
  stored formats, and the version catalog: none, patch, additive or breaking. The pull request must
  add a matching entry to the changelog, and a person can always mark a change breaking when the
  script cannot see it.
- Some changes reach no record, so a reviewer checks them by hand: parameter names and their order
  (a renamed parameter breaks a call that names it), changed defaults and documented behaviour, the
  bodies of inline functions, and new deprecation levels or opt-in requirements.
- The release workflow will run the same script over everything since the last release tag, and
  refuse a version number too small for what it finds.

The details for contributors are in [Contributing](contributing.md#changing-the-public-api).
