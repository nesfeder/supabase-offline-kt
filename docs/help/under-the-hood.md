# Under the hood

The Swift package is a Swift API over a shared offline engine: the same engine the Android library
runs. Its API, its documentation, its errors and its log lines are Swift's own and name nothing of
the engine. This page is the one place that says what the engine is, and what that means for an
app.

## What the package holds

- **`SupabaseOffline` and `SupabaseOfflineTesting`**, the two products: Swift source. They hold the
  API, its errors and sequences, and the mapping of each call onto the engine.
- **`SupabaseOfflineCore`**, a static binary framework the products link: the engine. It holds the
  cache, the mutation queue, sync and the file cache, and the HTTP client under them. It is built
  with Kotlin Multiplatform from the same code as the Kotlin library, so it carries a Kotlin/Native
  runtime. It is not a product and not API: an app can `import SupabaseOfflineCore` by name, and
  its header shows the runtime's types (`KotlinBase` and others), but nothing in it is supported
  and any of it may change in any release.
- **The system's SQLite.** The engine uses `libsqlite3.dylib` and the system's C++ runtime, which
  the package links; it bundles no SQLite of its own.

The framework ships unsigned in 0.1.0.

## App size

The engine and its runtime are most of what the package adds to an app. Measured on 2026-10-04
with a **debug** build of the framework: a minimal arm64 app built in Release grew from about 70 KB
to 13.4 MB once stripped (4.3 MB compressed with gzip). The published package carries a release
build of the framework, whose figures replace these with the first release.

## Crash reports and the debugger

The Swift API hides the engine while you use it, not while you debug it:

- A crash or a sample taken inside the engine shows its frames with `kfun:` symbols, and the
  threads its runtime starts.
- The debugger steps through the Swift package's own code, but cannot evaluate expressions inside
  the engine; its objects show as `SOC…` types.
- The package's log, the unified log's subsystem `SupabaseOffline` at the level
  `Config.logLevel` sets, says what the engine did, and is where to look first.

## An app that already embeds a Kotlin framework

An app that already ships a Kotlin Multiplatform framework of its own would carry a second Kotlin
runtime with this package, and the two runtimes may conflict. Such an app uses the Kotlin library
instead: add `io.github.nesfeder.supabase-offline:supabase-offline-kt` to its shared module, as
[Project setup](../getting-started/setup.md) describes, and call it from Kotlin. The Kotlin library
on iOS bundles its own SQLite.
