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

## The testing artifact

Use `supabase-offline-kt-testing` at exactly the version of the library it sits beside. Its
factories and its in-memory client call into the library outside the API records, so they work only
with the library of their own version. Its published metadata pins that version (a `strictly`
constraint), which works in one direction only. Beside a newer library, an older testing artifact
fails dependency resolution, rather than a test failing with a missing method. A newer testing
artifact raises the library in the test classpath to its own version without a word, so the tests
run on a library the app does not ship. Keep both on one version, from one version-catalog entry.

When a value type gains a field, its factory in the testing artifact gains an overload that takes
it; the existing factory is not given a new parameter with a default. A new defaulted parameter
breaks every compiled caller, and from 0.1.0 on the overload is the compatible way to grow: the
earlier form stays, hidden from new code.

## What it does not cover

- Anything in a package whose name contains `internal`, and anything marked `@OfflineInternal`.
  These exist for the library's own testing artifact and Swift package, and can change in any
  release.
- API marked `@OfflineExperimental`. Using it needs an opt-in at error level, and it is left out of
  the API records the compatibility checks compare, so it can change in any minor release without
  a changelog line.
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
| Unreleased | 3.6.0 |

Each release renames the `Unreleased` row to its version, and the release workflow refuses a
version without its row. Moving to a new supabase-kt minor is a minor release of
supabase-offline-kt, even when nothing else changes, because it changes which supabase-kt your app
must use. A supabase-kt patch can arrive in a patch release.

### Why 3.6, not 3.8

supabase-kt 3.7.0 and 3.8.0 carry two Realtime defects, as upstream describes them:

- A heartbeat tick can race `disconnect()` and throw from a coroutine with no exception handler,
  which ends the app's process on Android.
- `phx_leave` and `access_token` messages are sent with `join_ref: null`, a Phoenix protocol
  violation.

Both fixes are merged upstream and not yet released. This library never uses Realtime, but it
declares supabase-kt through its BOM, so its pin sets an app's whole supabase-kt, Realtime
included. The pin moves to the first supabase-kt release that contains both fixes. Ktor and
kotlinx.coroutines are held at the versions supabase-kt 3.6.0 was released with (3.4.3 and
1.10.2), so adopting the library does not move your app past them.

**What an app on supabase-kt 3.6 does not have.**

- supabase-kt 3.8 API: `dryRun()`, `maybeSingle()`, the `isdistinct` filter operator
  (`FilterOperator.ISDISTINCT`), and `purgeCache` / `purgeBucketCache`.
- On 3.6, write a dry run by adding the header yourself: `headers.append("Prefer", "tx=rollback")`
  in the request block, which supabase-kt marks experimental
  (`@OptIn(SupabaseExperimental::class)`). The library reads every `Prefer` value, so such a write
  is never queued. A hosted Supabase project commits a dry run all the same, unless it allows the
  rollback: see [the write path](architecture.md#the-write-path).
- Storage object names containing `%`, `?` or `#`. supabase-kt 3.6.0 puts the name in the URL as
  written: a `%` fails the request, and a `?` or `#` can fetch a different object (`x?y.png`
  fetches `x`). The library downloads such a name as supabase-kt does but never caches it or
  serves it from the cache, and `ensureCachedFile` refuses it. Keep such characters out of object
  names until the pin moves.

**What an app on supabase-kt 3.6 inherits from it.**

- The first request after supabase-kt refreshes an expired token goes out with the old token and
  fails once with `401` (fixed in 3.7.0). A replayed write that meets it stays queued and is sent
  again on a later pass; an app's own call sees the `401`.
- When the server refuses a sign-out, supabase-kt 3.6 keeps the local session (fixed in 3.7.0).

**Two top-level filters on one column.** Every supabase-kt release to date, 3.6 and 3.8 included,
sends only the first value of each top-level query parameter: `filter { gte("age", 1); lte("age",
10) }` sends `age=gte.1` and nothing else, where postgrest-js and supabase-swift send both. Rather
than read or delete more rows than the call names, the library refuses such a read, update or
delete with `IllegalArgumentException` before anything is sent or queued. Write the two conditions
as one group, which sends both:

```kotlin
filter {
    and {
        gte("age", 1)
        lte("age", 10)
    }
}
```

The refusal sees a parameter that holds two values. It cannot see the calls that replace one: a
second top-level `or { }` group replaces the first, and a text search replaces a filter written
before it on the same column. Combine those into one group too. A test pins what supabase-kt sends,
so the day it sends every value this rule is revisited before the pin moves.

**An app that asks for supabase-kt 3.7 or 3.8 itself** gets it: Gradle picks the higher version,
so the library's pin is a floor, not a ceiling.

- The plugin logs one warning when it is installed, naming both versions.
- On 3.8, `purgeCache` called on a bucket from `supabase.offline.storage` fails, on Android as an
  `AbstractMethodError` when it is called, and on iOS as an `IrLinkageError`. The library was
  built against a `BucketApi` without that member. Call `purgeCache` on supabase-kt's own bucket
  instead (`supabase.storage.from(…)`).

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
- Parameter names and their order, which the ABI records do not show, have a record of their own:
  a renamed or reordered parameter breaks a call that names it, so it changes that record and is
  rated breaking. That record also shows which parameters have a default, so giving an existing
  parameter one changes it too and is rated breaking: a conservative rating, since the change
  breaks no source.
- Some changes reach no record, so a reviewer checks them by hand: changed defaults and documented
  behaviour, the bodies of inline functions, and new deprecation levels or opt-in requirements.
- The release workflow runs the same script over everything since the last release tag, and
  refuses a version number too small for what it finds ([Releasing](releasing.md)).

The details for contributors are in [Contributing](contributing.md#changing-the-public-api).
