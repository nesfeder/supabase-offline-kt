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

## PostgREST reads and writes

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
| `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `like`, `ilike`, `notIn`, `contains`, `overlaps`, `rangeLt`, `rangeGt`, `rangeGte`, `rangeLte`, `textSearch`, `filter`, `or` | the same names | Filter methods keep supabase-swift's names and labels (`value:`, `pattern:`, `range:`), so a supabase-swift chain compiles unchanged |
| `is(_:value:)`, `in(_:values:)` | `exact`, `isIn` | `is` and `in` are Kotlin keywords, not Swift's |
| `match(_:pattern:)`, `imatch(_:pattern:)` | `regexMatch`, `regexIMatch` | supabase-swift's names |
| `match(_:)` (a dictionary) | `match(map)` | Same |
| `likeAllOf`, `likeAnyOf`, `iLikeAllOf`, `iLikeAnyOf` | `likeAll`, `likeAny`, `ilikeAll`, `ilikeAny` | supabase-swift's names |
| `containedBy` | `contained` | supabase-swift's name |
| `rangeAdjacent` | `adjacent` | supabase-swift's name |
| `not(_:operator:value:)` | `filterNot(column, operator, value)` | supabase-swift's name |
| `fts(_:query:config:)` | `textSearch(column, query, TextSearchType.NONE, config)` | supabase-swift's shorthand |
| `order(_:ascending:nullsFirst:referencedTable:)` | `order(column, Order, nullsFirst, referencedTable)` | `ascending: Bool` as supabase-swift has it, in place of the `Order` enum |
| `limit(_:referencedTable:)`, `range(from:to:referencedTable:)`, `single()`, `csv()`, `geojson()`, `stripNulls()` | the same names | Same |
| `OfflineFilterValue`, `filterText` | — (a Kotlin filter value is `Any`, written with `toString()`) | Swift needs a protocol to accept several value types; values are written as the Kotlin API writes them (lower-case UUIDs, UTC dates, `1.0E-7`) |
| `FilterOperator` | supabase-kt's `FilterOperator` | Lower-camel cases, as Swift names them |
| `OfflineTextSearchType` | supabase-kt's `TextSearchType` | supabase-swift has a public `TextSearchType`, which an app importing both would find ambiguous; `nil` stands for `NONE` |
| `Count` | supabase-kt's `Count` | Lower-camel cases |
| `ReadStrategy` (`networkOnly`, `networkFirst(maxAge:)`, `cacheFirst(maxAge:)`, `cacheOnly(maxAge:)`, and `networkFirst`, `cacheFirst`, `cacheOnly` without an age) | `ReadStrategy` (`NetworkOnly`, `NetworkFirst(maxAge)`, `CacheFirst(maxAge)`, `CacheOnly(maxAge)`) | `Duration` for `maxAge`; Swift needs the age-less static members where Kotlin has default arguments in a constructor |
| `SnapshotStrategy` (`cacheFirstThenNetwork`, `networkFirstThenCache`, `cacheOnly`) | `FlowReadStrategy` | Swift has no `Flow`: the strategy is named for the snapshots it yields |
| `MutationStrategy` (`queueIfOffline`, `onlineOnly`) | `MutationStrategy` (`QueueIfOffline`, `OnlineOnly`) | Same |
| `OfflineResponse` (`value`, `data`, `count`, `isFromCache`, `fetchedAt`, `hasPendingMutations`) | supabase-kt's `PostgrestResult` (`data`, `countOrNull()`) with the extension properties `isFromCache`, `fetchedAt`, `hasPendingMutations` | Swift cannot return an upstream type, so the provenance is stored on the response; `value` is the decoded body, as supabase-swift's `execute().value` |
| `QuerySnapshot` (`result`, `isFromCache`, `fetchedAt`, `hasPendingMutations`, `refresh`) | `QuerySnapshot` (the same fields) | `result` is the rows decoded as the type asked for, where Kotlin holds the undecoded `PostgrestResult` |
| `RefreshState` (`idle`, `refreshing`, `paused`, `stalled(cause:)`) | `RefreshState` (`Idle`, `Refreshing`, `Paused`, `Stalled(cause)`) | `cause` is an `OfflineError` |
| `MutationOutcome` (`applied(result:)`, `queued(pendingMutation:)`, `isQueued`, `result`) | `MutationOutcome` (`Applied(result)`, `Queued(pendingMutation)`, `isQueued`, `resultOrNull()`) | `result` is an optional property, as Swift writes `resultOrNull()` |
| `PendingMutation` (`id`, `table`, `operation`, `createdAt`, `rowKeys`, `body`) | `PendingMutation` (the same fields) | `Date` for `createdAt`; `OfflineJSON` for the row keys and the body |
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
| `syncPendingMutations(table:)` | `Offline.syncPendingMutations(table)` | Same; `@discardableResult`, as the report is often not read |
| `SyncReport` (`syncedCount`, `rejected`, `remainingCount`, `alreadyOnServerCount`) | `SyncReport` (the same fields) | Same |
| `syncStatus` | `Offline.syncStatus` | Same name; a `SyncStatusSequence` for the `StateFlow<SyncStatus>` |
| `SyncStatusSequence`, `SyncStatusSequence.Iterator` | `StateFlow<SyncStatus>` | An `AsyncSequence` of `SyncStatus` for the `StateFlow`; iterating one begun after `close()` throws `clientClosed`, where Kotlin's flow keeps its last value |
| `SyncStatusSequence.value` | `StateFlow.value` | Same: the status now, without waiting. Before the client has opened its database (it opens in the background) it is the status of a client that has done nothing yet |
| `SyncStatus` (`network`, `pendingMutationCount`, `rejectedMutationCount`, `isSyncing`, `lastSyncedAt`, `lastSyncError`) | `SyncStatus` (the same fields) | Same; `Date?` for `lastSyncedAt`, and `OfflineError?` (code `syncFailed`, with the server's status and code in `serverError`) for the `SyncFailedException` in `lastSyncError` |
| `SyncStatus ==` | the data class's `equals` | Two sync errors are equal when their code, message, server error and network flag are, as `OfflineError` is not `Equatable` |
| `NetworkStatus` (`unknown`, `online`, `offline`, `disabled`) | `NetworkStatus` (`UNKNOWN`, `ONLINE`, `OFFLINE`, `DISABLED`) | Lower-camel cases |
| `disableNetwork()`, `enableNetwork()` | `Offline.disableNetwork()`, `Offline.enableNetwork()` | Same; `async throws` for `suspend` |
| `clearLocalData()` | `Offline.clearLocalData()` | Same |
| `applyServerRecord(table:record:primaryKeys:schema:)` (an `Encodable`, or `[String: OfflineJSON]`) | `Offline.applyServerRecord(table, record, primaryKeys, schema)` (a `JsonObject`, or the reified `T`) | Same parameters in the same order; `Encodable` for the reified form, a dictionary of `OfflineJSON` for `JsonObject`. A record that is not a JSON object throws `invalidRequest` where Kotlin throws `IllegalArgumentException` |
| `applyServerDelete(table:oldRecord:primaryKeys:schema:)` (an `Encodable`, or `[String: OfflineJSON]`) | `Offline.applyServerDelete(table, oldRecord, primaryKeys, schema)` | As `applyServerRecord`; Kotlin takes a `JsonObject` only |

## Test values

The testing product's factories, for an app's own test doubles: free functions with every field as
a parameter and defaults for the rest, as the Kotlin testing artifact's are.

| Swift | Kotlin | Same / reason |
| --- | --- | --- |
| `pendingMutation(id:table:operation:createdAt:rowKeys:body:)` | `pendingMutation(id, table, operation, createdAt, rowKeys, body)` | Same |
| `rejectedMutation(id:table:operation:createdAt:rowKeys:body:serverError:message:rejectedAt:isRetryable:)` | `rejectedMutation(…)`, the same parameters | Same |
| `serverError(status:code:message:details:hint:)` | `serverError(status, code, message, details, hint)` | Same; it builds an `OfflineError.ServerError` |
| `syncReport(syncedCount:rejected:remainingCount:alreadyOnServerCount:)` | `syncReport(…)`, the same parameters | Same |
| `syncStatus(network:pendingMutationCount:rejectedMutationCount:isSyncing:lastSyncedAt:lastSyncError:)` | `syncStatus(…)`, the same parameters | Same; `lastSyncError` is an `OfflineError` |
| `querySnapshot(result:isFromCache:fetchedAt:hasPendingMutations:refresh:)` | `querySnapshot(result, isFromCache, fetchedAt, hasPendingMutations, refresh)` | Same; generic over the decoded rows |
| `offlineResponse(value:data:count:isFromCache:fetchedAt:hasPendingMutations:)` | — | Swift only: Kotlin's `PostgrestResult` is supabase-kt's, with a public constructor |
| — | `appliedOutcome(result)`, `queuedOutcome(pendingMutation)` | Not needed: `MutationOutcome`'s cases are public in Swift, so `.applied(result:)` and `.queued(pendingMutation:)` build them |
| — | `stalledRefresh(cause)` | Not needed: `RefreshState.stalled(cause:)` is a public case |
| `cacheMissError(message:)`, `mutationNotQueueableError(message:)`, `networkDisabledError(message:)` | `cacheMissException(message)`, `mutationNotQueueableException(cause, message)`, `networkDisabledException(message)` | `Error` for `Exception`: each builds an `OfflineError` with that code. Swift's has no `cause`; it is a network failure, as Kotlin's is through its cause |
| `syncFailedError(message:httpStatus:code:isConnectivityFailure:)` | `syncFailedException(message, httpStatus, code, isConnectivityFailure)` | `Error` for `Exception`; the status and code go in `serverError` |
| `offlineError(code:message:serverError:isNetworkOrConnectivityFailure:)` | — (supabase-kt's own exceptions have public constructors) | Swift only: any other code, such as `server` or `connectivity` |
| — | `cachedFile(path, fetchedAt, sizeBytes)` | Not yet: Storage's `CachedFile` takes its factory beside it |
