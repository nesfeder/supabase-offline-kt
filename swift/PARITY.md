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
`OfflineBucketApi` inherits from supabase-kt's `BucketApi`; in Swift they take supabase-swift's names
and labels instead, as Kotlin's take supabase-kt's, so that a supabase-swift Storage call reads the
same. Value types keep supabase-kt's names, nested in `OfflineBucketApi` as `OfflineError.ServerError`
is, so an app that imports supabase-swift too finds no two types of one name. supabase-kt's `Url` is
Swift's `URL`.

**What compiles unchanged from supabase-swift 2** (2.55.2): a call with only its receiver changed
compiles when its option values are written `.init(…)` and it reads no result field this package
names differently. These do not compile:

- an option type written out by supabase-swift's name: `FileOptions(…)`, `TransformOptions(…)`,
  `SearchOptions(…)`, `DestinationOptions(…)`, `CreateSignedUploadURLOptions(…)`;
- upload options supabase-swift has and this package does not: `cacheControl:`, `duplex:`,
  `headers:`, and `metadata:` (here `userMetadata:`); `uploadToSignedURL(…, options: nil)`;
- a transformation's `resize: "cover"` (an enum here) and `format: .webp` (a `String` here); a sort's
  `order: "asc"` or `.ascending` (here `.asc` / `.desc`) and a sort without a column;
- the upload overloads that take a `fileURL:`, and `download`'s `query:` and `cacheNonce:`;
  `createSignedURL`'s and `createSignedURLs`' `download:` and `cacheNonce:`;
- `move` / `copy` across buckets through `options:` (here `destinationBucket:`), and
  `createSignedUploadURL(path:options: .init(upsert:))` (here `upsert:`);
- `getPublicURL(path:…)`, which is `publicURL(path:)` here, `async`, with no transformation and no
  `download:`;
- code that reads a result this package shapes differently: `FileUploadResponse.fullPath` (here
  `key`) and its `id` as a non-optional `String`; `uploadToSignedURL`'s `SignedURLUploadResponse`
  (`path`, `fullPath`; here a `FileUploadResponse`); the key `copy` returns and the objects `remove`
  returns (here both return nothing); `SignedUploadURL.signedURL` (here `url`); `createSignedURLs`'
  `[SignedURLResult]` enum (here `[SignedURL]` structs); `FileObject.id` as a `UUID` and its
  `bucketId`, `owner` and `buckets`; `FileObjectV2`'s optional `createdAt`, `size` and
  `contentType` (non-optional here, as Kotlin has them) and its non-optional `id`.

One thing compiles and does not work: `catch let error as StorageError` in an app that also imports
supabase-swift never matches, because Storage's refusals arrive as `OfflineError` (`server`).

| Swift | Kotlin | Side / reason |
| --- | --- | --- |
| `SupabaseOfflineClient.storage` | `Offline.storage` | Kotlin's name; supabase-swift's is the same |
| `OfflineStorage` | `OfflineStorage` | Kotlin's name |
| `OfflineStorage.from(_:)` | `OfflineStorage.from(bucketId)` | Kotlin's name, supabase-swift's unlabelled argument; the operator `get(bucketId)` (`storage["avatars"]`) has no counterpart, as `from(_:)` for tables has none |
| `OfflineBucketApi` | `OfflineBucketApi` | Kotlin's name; a struct for an interface, since only the library implements it |
| `OfflineBucketApi.bucketId` | `BucketApi.bucketId` | Kotlin's name. supabase-kt's `supabaseClient`, `resumable` and `setHeader` are not offered: Swift has no supabase-kt client, and resumable uploads are not offered |
| `upload(_:data:options:)` | `upload(path, data, options)` | supabase-swift's labels (the path unlabelled); `Data` for `ByteArray`. Empty data is refused with `invalidRequest` and nothing is sent, as Kotlin's `upload(path, ByteArray)` refuses it: a zero-byte object (a folder placeholder) cannot be uploaded, where supabase-swift sends it |
| `update(_:data:options:)` | `update(path, data, options)` | As `upload`, empty data included |
| `uploadToSignedURL(_:token:data:options:)` | `uploadToSignedUrl(path, token, data, options)` | supabase-swift's labels; empty data as `upload`. `options` is not optional, as Kotlin's has a default |
| `createSignedUploadURL(path:upsert:)` | `createSignedUploadUrl(path, upsert)` | supabase-swift's name; Kotlin's `upsert` parameter, because supabase-swift's `options: CreateSignedUploadURLOptions` would be a one-field type with no Kotlin counterpart |
| `remove(paths:)` | `delete(paths)` | supabase-swift's name; returns nothing, as Kotlin's does (supabase-swift returns the removed objects) |
| `move(from:to:destinationBucket:)` | `move(from, to, destinationBucket)` | supabase-swift's labels for the two paths; Kotlin's `destinationBucket`, because supabase-swift's `options: DestinationOptions` would be a one-field type with no Kotlin counterpart |
| `copy(from:to:destinationBucket:)` | `copy(from, to, destinationBucket)` | As `move`; returns nothing, as Kotlin's does (supabase-swift returns the new key) |
| `createSignedURL(path:expiresIn:transform:)` | `createSignedUrl(path, expiresIn) { transform { … } }` | supabase-swift's labels, and its `Int` seconds for Kotlin's `Duration`, so its call compiles; the transformation a parameter for the builder lambda; returns `URL` |
| `createSignedURLs(paths:expiresIn:)` | `createSignedUrls(expiresIn, paths)` | supabase-swift's labels and order; seconds as above. A path Storage cannot sign fails the whole call with `decoding` (its message names the path's position), as Kotlin's call fails: supabase-kt's `SignedUrl` requires a URL |
| `download(path:options:isPublic:)` | `downloadAuthenticated(path, options)`, `downloadPublic(path, options)` | supabase-swift's name and `options:` label; Kotlin's `isPublic`, as `ensureCachedFile` takes it, chooses between Kotlin's two. The options are the image transformation, which Kotlin sets with `transform { }` in its options lambda |
| `list(path:options:)` | `list(prefix, filter)` | supabase-swift's labels |
| `info(path:)` | `info(path)` | The same name in both |
| `exists(path:)` | `exists(path)` | The same name in both |
| `publicURL(path:)` | `publicUrl(path)` | Kotlin's name (supabase-swift 2 says `getPublicURL`). `async` because the URL comes from the opened client, which opens in the background; for the same reason it throws `clientClosed` after `close()`, where Kotlin's still answers. `%`, `?` and `#` in the name are encoded, as supabase-swift encodes them, where Kotlin's (on supabase-kt 3.6) puts the name in as written; the same holds for `authenticatedURL`, `createSignedURL`, `createSignedUploadURL` and `uploadToSignedURL` |
| `authenticatedURL(path:)` | `authenticatedUrl(path)` | Kotlin's name; `async` and `clientClosed` after `close()`, as `publicURL` |
| `cachedFile(path:)` | `cachedFile(path)` | Kotlin's name |
| `ensureCachedFile(path:isPublic:)` | `ensureCachedFile(path, isPublic)` | Kotlin's name. A name holding `%`, `?` or `#` is refused (`invalidRequest`) before the client is asked, in the package's own words, where Kotlin's throws `IllegalArgumentException` |
| `CachedFile` (`path`, `fetchedAt`, `sizeBytes`) | `CachedFile` (`path`, `fetchedAt`, `sizeBytes`) | Kotlin's names; `Date` for `Instant`, `Int` for `Long` |
| `CachedFile.url` | Android `ensureLocalFile` / `ensureLocalUri`, iOS `ensureLocalPath` | Swift's addition: a file URL beside `path`, for APIs that take a URL |
| `OfflineBucketApi.UploadOptions` (`contentType`, `upsert`, `userMetadata`) | supabase-kt's `UploadOptionBuilder` (`contentType`, `upsert`, `userMetadata`) | Kotlin's fields; a value for a builder lambda, so not a "builder"; `String` for `ContentType`, `[String: OfflineJSON]` for `JsonObject` |
| `OfflineBucketApi.ImageTransformation` (`width`, `height`, `resize`, `quality`, `format`) | supabase-kt's `ImageTransformation` (the same) | Kotlin's names; a value for a builder lambda |
| `ImageTransformation.Resize` (`cover`, `contain`, `fill`) | `ImageTransformation.Resize` (`COVER`, `CONTAIN`, `FILL`) | Kotlin's, lower-camel cases |
| `OfflineBucketApi.StorageListFilter` (`init(limit:offset:sortBy:search:)`) | supabase-kt's `StorageListFilter.Files` (`limit`, `offset`, `search`, `sortBy(column, order)`) | Kotlin's names; a value for a builder lambda, with `sortBy` a field for the call; the initializer takes supabase-swift's argument order, which Kotlin's builder does not fix |
| `StorageListFilter.SortBy` (`column`, `order`) | the two arguments of `sortBy(column, order)` | Kotlin's names: a value holds what Kotlin's call takes |
| `StorageListFilter.SortOrder` (`asc`, `desc`) | supabase-kt's `SortOrder` (`ASC`, `DESC`) | Kotlin's, lower-camel cases; nested because Foundation has a `SortOrder` |
| `OfflineBucketApi.FileUploadResponse` (`id`, `path`, `key`) | supabase-kt's `FileUploadResponse` (the same) | Kotlin's names and types (supabase-swift's has `fullPath` and a non-optional `id`) |
| `OfflineBucketApi.UploadSignedURL` (`url`, `path`, `token`) | supabase-kt's `UploadSignedUrl` (the same) | Kotlin's names (supabase-swift's `SignedUploadURL` has `signedURL`); `URL` for the URL string |
| `OfflineBucketApi.SignedURL` (`error`, `signedURL`, `path`) | supabase-kt's `SignedUrl` (the same) | Kotlin's names and shape (supabase-swift returns a `SignedURLResult` enum); `URL` for the URL string |
| `OfflineBucketApi.FileObject` (`name`, `id`, `updatedAt`, `createdAt`, `lastAccessedAt`, `metadata`) | supabase-kt's `FileObject` (the same) | Kotlin's names and types; `Date` for `Instant`, `[String: OfflineJSON]` for `JsonObject` |
| `OfflineBucketApi.FileObjectV2` (`name`, `id`, `version`, `bucketId`, `updatedAt`, `createdAt`, `lastAccessedAt`, `metadata`, `size`, `contentType`, `etag`, `lastModified`, `cacheControl`) | supabase-kt's `FileObjectV2` (the same, with `rawContentType`) | Kotlin's names and nullability; `contentType` is the text Kotlin calls `rawContentType`, as Swift has no parsed content-type type. Kotlin's `decodeMetadata()` has no counterpart |
| A Storage refusal: `OfflineError` `server`, `serverError.status` / `.code` / `.message` | supabase-kt's `RestException` (`statusCode`, `error`, `description`) | Storage's own error code (`not_found`, `Duplicate`) is `code` and its message is `message`; supabase-kt keeps them only for a `400` and prints them into one string otherwise. supabase-swift's `StorageError` is never thrown |

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
