# Configuration & Logging

--8<-- "docs/.snippets/legacy-notice.md"

The SDK is configured at construction time via `SupabaseOfflineConfig`. Both knobs that exist
today — cache sizing and the optional logger — are wired in
[SupabaseOfflineConfig.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/SupabaseOfflineConfig.kt).

## Contents

- [`SupabaseOfflineConfig`](#supabaseofflineconfig)
    - [`cacheSizeBytes`](#cachesizebytes)
    - [`offlineStoragePath`](#offlinestoragepath)
    - [`logger`](#logger)
    - [`minLogLevel`](#minloglevel)
- [Logging deep-dive](#logging-deep-dive)
    - [Setup (Android)](#setup-android)
    - [Setup (iOS)](#setup-ios)
    - [Log levels](#log-levels)
    - [Production safety](#production-safety)
    - [Disabling logging](#disabling-logging)

## `SupabaseOfflineConfig`

```kotlin title="shared/src/commonMain/kotlin/io/github/nesfeder/supabase/SupabaseOfflineConfig.kt" linenums="14"
data class SupabaseOfflineConfig(
    /**
     * Maximum size in bytes for the offline database file.
     *
     * If set to [CACHE_SIZE_UNLIMITED], no size-based eviction will run.
     *
     * Values lower than [MIN_CACHE_SIZE_BYTES] are coerced to
     * [MIN_CACHE_SIZE_BYTES] to avoid setting a limit below the typical
     * minimum on-disk size of a Room database file.
     */
    val cacheSizeBytes: Long = DEFAULT_CACHE_SIZE_BYTES,

    /**
     * Optional explicit path for where offline data (database and cached
     * files) should be stored.
     *
     * If left as `null`, a platform-specific sensible default is used by
     * the corresponding `createSupabaseOfflineClient` factory. If both the
     * factory parameter and this property are provided, this property wins.
     */
    val offlineStoragePath: Path? = null,

    /**
     * Optional logger for receiving SDK operational events.
     *
     * When `null` (the default), no logging occurs and there is zero overhead.
     * Provide an [OfflineLogger] implementation to bridge SDK logs into your
     * preferred framework (e.g. Timber on Android, os.Logger on iOS).
     */
    val logger: OfflineLogger? = null,

    /**
     * Minimum log level that will be emitted to [logger].
     *
     * Defaults to [OfflineLogLevel.INFO], which is production-safe: only
     * operational events (cache fallbacks, mutation queuing, sync outcomes)
     * are surfaced, with **no** JSON payloads or user data.
     *
     * Set to [OfflineLogLevel.DEBUG] during development to include
     * request/response body previews and detailed operation tracing.
     */
    val minLogLevel: OfflineLogLevel = OfflineLogLevel.INFO,
) {
```

### `cacheSizeBytes`

- **Default:** `100 * 1024 * 1024` (100 MB).
- **Unlimited:** set to `SupabaseOfflineConfig.CACHE_SIZE_UNLIMITED` (`-1`).
- **Minimum coerced floor:** `SupabaseOfflineConfig.MIN_CACHE_SIZE_BYTES` (4 KiB) — values below
  are silently raised, since the empty Room file already occupies at least one page.

The limit applies to the on-disk size of the offline database (only `object_cache`, since the
mutation queue and storage metadata are durable user state). See
[architecture.md](../project/internals.md#cache-eviction) for how eviction is paced.

### `offlineStoragePath`

An optional `okio.Path?`. When provided, this becomes the base directory for both the database
file and cached storage files (`<path>/storage/<bucketId>/<objectPath>`).

When `null` (the default), each platform picks a sensible private location:

- **Android:** `Context.getDatabasePath("supabase_offline.db")` for the DB; the
  `PlatformBaseDirectory(Context)` for storage files.
- **iOS:** `Documents/supabase_offline.db` for the DB; the Application Support directory for
  storage files.

### `logger`

An `OfflineLogger` (`fun interface`) that the SDK calls with `(level, message)`. The default is
`null` — when null, no log message is ever constructed, so there is zero runtime cost.

The interface is in
[OfflineLogger.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/shared/src/commonMain/kotlin/io/github/nesfeder/supabase/OfflineLogger.kt):

```kotlin title="shared/src/commonMain/kotlin/io/github/nesfeder/supabase/OfflineLogger.kt" linenums="30"
fun interface OfflineLogger {
    fun log(level: OfflineLogLevel, message: String)
}
```

The internal `SdkLogger` gates on `minLogLevel` and uses lazy message construction so filtered-out
levels skip the string-building work entirely.

### `minLogLevel`

`OfflineLogLevel.INFO` (default) is production-safe. Set to `DEBUG` only during development —
see [Production safety](#production-safety).

## Logging deep-dive

### Setup (Android)

Pass a logger that fans out to your preferred framework:

```kotlin
val offline = createSupabaseOfflineClient(
    context = this,
    supabase = supabase,
    config = SupabaseOfflineConfig(
        logger = OfflineLogger { level, message ->
            when (level) {
                OfflineLogLevel.DEBUG   -> Log.d("Echo", message)
                OfflineLogLevel.INFO    -> Log.i("Echo", message)
                OfflineLogLevel.WARNING -> Log.w("Echo", message)
                OfflineLogLevel.ERROR   -> Log.e("Echo", message)
            }
        },
        minLogLevel = OfflineLogLevel.DEBUG // Only for development!
    )
)
```

The Android demo wires this in
[MainActivity.kt](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/composeApp/src/androidMain/kotlin/io/github/nesfeder/supabase/demo/MainActivity.kt).

### Setup (iOS)

The Swift wrapper takes a closure of `(OfflineLogLevel, String) -> Void` directly. Bridge it to
`os.Logger`:

```swift
import os

private let sdkLogger = Logger(subsystem: "io.github.nesfeder.supabase.demo", category: "SDK")

let offlineClient = SupabaseFactory.offlineClient(
    supabaseUrl: url,
    supabaseAnonKey: anon,
    logger: { level, message in
        switch level {
        case .debug:   sdkLogger.debug("\(message)")
        case .info:    sdkLogger.info("\(message)")
        case .warning: sdkLogger.warning("\(message)")
        case .error:   sdkLogger.error("\(message)")
        default:       sdkLogger.log("\(message)")
        }
    },
    minLogLevel: .debug // Only for development!
)
```

The iOS demo wires this in
[ContentView.swift](https://github.com/nesfeder/supabase-offline-kt/blob/9b014a795453590d7aecd704b01ca36ade8f67cb/iosApp/iosApp/ContentView.swift).

### Log levels

| Level | What is logged | Contains payload data? |
|-------|----------------|------------------------|
| `DEBUG` | Strategy entry points, full JSON request/response bodies, per-mutation sync progress | **Yes** (full JSON) |
| `INFO` | Cache hits, mutations queued offline, sync started/completed | No |
| `WARNING` | Network failures with cache fallback, permanent mutation failures (dropped), sync paused | No |
| `ERROR` | Network failures with no cache available | No |

### Production safety

Keep `minLogLevel` at `OfflineLogLevel.INFO` (the default) in production. INFO and above never
emit JSON payloads or user data — only operational events with table names and operation types.

`DEBUG` includes full JSON request/response bodies, which are useful when reproducing a bug but
should not be emitted in shipped builds.

### Disabling logging

Leave `logger` as `null` (the default). No `OfflineLogger` is ever called and the `SdkLogger`
short-circuits before constructing any message string.
