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

## Storage

The library's own Storage names are Kotlin's. The bucket's object operations are the ones Kotlin's
`OfflineBucketApi` inherits from supabase-kt's `BucketApi`; in Swift they take supabase-swift's
names and labels instead, so a supabase-swift Storage call compiles with only its receiver changed,
as a supabase-kt call does in Kotlin. Value types keep Kotlin's names, nested in `OfflineBucketApi`
as `OfflineError.ServerError` is, so an app that imports supabase-swift too finds no two types of
one name. supabase-kt's `Url` is Swift's `URL`.

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `SupabaseOfflineClient.storage` | `Offline.storage` | Same |
| `OfflineStorage` | `OfflineStorage` | Same |
| `OfflineStorage.from(_:)` | `OfflineStorage.from(bucketId)` | Same; the operator `get(bucketId)` (`storage["avatars"]`) has no counterpart, as `from(_:)` for tables has none |
| `OfflineBucketApi` | `OfflineBucketApi` | Same; a struct for an interface, since only the library implements it |
| `OfflineBucketApi.bucketId` | `BucketApi.bucketId` | Same. supabase-kt's `supabaseClient`, `resumable` and `setHeader` are not offered: Swift has no supabase-kt client, and resumable uploads are not bridged |
| `upload(_:data:options:)` | `upload(path, data, options)` | Same; `path` unlabelled as supabase-swift has it; `Data` for `ByteArray` |
| `update(_:data:options:)` | `update(path, data, options)` | As `upload` |
| `uploadToSignedURL(_:token:data:options:)` | `uploadToSignedUrl(path, token, data, options)` | Same |
| `createSignedUploadURL(path:upsert:)` | `createSignedUploadUrl(path, upsert)` | Same |
| `remove(paths:)` | `delete(paths)` | supabase-swift's name; Swift returns nothing, as Kotlin does (supabase-swift returns the removed objects) |
| `move(from:to:destinationBucket:)` | `move(from, to, destinationBucket)` | Same; supabase-swift's `options: DestinationOptions` is Kotlin's `destinationBucket` here |
| `copy(from:to:destinationBucket:)` | `copy(from, to, destinationBucket)` | As `move`; returns nothing, as Kotlin does |
| `createSignedURL(path:expiresIn:transform:)` | `createSignedUrl(path, expiresIn) { transform { … } }` | `expiresIn` in whole seconds (`Int`), as supabase-swift has it, for Kotlin's `Duration`; the transform a parameter for the builder lambda; returns `URL` |
| `createSignedURLs(paths:expiresIn:)` | `createSignedUrls(expiresIn, paths)` | Labels in supabase-swift's order; seconds as above |
| `download(path:options:isPublic:)` | `downloadAuthenticated(path, options)`, `downloadPublic(path, options)` | supabase-swift's name and `options:` label; `isPublic`, as `ensureCachedFile` takes it, chooses between Kotlin's two. The options are the image transformation, which Kotlin sets with `transform { }` in its options lambda |
| `list(path:options:)` | `list(prefix, filter)` | supabase-swift's labels |
| `info(path:)` | `info(path)` | Same |
| `exists(path:)` | `exists(path)` | Same |
| `publicURL(path:)` | `publicUrl(path)` | Same (supabase-swift 2 calls it `getPublicURL`). `async` because the client opens in the background; Kotlin's plugin is open when it is called |
| `authenticatedURL(path:)` | `authenticatedUrl(path)` | As `publicURL` |
| `cachedFile(path:)` | `cachedFile(path)` | Same |
| `ensureCachedFile(path:isPublic:)` | `ensureCachedFile(path, isPublic)` | Same |
| `CachedFile` (`path`, `fetchedAt`, `sizeBytes`) | `CachedFile` (`path`, `fetchedAt`, `sizeBytes`) | Same; `Date` for `Instant`, `Int` for `Long` |
| `CachedFile.url` | Android `ensureLocalFile` / `ensureLocalUri`, iOS `ensureLocalPath` | A file URL beside `path`, for APIs that take a URL |
| `OfflineBucketApi.UploadOptions` (`contentType`, `upsert`, `userMetadata`) | supabase-kt's `UploadOptionBuilder` (`contentType`, `upsert`, `userMetadata`) | A value for a builder lambda, so not a "builder"; the fields are the same, `String` for `ContentType` and `[String: OfflineJSON]` for `JsonObject` |
| `OfflineBucketApi.ImageTransformation` (`width`, `height`, `resize`, `quality`, `format`) | supabase-kt's `ImageTransformation` (the same) | Same; a value for a builder lambda |
| `ImageTransformation.Resize` (`cover`, `contain`, `fill`) | `ImageTransformation.Resize` (`COVER`, `CONTAIN`, `FILL`) | Lower-camel cases |
| `OfflineBucketApi.StorageListFilter` (`limit`, `offset`, `search`, `sortBy`) | supabase-kt's `StorageListFilter.Files` (`limit`, `offset`, `search`, `sortBy(column, order)`) | A value for a builder lambda: `sortBy` a field for the call |
| `StorageListFilter.SortBy` (`column`, `order`) | the two arguments of `sortBy(column, order)` | A value holds what Kotlin's call takes |
| `StorageListFilter.SortOrder` (`asc`, `desc`) | supabase-kt's `SortOrder` (`ASC`, `DESC`) | Lower-camel cases; nested because Foundation has a `SortOrder` |
| `OfflineBucketApi.FileUploadResponse` (`id`, `path`, `key`) | supabase-kt's `FileUploadResponse` (the same) | Same |
| `OfflineBucketApi.UploadSignedURL` (`url`, `path`, `token`) | supabase-kt's `UploadSignedUrl` (the same) | Same; `URL` for the URL string |
| `OfflineBucketApi.SignedURL` (`error`, `signedURL`, `path`) | supabase-kt's `SignedUrl` (the same) | Same; `URL` for the URL string |
| `OfflineBucketApi.FileObject` (`name`, `id`, `updatedAt`, `createdAt`, `lastAccessedAt`, `metadata`) | supabase-kt's `FileObject` (the same) | Same; `Date` for `Instant`, `[String: OfflineJSON]` for `JsonObject` |
| `OfflineBucketApi.FileObjectV2` (`name`, `id`, `version`, `bucketId`, `updatedAt`, `createdAt`, `lastAccessedAt`, `metadata`, `size`, `contentType`, `etag`, `lastModified`, `cacheControl`) | supabase-kt's `FileObjectV2` (the same, with `rawContentType`) | `contentType` is the text Kotlin calls `rawContentType`: Swift has no parsed content-type type. Kotlin's `decodeMetadata()` has no counterpart |

## The testing product

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `SupabaseOfflineClient.inMemory(supabaseURL:supabaseKey:config:server:clock:)` | `Offline.Config.inMemory(clock)` with Ktor's `MockEngine` | Same name and `clock`; a factory for a configuration extension |
| `FakeServer`, `FakeServer.Request`, `FakeServer.Response` (`json`, `status`, `noConnection`) | Ktor's `MockEngine` | Swift has no Ktor; the library's Kotlin API has no name of its own here |
| `ManualClock` (`now`, `advance(by:)`, `set(to:)`) | any `kotlin.time.Clock` | Swift's `Clock` protocol measures durations, not wall-clock `Date`s, so a concrete type is needed |

## Decided for the slices to come

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `SnapshotStrategy` (`cacheFirstThenNetwork`, `networkFirstThenCache`, `cacheOnly`) | `FlowReadStrategy` (the same cases) | "Flow" is a Kotlin type: a Swift name with it would be a sign of what is underneath. The cases are the same |
| `QuerySnapshot.result` | `QuerySnapshot.result` | Same; it holds the decoded rows |
