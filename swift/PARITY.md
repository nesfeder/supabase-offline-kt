# Swift and Kotlin names

The Swift package's public names follow the library's Kotlin (Android) API: the same type, case,
member and field names, unless Swift syntax or a platform type forces a difference (an initializer
for a plugin install, an `AsyncSequence` for a `Flow`, lower-camel enum cases, `Date` / `URL` /
`Decodable`, an error code for an exception class). This file lists every public Swift name beside
its Kotlin counterpart, with the reason when they differ. A pull request that adds or renames a
public Swift name updates it, and its reviewer checks the names against it.

This file is for the people who maintain both languages; it is not part of the Swift package the
release publishes.

Kotlin names are the library's (`io.github.nesfeder.supabase.offline`) unless marked supabase-kt.
"Same" means the same name, allowing for Swift's lower-camel case.

## The client and its configuration

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `SupabaseOfflineClient` | `Offline` (the plugin), `supabase.offline` | Swift has no plugin host: a standalone client, as supabase-swift's `SupabaseClient` is |
| `init(supabaseURL:supabaseKey:config:)` | `createSupabaseClient(supabaseUrl, supabaseKey) { install(Offline) { … } }` | An initializer for the plugin install; `URL` for the URL string |
| `close()` | `close()` | Same |
| `SupabaseOfflineClient.Config` | `Offline.Config` | Same |
| `Config.init(defaultSchema:accessToken:dataDirectory:cacheSize:fileCacheSize:autoSync:primaryKeys:logLevel:)` | the `install(Offline) { … }` block | A memberwise initializer with defaults for a configuration block |
| `Config.defaultSchema` | supabase-kt `Postgrest.Config.defaultSchema` | Same. Swift has no PostgREST plugin, so the setting lives here |
| `Config.accessToken` | supabase-kt `SupabaseClientBuilder.accessToken` | Same; an `async` closure for a `suspend` lambda |
| `Config.dataDirectory` | `Offline.Config.dataDirectory` | Same; `URL?` for a path string. The default differs by platform convention: Application Support/SupabaseOffline here |
| `Config.cacheSize` | `Offline.Config.cacheSizeBytes` | The unit moves into the value: `CacheSize.bytes(_:)` |
| `Config.fileCacheSize` | `Offline.Config.fileCacheSizeBytes` | As `cacheSize` |
| `SupabaseOfflineClient.CacheSize` | `Long` with `Offline.CACHE_SIZE_UNLIMITED` | An enum is Swift's form of a number with a sentinel |
| `CacheSize.bytes(_:)` | the `Long` value | As above |
| `CacheSize.unlimited` | `Offline.CACHE_SIZE_UNLIMITED` | A case for the `-1` sentinel |
| `Config.autoSync` | `Offline.Config.autoSync` | Same |
| `Config.primaryKeys` | `Offline.Config.primaryKeys`, `primaryKey(table, vararg columns)` | Same; a dictionary literal for the DSL helper |
| `Config.logLevel` | supabase-kt `logLevel` (each plugin's `MainConfig.logLevel`) | Same. Kotlin's `null` means the client's own level; Swift has no such client, so it defaults to `.warning` |
| `SupabaseOfflineClient.LogLevel` | supabase-kt `LogLevel` | Same |
| `LogLevel.debug`, `.info`, `.warning`, `.error` | `DEBUG`, `INFO`, `WARNING`, `ERROR` | Same, lower camel |
| `LogLevel.off` | `NONE` | A case named `none` is ambiguous with `Optional.none` wherever the level is optional |

## Errors

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `OfflineError` | `OfflineException` and its subclasses, and supabase-kt's exceptions | A Swift `Error` value with codes for an exception class hierarchy (Foundation's `URLError` model) |
| `OfflineError.Code` | the exception's class | An extensible code for an open hierarchy: a new code never breaks an app's `switch` |
| `Code.server` | supabase-kt `PostgrestRestException`, `RestException` | Swift has no upstream exception to pass through; the word is Kotlin's `ServerError`'s |
| `Code.connectivity` | `isNetworkOrConnectivityFailure()`, supabase-kt `HttpRequestException` | Same word |
| `Code.cacheMiss` | `CacheMissException` | Same |
| `Code.networkDisabled` | `NetworkDisabledException` | Same |
| `Code.mutationNotQueueable` | `MutationNotQueueableException` | Same |
| `Code.syncFailed` | `SyncFailedException` | Same |
| `Code.clientClosed` | the closed client's `IllegalStateException` | Kotlin uses a standard type; Swift needs a code |
| `Code.accessToken` | supabase-kt's access-token provider failure | Same |
| `Code.invalidRequest` | `IllegalArgumentException` | Swift only: the package's request description can be refused, which Kotlin's typed builders cannot be |
| `Code.decoding`, `Code.encoding` | kotlinx.serialization's `SerializationException` | `Codable`'s errors; no shared type |
| `Code.configuration` | `createSupabaseClient`'s `IllegalStateException` / `IllegalArgumentException` | The client opens in the background, so its failure needs a code |
| `Code.unexpected` | any other exception | Same word as the cause it stands for |
| `Code.rawValue`, `init(rawValue:)`, `description`, `~=` | — | `RawRepresentable`, `CustomStringConvertible`, and `catch OfflineError.cacheMiss` matching |
| `OfflineError.server` … `.unexpected` (static aliases of the codes) | — | `URLError`'s catch idiom |
| `OfflineError.code` | the exception's class | Foundation's error `code`. Kotlin's `SyncFailedException.code` (the server's code) is Swift's `serverError?.code` |
| `OfflineError.message` | `Throwable.message` | Same |
| `OfflineError.serverError` | `RejectedMutation.serverError` | Same |
| `OfflineError.underlyingError` | `Throwable.cause` | Foundation's name for a wrapped error; Swift's `Error` has no `cause` |
| `OfflineError.isNetworkOrConnectivityFailure` | `Throwable.isNetworkOrConnectivityFailure()` | Same; a property for an extension function |
| `OfflineError.httpStatus` | `Throwable.httpStatusOrNull()` | An optional for `…OrNull` |
| `OfflineError.errorDescription` | — | `LocalizedError` |
| `OfflineError.ServerError` | `ServerError` | Same; nested because Swift has no packages and supabase-swift has a `ServerError` |
| `ServerError.status`, `.code`, `.message`, `.details`, `.hint` | the same five | Same |

## JSON

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `OfflineJSON` (`null`, `bool`, `int`, `double`, `string`, `array`, `object`) | kotlinx.serialization's `JsonElement`, `JsonObject` | No shared JSON type; supabase-swift's `AnyJSON` would clash |

## The testing product

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `SupabaseOfflineClient.inMemory(supabaseURL:supabaseKey:config:server:clock:)` | `Offline.Config.inMemory(clock)` with Ktor's `MockEngine` | Same name and `clock`; a factory for a configuration extension |
| `FakeServer`, `FakeServer.Request`, `FakeServer.Response` (`json`, `status`, `noConnection`) | Ktor's `MockEngine` | Swift has no Ktor; the library's Kotlin API has no name of its own here |
| `ManualClock` (`now`, `advance(by:)`, `set(to:)`) | any `kotlin.time.Clock` | Swift's `Clock` protocol measures durations, not wall-clock `Date`s, so a concrete type is needed |

## Decided for the slices to come

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `SequenceReadStrategy` (`cacheFirstThenNetwork`, `networkFirstThenCache`, `cacheOnly`) | `FlowReadStrategy` (the same cases) | A Kotlin `Flow` is a Swift sequence, in names too ("Flow" would be a sign of what is underneath); the cases are the same |
| `selectAsSequence` | `selectAsFlow` | As above |
| `QuerySnapshot.result` | `QuerySnapshot.result` | Same; it holds the decoded rows |
