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
| `Config.init(defaultSchema:accessToken:dataDirectory:cacheSize:fileCacheSize:networkAvailability:autoSync:primaryKeys:logLevel:)`, the same with `realtimeTables:` before `logLevel:`, and the same with `realtimeTables:`, `versionColumns:` and `conflictResolver:` before `logLevel:` | the `install(Offline) { … }` block | A memberwise initializer with defaults for a configuration block; the forms with `realtimeTables:` and with `versionColumns:` are overloads, since a new defaulted parameter would change the earlier forms. In the last, `versionColumns:` has no default, so a call that names none of the new settings picks an earlier form |
| `Config.defaultSchema` | supabase-kt `Postgrest.Config.defaultSchema` | Same. Swift has no PostgREST plugin, so the setting lives here |
| `Config.accessToken` | supabase-kt `SupabaseClientBuilder.accessToken` | Same; an `async` closure for a `suspend` lambda |
| `Config.dataDirectory` | `Offline.Config.dataDirectory` | Same; `URL?` for a path string. The default differs by platform convention: Application Support/SupabaseOffline here |
| `Config.cacheSize` | `Offline.Config.cacheSizeBytes` | The unit moves into the value: `CacheSize.bytes(_:)` |
| `Config.fileCacheSize` | `Offline.Config.fileCacheSizeBytes` | As `cacheSize` |
| `SupabaseOfflineClient.CacheSize` | `Long` with `Offline.CACHE_SIZE_UNLIMITED` | An enum is Swift's form of a number with a sentinel |
| `CacheSize.bytes(_:)` | the `Long` value | As above |
| `CacheSize.unlimited` | `Offline.CACHE_SIZE_UNLIMITED` | A case for the `-1` sentinel |
| `Config.networkAvailability` | `Offline.Config.networkAvailability` | Same name; a closure that makes an `AsyncStream<Bool>`, called once when each client opens, for a `Flow<Boolean>?`: a Swift stream has one consumer and is finished with its client, where a cold `Flow` is collected afresh by each client. A Swift stream that ends counts as reachable and the status reads `.online`, where a Kotlin flow that ends stops being watched and the status reads `UNKNOWN` (which also counts as reachable): the framework cannot yet be told a signal has ended. `nil` is the system's path monitor in both |
| `Config.autoSync` | `Offline.Config.autoSync` | Same |
| `Config.primaryKeys` | `Offline.Config.primaryKeys`, `primaryKey(table, vararg columns)` | Same; a dictionary literal for the DSL helper |
| `Config.realtimeTables` | `Offline.Config.realtimeTables`, `realtime { table(table, schema, filter) }` | Same name; an array of `RealtimeTable` for the DSL block. Swift names no Realtime plugin: the core installs supabase-kt's when the array is not empty |
| `RealtimeTable(_:schema:filter:)` (`table`, `schema`, `filter`) | `RealtimeTable` (`table`, `schema`, `filter`), built by `table(...)` | Same fields; a public initializer for the block's call, the table name unlabeled as `from(_:)`'s is |
| `RealtimeFilter(column:operator:value:)`, `RealtimeFilter(column:in:)` (`column`, `operator`) | `RealtimeFilter(column, operator, value)` | `operator` is Swift's `FilterOperator`; the value is an `OfflineFilterValue`, or an array for `in`, and is not read back. An operator Realtime does not evaluate, or an empty array for `in`, fails the open with `configuration`, where Kotlin's initializer throws `IllegalArgumentException` |
| `Config.versionColumns` | `Offline.Config.versionColumns`, `versionColumn(table, column)` | Same; a dictionary literal for the DSL helper. A blank column, or one of the table's key columns, fails the open with `configuration`, where Kotlin's `createConfig` throws `IllegalArgumentException`; the package checks first so the message names the Swift setting, and the core refuses the same behind it |
| `Config.conflictResolver` | `Offline.Config.conflictResolver` (a `ConflictResolver`) | Same name; an `async throws` closure for the `fun interface` with its `suspend fun resolve`. It runs in a detached task, off the caller's actor; a throw, a conflict or columns the package cannot cross, and no answer within 30 seconds read as `hold`, as anything Kotlin's resolver throws but a cancellation does. At the 30-second bound the Swift task is not canceled, as Kotlin's resolver is: it runs on and its late answer is dropped, as a late access token's is |
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
| `OfflineStorage.from(_:)` | `OfflineStorage.from(bucketId)` | Kotlin's name, supabase-swift's unlabeled argument; the operator `get(bucketId)` (`storage["avatars"]`) has no counterpart, as `from(_:)` for tables has none |
| `OfflineStorage.clearFileCache()` | `OfflineStorage.clearFileCache()` | Kotlin's name |
| `OfflineBucketApi` | `OfflineBucketApi` | Kotlin's name; a struct for an interface, since only the library implements it |
| `OfflineBucketApi.bucketId` | `BucketApi.bucketId` | Kotlin's name. supabase-kt's `supabaseClient`, `resumable` and `setHeader` are not offered: Swift has no supabase-kt client, and resumable uploads are not offered |
| `upload(_:data:options:)` | `upload(path, data, options)` | supabase-swift's labels (the path unlabeled); `Data` for `ByteArray`. Empty data is refused with `invalidRequest` and nothing is sent, as Kotlin's `upload(path, ByteArray)` refuses it: a zero-byte object (a folder placeholder) cannot be uploaded, where supabase-swift sends it |
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
| `cachedFile(path:transform:)` | `cachedFile(path) { … }` (`cachedFile(path, transform)`) | Kotlin's names; an `ImageTransformation` value for the transform lambda, as `createSignedURL`'s `transform:` is. An overload, not a defaulted parameter |
| `evictCachedFile(path:)` | `evictCachedFile(path)` | Kotlin's name |
| `evictCachedFile(path:transform:)` | `evictCachedFile(path) { … }` (`evictCachedFile(path, transform)`) | Kotlin's names; the transform as for `cachedFile(path:transform:)` |
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
| `SupabaseOfflineClient.inMemory(supabaseURL:supabaseKey:config:server:clock:)` and `inMemory(supabaseURL:supabaseKey:config:server:clock:realtime:)` | `Offline.Config.inMemory(clock)` and `inMemory(clock, realtime)` with Ktor's `MockEngine` | Same name, `clock` and `realtime`, an overload each; a factory for a configuration extension |
| `FakeRealtime` (`subscribedTables`, `droppedReportCount`, `reportInsert(table:record:schema:)`, `reportUpdate(table:record:schema:)`, `reportDelete(table:oldRecord:schema:)`, `failChannel(message:)`, `dropChannel()`) | `FakeRealtime` (the same, `subscribedTables` a `StateFlow`, `failChannel(cause)`) | Same; a `Set<String>` read now for the `StateFlow`, `[String: OfflineJSON]` for `JsonObject`, and a message for the cause's `Throwable`. A record that is not a JSON object throws `invalidRequest` |
| `FakeServer`, `FakeServer.Request`, `FakeServer.Response` (`json`, `status`, `noConnection`) | Ktor's `MockEngine` | Swift has no Ktor; the library's Kotlin API has no name of its own here |
| `ManualClock` (`now`, `advance(by:)`, `set(to:)`) | any `kotlin.time.Clock` | Swift's `Clock` protocol measures durations, not wall-clock `Date`s, so a concrete type is needed |

## PostgREST reads and writes

On the drop-in surfaces (the filter and transform methods, and `rpc`'s `params:`) each platform
keeps its upstream client's names and labels: supabase-swift's here, supabase-kt's on Android, so a
chain written for either upstream compiles against its own platform unchanged. The offline names
(strategies, outcomes, snapshots, the queue) are the library's own and match across the two.

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `SupabaseOfflineClient.from(_:)` | `Offline.from(table)` | Same |
| `SupabaseOfflineClient.from(schema:table:)` | `Offline.from(schema, table)` | Labels, as Swift names its arguments; the subscript `offline["todos"]` has no counterpart |
| `OfflinePostgrestQueryBuilder` | `OfflinePostgrestQueryBuilder` | Same |
| `OfflinePostgrestQueryBuilder.table`, `.schema` | `.table`, `.schema` | `schema` is `nil` for the client's default, which the Swift builder does not hold |
| `select(_:head:count:readStrategy:)` | `select(columns, readStrategy, request)` | Filters and transforms chain after `select`, as in supabase-swift, in place of the trailing request lambda; `head` and `count` move from the lambda to parameters, as supabase-swift has them |
| `selectAsSequence(_:as:readStrategy:)` | `selectAsFlow(columns, readStrategy, request)` | An `AsyncSequence` where Kotlin has a `Flow`; `as:` names the `Decodable` type the rows decode to |
| `insert(_:defaultToNull:mutationStrategy:)` | `insert(value / values / body, mutationStrategy, request)` | One `Encodable` parameter (a row or an array) and an `OfflineJSON` overload, in place of three overloads; `defaultToNull` moves from the lambda to a parameter |
| `upsert(_:onConflict:ignoreDuplicates:defaultToNull:mutationStrategy:)` | `upsert(value / values / body, mutationStrategy, request)` | As `insert`; `onConflict` and `ignoreDuplicates` move from the lambda to parameters |
| `update(_:mutationStrategy:)` (an `Encodable` or `[String: OfflineJSON]`) | `update(value / body / update { }, mutationStrategy, request)` | No `PostgrestUpdate` DSL; `[String: OfflineJSON]` sends an explicit `null` |
| `delete(mutationStrategy:)` | `delete(mutationStrategy, request)` | Same |
| `SupabaseOfflineClient.rpc(_:params:count:schema:)` | `Offline.rpc(function, parameters, request)` | `params:` as supabase-swift labels it; `count` and `schema` move from the lambda to parameters; arguments go by POST only |
| `SupabaseOfflineClient.rpc(_:head:get:count:schema:)` | `Offline.rpc(function, request)` | `head` and `get` choose the method that the lambda's `method` sets |
| `OfflineSelectRequestBuilder` | the `request` lambda of `select` (supabase-kt's `SelectRequestBuilder`) | A builder value chained before `execute()`, as supabase-swift does |
| `OfflineSelectRequestBuilder.execute()`, `execute(_:)` | the result of `select` | Swift sends a chain at `execute`, as supabase-swift does; `execute(_:)` decodes the rows, which Kotlin does with `decodeList` |
| `OfflineMutationRequestBuilder` | the `request` lambda of `insert`, `upsert`, `update`, `delete` | As `OfflineSelectRequestBuilder` |
| `OfflineMutationRequestBuilder.select(_:)`, `.single()` | `select(columns)` and `single()` in the lambda | Same |
| `OfflineMutationRequestBuilder.execute()`, `execute(_:)` | the result of the write verbs | As above |
| `OfflineRPCRequestBuilder`, `.execute()`, `.execute(_:)` | the `request` lambda of `rpc` (supabase-kt's `RpcRequestBuilder`), and its result | As above |
| `QuerySnapshotSequence`, `QuerySnapshotSequence.Iterator` | `Flow<QuerySnapshot>` | An `AsyncSequence`; it also takes the filters and transforms the lambda would |
| `OfflinePostgrestFilterBuilder` | supabase-kt's `PostgrestFilterBuilder` (the `filter { }` lambda) | A protocol the three request builders and the live read share, since Swift chains where Kotlin nests a lambda |
| `OfflinePostgrestRequestBuilder` | supabase-kt's `PostgrestRequestBuilder` | As above, for `order`, `limit`, `range`, `single`, `csv`, `geojson`, `stripNulls` |
| `OfflinePostgrestFilterBuilder.applying(_:)`, `OfflineQueryChange` | — | What each filter and transform method makes; Kotlin needs none, as its lambdas call the builder directly |
| `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `like`, `ilike`, `contains`, `overlaps`, `rangeLt`, `rangeGt`, `rangeGte`, `rangeLte`, `textSearch`, `filter`, `or` | the same names | Same names; the labels (`value:`, `pattern:`, `range:`) are supabase-swift's |
| `is(_:value:)`, `in(_:values:)` | `exact`, `isIn` | supabase-swift's names (written with backticks, since both are Swift keywords); supabase-kt chose other names |
| `notIn(_:values:)` | none: `filterNot(column, FilterOperator.IN, values)` | supabase-swift's name; supabase-kt has no `notIn` |
| `match(_:pattern:)`, `imatch(_:pattern:)` | `regexMatch`, `regexIMatch` | supabase-swift's names |
| `match(_:)` (a dictionary) | `match(map)` | Same |
| `likeAllOf`, `likeAnyOf`, `iLikeAllOf`, `iLikeAnyOf` | `likeAll`, `likeAny`, `ilikeAll`, `ilikeAny` | supabase-swift's names |
| `containedBy` | `contained` | supabase-swift's name |
| `rangeAdjacent` | `adjacent` | supabase-swift's name |
| `not(_:operator:value:)` | `filterNot(column, operator, value)` | supabase-swift's name |
| `fts(_:query:config:)` | `textSearch(column, query, TextSearchType.NONE, config)` | supabase-swift's shorthand |
| `order(_:ascending:nullsFirst:referencedTable:)` | `order(column, Order, nullsFirst, referencedTable)` | `ascending: Bool` as supabase-swift has it, in place of the `Order` enum |
| `limit(_:referencedTable:)`, `range(from:to:referencedTable:)`, `single()`, `csv()`, `geojson()`, `stripNulls()` | the same names | Same |
| `OfflineFilterValue`, `offlineFilterText` | — (a Kotlin filter value is `Any`, written with `toString()`) | Swift needs a protocol to accept several value types; values are written as the Kotlin API writes them (lower-case UUIDs, UTC dates, `1.0E-7`); the `offline` prefix keeps the property from colliding with a name an app declares on the same standard types |
| A value holding `"` or `\` in an `in` list or inside an `or` / `and` group (explicit, or one the chain builds) fails as `invalidRequest`; so does a range filter inside a group, or a configured text search there whose query has `,`, `)` or a leading `"` | `isIn`, `or { }` and `and { }` refuse it with `IllegalArgumentException` | supabase-kt escapes `"` and `\` there but quotes only a value that also holds one of `,.:()`, and PostgREST reads an unquoted value literally, so the engine would send a different filter than written. Both platforms refuse it until the engine can send it as written; the package checks first so its message names the Swift call's column in its own words, and the core refuses the same shapes behind it, except a text search query given through `filter` and a string given to `cs`, `cd` or `ov` in a group, which only the package refuses. A single top-level condition sends such a value as written on both platforms |
| A pattern list (`likeAllOf`, `likeAnyOf`, `iLikeAllOf`, `iLikeAnyOf`, or `like(all)` / `like(any)` / `ilike(all)` / `ilike(any)` text) that is empty, or holds a pattern that is empty, `NULL` in any case, edged with white space or holding a brace (and none of `,.:()`), fails as `invalidRequest` | `likeAll`, `likeAny`, `ilikeAll`, `ilikeAny` refuse it with `IllegalArgumentException` | supabase-kt quotes a pattern only when it holds one of `,.:()`, so Postgres reads such a pattern as another (an empty one as no pattern at all, and `LIKE ALL` over no pattern matches every row). supabase-swift quotes these; both platforms refuse them until the engine can send them as written. The package checks first so its message names the Swift call's column in its own words; the core refuses the same shapes behind it |
| Two conditions on one top-level column, or two `or` groups on one table, are sent in one `and` group (the embedded table's own, `authors.and`, for `authors.born`) | `filter { gte(…); lt(…) }` sends only the first condition on a column (supabase-kt's `mapToFirstValue`); `and { }` holds several | supabase-swift sends `a=gte.1&a=lt.5`, which supabase-kt cannot; PostgREST reads the group the same way |
| `FilterOperator` | supabase-kt's `FilterOperator` | Lower-camel cases, as Swift names them |
| `OfflineTextSearchType` (`plain`, `phrase`, `websearch`) | supabase-kt's `TextSearchType` (`PLAINTO`, `PHRASETO`, `WEBSEARCH`, `NONE`) | The prefix because supabase-swift has a public `TextSearchType`, which an app importing both would find ambiguous; the cases are supabase-swift's names, and `nil` stands for `NONE` |
| `Count` | supabase-kt's `Count` | Lower-camel cases |
| `ReadStrategy` (`networkOnly`, `networkFirst(maxAge:)`, `cacheFirst(maxAge:)`, `cacheOnly(maxAge:)`, and `networkFirst`, `cacheFirst`, `cacheOnly` without an age) | `ReadStrategy` (`NetworkOnly`, `NetworkFirst(maxAge)`, `CacheFirst(maxAge)`, `CacheOnly(maxAge)`) | `Duration` for `maxAge`; Swift needs the age-less static members where Kotlin has default arguments in a constructor |
| `SequenceReadStrategy` (`cacheFirstThenNetwork`, `networkFirstThenCache`, `cacheOnly`) | `FlowReadStrategy` (the same cases) | Swift has no `Flow`: as `selectAsFlow` is `selectAsSequence`, `FlowReadStrategy` is `SequenceReadStrategy`, keeping the `ReadStrategy` stem |
| `MutationStrategy` (`queueIfOffline`, `onlineOnly`) | `MutationStrategy` (`QueueIfOffline`, `OnlineOnly`) | Same |
| `OfflineResponse` (`value`, `data`, `count`, `isFromCache`, `fetchedAt`, `hasPendingMutations`) | supabase-kt's `PostgrestResult` (`data`, `countOrNull()`) with the extension properties `isFromCache`, `fetchedAt`, `hasPendingMutations` | Swift cannot return an upstream type, so the provenance is stored on the response; `value` is the decoded body, as supabase-swift's `execute().value` |
| `QuerySnapshot` (`result`, `isFromCache`, `fetchedAt`, `hasPendingMutations`, `refresh`) | `QuerySnapshot` (the same fields) | `result` is the rows decoded as the type asked for, where Kotlin holds the undecoded `PostgrestResult` |
| `RefreshState` (`idle`, `refreshing`, `paused`, `stalled(cause:)`) | `RefreshState` (`Idle`, `Refreshing`, `Paused`, `Stalled(cause)`) | `cause` is an `OfflineError` |
| `MutationOutcome` (`applied(result:)`, `queued(pendingMutation:)`, `isQueued`, `result`) | `MutationOutcome` (`Applied(result)`, `Queued(pendingMutation)`, `isQueued`, `resultOrNull()`) | `result` is an optional property, as Swift writes `resultOrNull()` |
| `PendingMutation` (`id`, `table`, `operation`, `createdAt`, `rowKeys`, `body`, `baseVersion`) | `PendingMutation` (the same fields) | `Date` for `createdAt`; `OfflineJSON` for the row keys and the body |
| `MutationOperation` (`insert`, `upsert`, `update`, `delete`) | `MutationOperation` (`INSERT`, `UPSERT`, `UPDATE`, `DELETE`) | Lower-camel cases |

## The mutation queue, sync, status and lifecycle

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `pendingMutations(table:)` | `Offline.pendingMutations(table)` | Same; it returns a `PendingMutationSequence` where Kotlin returns a `Flow<List<PendingMutation>>` |
| `PendingMutationSequence`, `PendingMutationSequence.Iterator` | `Flow<List<PendingMutation>>` | An `AsyncSequence` of `[PendingMutation]` for the `Flow` |
| `PendingMutationSequence.first()` | `Flow.first()` | Same: the list as it is now, read once (without subscribing) |
| `pendingMutationCount(table:)` | `Offline.pendingMutationCount(table)` | Same; `async throws` for `suspend` |
| `pendingMutationCountSequence(table:)` | `Offline.pendingMutationCountFlow(table)` | `Sequence` for `Flow` in the name, as `selectAsSequence` is to `selectAsFlow` |
| `PendingMutationCountSequence`, `PendingMutationCountSequence.Iterator` | `Flow<Int>` | An `AsyncSequence` of `Int` for the `Flow` |
| `discardPendingMutation(id:)` | `Offline.discardPendingMutation(id)` | Same; the label names the argument, as Swift writes it |
| `rejectedMutations()` | `Offline.rejectedMutations()` | Same; it returns a `RejectedMutationSequence` where Kotlin returns a `Flow<List<RejectedMutation>>` |
| `RejectedMutationSequence`, `RejectedMutationSequence.Iterator` | `Flow<List<RejectedMutation>>` | An `AsyncSequence` of `[RejectedMutation]` for the `Flow` |
| `RejectedMutationSequence.first()` | `Flow.first()` | Same, as for the pending list |
| `dismissRejectedMutation(id:)`, `retryRejectedMutation(id:)` | `Offline.dismissRejectedMutation(id)`, `Offline.retryRejectedMutation(id)` | Same. A retry Kotlin refuses with `IllegalArgumentException` throws `OfflineError.invalidRequest` |
| `RejectedMutation` (`id`, `table`, `operation`, `createdAt`, `rowKeys`, `body`, `serverError`, `message`, `rejectedAt`, `isRetryable`) | `RejectedMutation` (the same fields) | Same; `Date` for the times, `OfflineJSON` for the row keys and the body, `OfflineError.ServerError` for `ServerError` |
| `conflictedMutations()` | `Offline.conflictedMutations()` | Same; it returns a `ConflictedMutationSequence` where Kotlin returns a `Flow<List<ConflictedMutation>>` |
| `ConflictedMutationSequence`, `ConflictedMutationSequence.Iterator` | `Flow<List<ConflictedMutation>>` | An `AsyncSequence` of `[ConflictedMutation]` for the `Flow`, named as `RejectedMutationSequence` is |
| `ConflictedMutationSequence.first()` | `Flow.first()` | Same, as for the rejected list |
| `resolveConflictedMutation(id:resolution:)`, `dismissConflictedMutation(id:)` | `Offline.resolveConflictedMutation(id, resolution)`, `Offline.dismissConflictedMutation(id)` | Same. A resolution Kotlin refuses with `IllegalArgumentException` (`hold`, an `apply` with no column or on an entry it does not fit, a `keepLocal` of a row that was gone) throws `OfflineError.invalidRequest` |
| `MutationConflict` (`mutationId`, `table`, `operation`, `rowKey`, `base`, `local`, `server`, `changedOnServer`, `conflictingColumns`) | `MutationConflict` (the same fields) | Same; `[String: OfflineJSON]` for `JsonObject`, `Set<String>` for the column sets. `rowKey` holds the key values as text taken from the write's filter (`["id": "1"]`), as Kotlin's does |
| `ConflictResolution` (`keepLocal`, `keepServer`, `apply(columns:)`, `hold`) | `ConflictResolution` (`KeepLocal`, `KeepServer`, `Apply(columns)` built by `ConflictResolution.apply(columns)`, `Hold`) | Lower-camel cases; a case with an associated value for `Apply` and its factory. An empty `columns` is refused where it is used (a resolver's answer holds the write; `resolveConflictedMutation` throws `invalidRequest`), where Kotlin's factory throws `IllegalArgumentException`. An input type, so a later case is additive |
| `ConflictedMutation` (`id`, `table`, `operation`, `createdAt`, `rowKeys`, `body`, `base`, `server`, `serverVersion`, `changedOnServer`, `conflictingColumns`, `message`, `conflictedAt`) | `ConflictedMutation` (the same fields) | Same; `Date` for the times, `OfflineJSON` for the rows, `Set<String>` for the column sets. `rowKeys` holds text taken from the filter, as `PendingMutation.rowKeys` does |
| `syncPendingMutations(table:)` | `Offline.syncPendingMutations(table)` | Same; `@discardableResult`, as the report is often not read |
| `SyncReport` (`syncedCount`, `rejected`, `remainingCount`, `alreadyOnServerCount`, `heldBehindOtherTablesCount`, `conflictsResolvedCount`, `conflictsKeptServerCount`, `conflicted`) | `SyncReport` (the same fields) | Same |
| `syncStatus` | `Offline.syncStatus` | Same name; a `SyncStatusSequence` for the `StateFlow<SyncStatus>` |
| `SyncStatusSequence`, `SyncStatusSequence.Iterator` | `StateFlow<SyncStatus>` | An `AsyncSequence` of `SyncStatus` for the `StateFlow`; iterating one begun after `close()` throws `clientClosed`, where Kotlin's flow keeps its last value |
| `SyncStatusSequence.value` | `StateFlow.value` | Same: the status now, without waiting. Before the client has opened its database (it opens in the background) it is the status of a client that has done nothing yet |
| `SyncStatus` (`network`, `pendingMutationCount`, `rejectedMutationCount`, `isSyncing`, `lastSyncedAt`, `lastSyncError`, `conflictedMutationCount`) | `SyncStatus` (the same fields) | Same; `Date?` for `lastSyncedAt`, and `OfflineError?` (code `syncFailed`, with the server's status and code in `serverError`) for the `SyncFailedException` in `lastSyncError` |
| `SyncStatus ==` | the data class's `equals` | Two sync errors are equal when their code, message, server error and network flag are, as `OfflineError` is not `Equatable` |
| `NetworkStatus` (`unknown`, `online`, `offline`, `disabled`) | `NetworkStatus` (`UNKNOWN`, `ONLINE`, `OFFLINE`, `DISABLED`) | Lower-camel cases |
| `disableNetwork()`, `enableNetwork()` | `Offline.disableNetwork()`, `Offline.enableNetwork()` | Same; `async throws` for `suspend` |
| `realtimeStatus` | `Offline.realtimeStatus` | Same name; a `RealtimeStatusSequence` for the `StateFlow<RealtimeStatus>` |
| `RealtimeStatusSequence`, `RealtimeStatusSequence.Iterator` | `StateFlow<RealtimeStatus>` | An `AsyncSequence` of `RealtimeStatus` for the `StateFlow`, as `SyncStatusSequence` is |
| `RealtimeStatusSequence.value` | `StateFlow.value` | Same: the status now, without waiting; `off` before the client has opened and after `close()` |
| `RealtimeStatus` (`off`, `paused`, `subscribing`, `subscribed`, `stalled(cause:)`) | `RealtimeStatus` (`Off`, `Paused`, `Subscribing`, `Subscribed`, `Stalled(cause)`) | Lower-camel cases; `cause` is an `OfflineError`, as `RefreshState.stalled`'s is |
| `disableRealtime()`, `enableRealtime()` | `Offline.disableRealtime()`, `Offline.enableRealtime()` | Same; `async throws` for `suspend`. Without an Auth plugin the core cannot see a sign-in, so a Swift app brackets one with the switch, which Kotlin's follower does by itself |
| `clearLocalData()` | `Offline.clearLocalData()` | Same |
| `applyServerRecord(table:record:primaryKeys:schema:)` (an `Encodable`, or `[String: OfflineJSON]`) | `Offline.applyServerRecord(table, record, primaryKeys, schema)` (a `JsonObject`, or the reified `T`) | Same parameters in the same order; `Encodable` for the reified form, a dictionary of `OfflineJSON` for `JsonObject`. A record that is not a JSON object throws `invalidRequest` where Kotlin throws `IllegalArgumentException` |
| `applyServerDelete(table:oldRecord:primaryKeys:schema:)` (an `Encodable`, or `[String: OfflineJSON]`) | `Offline.applyServerDelete(table, oldRecord, primaryKeys, schema)` | As `applyServerRecord`; Kotlin takes a `JsonObject` only |

## Test values

The testing product's factories, for an app's own test doubles: free functions with every field as
a parameter and defaults for the rest, as the Kotlin testing artifact's are.

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `pendingMutation(id:table:operation:createdAt:rowKeys:body:)` | `pendingMutation(id, table, operation, createdAt, rowKeys, body)` | Same |
| `pendingMutation(id:table:operation:createdAt:rowKeys:body:baseVersion:)` | `pendingMutation(…, baseVersion)` | Same; an overload beside the first, `baseVersion` required, as Kotlin's is |
| `rejectedMutation(id:table:operation:createdAt:rowKeys:body:serverError:message:rejectedAt:isRetryable:)` | `rejectedMutation(…)`, the same parameters | Same |
| `serverError(status:code:message:details:hint:)` | `serverError(status, code, message, details, hint)` | Same; it builds an `OfflineError.ServerError` |
| `syncReport(syncedCount:rejected:remainingCount:alreadyOnServerCount:heldBehindOtherTablesCount:)` | `syncReport(…)`, the same parameters | Same |
| `syncReport(syncedCount:rejected:remainingCount:alreadyOnServerCount:heldBehindOtherTablesCount:conflictsResolvedCount:conflictsKeptServerCount:conflicted:)` | `syncReport(…, conflictsResolvedCount, conflictsKeptServerCount, conflicted)` | Same; an overload beside the first, the three new parameters defaulted, as Kotlin's are. A call naming none of them still compiles, and builds the same report either way |
| `syncStatus(network:pendingMutationCount:rejectedMutationCount:isSyncing:lastSyncedAt:lastSyncError:)` | `syncStatus(…)`, the same parameters | Same; `lastSyncError` is an `OfflineError` |
| `syncStatus(network:pendingMutationCount:rejectedMutationCount:isSyncing:lastSyncedAt:lastSyncError:conflictedMutationCount:)` | `syncStatus(…, conflictedMutationCount)` | Same; an overload beside the first, `conflictedMutationCount` required, as Kotlin's is |
| `mutationConflict(mutationId:table:operation:rowKey:base:local:server:changedOnServer:conflictingColumns:)` | `mutationConflict(…)`, the same parameters | Same |
| `conflictedMutation(id:table:operation:createdAt:rowKeys:body:base:server:serverVersion:changedOnServer:conflictingColumns:message:conflictedAt:)` | `conflictedMutation(…)`, the same parameters | Same |
| `querySnapshot(result:isFromCache:fetchedAt:hasPendingMutations:refresh:)` | `querySnapshot(result, isFromCache, fetchedAt, hasPendingMutations, refresh)` | Same; generic over the decoded rows |
| `offlineResponse(value:data:count:isFromCache:fetchedAt:hasPendingMutations:)` | — | Swift only: Kotlin's `PostgrestResult` is supabase-kt's, with a public constructor |
| — | `appliedOutcome(result)`, `queuedOutcome(pendingMutation)` | Not needed: `MutationOutcome`'s cases are public in Swift, so `.applied(result:)` and `.queued(pendingMutation:)` build them |
| — | `stalledRefresh(cause)` | Not needed: `RefreshState.stalled(cause:)` is a public case |
| `cacheMissError(message:)`, `mutationNotQueueableError(message:)`, `networkDisabledError(message:)` | `cacheMissException(message)`, `mutationNotQueueableException(cause, message)`, `networkDisabledException(message)` | `Error` for `Exception`: each builds an `OfflineError` with that code. Swift's has no `cause`; it is a network failure, as Kotlin's is through its cause |
| `syncFailedError(message:httpStatus:code:isConnectivityFailure:)` | `syncFailedException(message, httpStatus, code, isConnectivityFailure)` | `Error` for `Exception`; the status and code go in `serverError` |
| `offlineError(code:message:serverError:isNetworkOrConnectivityFailure:)` | — (supabase-kt's own exceptions have public constructors) | Swift only: any other code, such as `server` or `connectivity` |
| `cachedFile(path:fetchedAt:sizeBytes:)` | `cachedFile(path, fetchedAt, sizeBytes)` | Same; `Date` for `Instant`, `Int` for `Long` |
