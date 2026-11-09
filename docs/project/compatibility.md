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
  or change behavior you rely on. Every such change is listed under **Breaking** in the
  [changelog](changelog.md), with what to do about it.
- Where it can, the library deprecates before it removes: a deprecated API keeps working for at
  least one minor release, and its deprecation message names the replacement.

Pin the minor you have tested:

=== "Gradle"

    ```kotlin
    implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt:0.4.0")
    ```

    Gradle takes the version you name. If another library in your build asks for a newer
    supabase-offline-kt, Gradle picks the newer one, which before 1.0 can be a breaking minor.

=== "Swift Package Manager"

    ```swift
    .package(url: "https://github.com/nesfeder/supabase-offline-swift", .upToNextMinor(from: "0.4.0"))
    ```

    Use `.upToNextMinor`, not `from:` on its own, which accepts every version up to 1.0.0, and so
    every breaking 0.x minor.

## From 1.0

Semantic versioning in the usual sense:

| Release | What it may contain |
| --- | --- |
| Patch (1.2.3 → 1.2.4) | Fixes only, as before 1.0 |
| Minor (1.2 → 1.3) | New API; deprecations; a new supabase-kt minor; a raised minimum Kotlin, Android or iOS version; a new database schema version with its migration; changes to documented defaults that keep a way to get the old behavior |
| Major (1 → 2) | Anything that breaks source or binary compatibility, or changes documented behavior with no way back |

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
4. **Documented behavior**: the defaults and guarantees on the [Behaviors](../behaviors/index.md)
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

## Platforms

The lowest versions each release supports. Raising one is a minor release.

| Channel | Lowest supported |
| --- | --- |
| Maven, Android | API 23 |
| Maven, iOS (`iosArm64`, `iosSimulatorArm64`) | iOS 15 |
| Swift package | iOS 16, Xcode 26; arm64 simulators only (a simulator build that includes x86_64, such as Any iOS Simulator Device, fails to link: build for a specific simulator, or with `ARCHS=arm64`) |

## supabase-kt

supabase-offline-kt is built and tested against one supabase-kt minor. It is compiled against that
minor's code, and parts of that code are copied into it at compile time, so using it with a
different supabase-kt minor can fail at runtime even when your app compiles.

| supabase-offline-kt | supabase-kt |
| --- | --- |
| 0.4.0 | 3.9.0 |
| 0.3.0 | 3.6.0 |
| 0.2.0 | 3.6.0 |
| 0.1.0 | 3.6.0 |

Each release renames the `Unreleased` row to its version, and the release workflow refuses a
version without its row. Moving to a new supabase-kt minor is a minor release of
supabase-offline-kt, even when nothing else changes, because it changes which supabase-kt your app
must use. A supabase-kt patch can arrive in a patch release.

### Why 3.9

Releases up to 0.3.0 were built on supabase-kt 3.6.0, because 3.7.0 and 3.8.0 carry two Realtime
defects: a heartbeat tick can race `disconnect()` and throw from a coroutine with no exception
handler, which ends the app's process on Android, and `phx_leave` and `access_token` messages are
sent with `join_ref: null`, a Phoenix protocol violation. This library depends on supabase-kt's
Realtime module and declares supabase-kt through its BOM, so its pin sets an app's whole
supabase-kt, Realtime included. supabase-kt 3.9.0 is the first release with both fixes
(supabase-community/supabase-kt#1395 and #1402), together with a batch of Realtime reliability
fixes, so the pin moved to it.

Ktor, kotlinx.coroutines and okio are held at the versions supabase-kt 3.9.0 was released with
(Ktor 3.6.0, kotlinx.coroutines 1.11.0, okio 3.18.2), so adopting the library does not move your
app past them.

**What an app gets with 3.9.** The supabase-kt API that arrived in 3.7 and 3.8 works through the
library: `dryRun()`, `maybeSingle()`, `notIn`, the `isdistinct` filter operator, and `purgeCache` on
a bucket from `supabase.offline.storage`. `notIn` works in reads and in writes sent online; a write
filtered with it is negated (`not.in.(…)`), which the queue cannot record, so it is sent at once and
never queued, and offline throws `MutationNotQueueableException` ([supported
features](../reference/supported-features.md#postgrest)). Storage object names containing `%`, `?`
or `#` are put in the URL percent-encoded, so the library caches them like any other name. Two
top-level filters on one column, such as `filter { gte("age", 1); lte("age", 10) }`, are both sent,
and the server joins them as AND, so the library sends, caches and queues them instead of refusing
them. Two fixes come with it: the first request after supabase-kt refreshes an expired token no
longer goes out with the old token (supabase-community/supabase-kt#1352), and a sign-out the server
refuses now clears the local session (#1359).

**Pattern lists.** supabase-kt quotes a pattern of `likeAll`, `likeAny`, `ilikeAll` or `ilikeAny`
that Postgres would otherwise read as something else (an empty pattern, `NULL`, a pattern edged
with white space, or one holding a brace, a comma, a quote or a backslash), so each pattern is read
as written. An empty list is still sent as no pattern at all:
`delete { filter { likeAll("code", emptyList()) } }` sends `code=like(all).{}`, which matches every
row. The library refuses an empty pattern list, at the top level or in a group, with
`IllegalArgumentException` before anything is sent or queued. `likeAny` and `ilikeAny` over an
empty list would match nothing; they are refused too, so all four behave alike.

**A value with a quote or a backslash in a list or a group.** supabase-kt puts a backslash before
each `"` and `\` of an `in` element, and of a comparison value inside an `or { }` or `and { }`
group, and quotes the value only when it also holds one of `,.:()`; PostgREST reads an unquoted
element or group value with its escapes. `isIn("title", listOf("Say \"hi\""))` sends
`title=in.(Say \"hi\")`, which matches nothing, while `eq("title", "Say \"hi\"")` at the top level
is sent as written and matches. The library refuses such an `in` element or group value with
`IllegalArgumentException` before anything is sent or queued, and moves a write queued before
0.3.0 with such a value to the rejected list when it comes up for sending. Filter on the value
with a single top-level filter.

**An app that asks for another supabase-kt minor itself** gets it: Gradle picks the higher version,
so the library's pin is a floor, not a ceiling. The plugin logs one warning when it is installed,
naming both versions. The library was built and tested against 3.9 only, so another minor can fail
at run time.

Every Monday a scheduled job looks for a newer supabase-kt minor, builds this library against it,
runs its Android host tests, and reports the result in one issue labeled
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
  behavior, the bodies of inline functions, and new deprecation levels or opt-in requirements.
- The release workflow runs the same script over everything since the last release tag, and
  refuses a version number too small for what it finds ([Releasing](releasing.md)).

The details for contributors are in [Contributing](contributing.md#changing-the-public-api).
