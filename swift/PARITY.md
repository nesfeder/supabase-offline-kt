# Swift and Kotlin API parity

Every public Swift name in this package, beside its counterpart in the Kotlin (Android) API, and
why the two differ where they do. The rule: the Kotlin name wherever Swift syntax or a platform
type does not force a difference; where it does, the same concept and word stem. Each area of the
package keeps its own section; add a row for every public name you add.

## PostgREST reads and writes

| Swift | Kotlin | Why they differ |
| --- | --- | --- |
| `SupabaseOfflineClient.from(_:)` | `Offline.from(table)` | — |
| `SupabaseOfflineClient.from(schema:table:)` | `Offline.from(schema, table)` | Labels, as Swift names its arguments; the subscript `offline["todos"]` has no counterpart |
| `OfflinePostgrestQueryBuilder` | `OfflinePostgrestQueryBuilder` | — |
| `OfflinePostgrestQueryBuilder.table`, `.schema` | `.table`, `.schema` | `schema` is `nil` for the client's default, which the Swift builder does not hold |
| `select(_:head:count:readStrategy:)` | `select(columns, readStrategy, request)` | Filters and transforms chain after `select`, as in supabase-swift, in place of the trailing request lambda; `head` and `count` move from the lambda to parameters, as supabase-swift has them |
| `selectAsSequence(_:as:readStrategy:)` | `selectAsFlow(columns, readStrategy, request)` | An `AsyncSequence` where Kotlin has a `Flow`; `as:` names the `Decodable` type the rows decode to |
| `insert(_:defaultToNull:mutationStrategy:)` | `insert(value / values / body, mutationStrategy, request)` | One `Encodable` parameter (a row or an array) and an `OfflineJSON` overload, in place of three overloads; `defaultToNull` moves from the lambda to a parameter |
| `upsert(_:onConflict:ignoreDuplicates:defaultToNull:mutationStrategy:)` | `upsert(value / values / body, mutationStrategy, request)` | As `insert`; `onConflict` and `ignoreDuplicates` move from the lambda to parameters |
| `update(_:mutationStrategy:)` (an `Encodable` or `[String: OfflineJSON]`) | `update(value / body / update { }, mutationStrategy, request)` | No `PostgrestUpdate` DSL; `[String: OfflineJSON]` sends an explicit `null` |
| `delete(mutationStrategy:)` | `delete(mutationStrategy, request)` | — |
| `SupabaseOfflineClient.rpc(_:params:count:schema:)` | `Offline.rpc(function, parameters, request)` | `params:` as supabase-swift labels it; `count` and `schema` move from the lambda to parameters; arguments go by POST only |
| `SupabaseOfflineClient.rpc(_:head:get:count:schema:)` | `Offline.rpc(function, request)` | `head` and `get` choose the method that the lambda's `method` sets |
| `OfflineSelectRequestBuilder` | the `request` lambda of `select` (supabase-kt's `SelectRequestBuilder`) | A builder value chained before `execute()`, as supabase-swift does |
| `OfflineSelectRequestBuilder.execute()`, `execute(_:)` | the result of `select` | Swift sends a chain at `execute`, as supabase-swift does; `execute(_:)` decodes the rows, which Kotlin does with `decodeList` |
| `OfflineMutationRequestBuilder` | the `request` lambda of `insert`, `upsert`, `update`, `delete` | As `OfflineSelectRequestBuilder` |
| `OfflineMutationRequestBuilder.select(_:)`, `.single()` | `select(columns)` and `single()` in the lambda | — |
| `OfflineMutationRequestBuilder.execute()`, `execute(_:)` | the result of the write verbs | As above |
| `OfflineRPCRequestBuilder`, `.execute()`, `.execute(_:)` | the `request` lambda of `rpc` (supabase-kt's `RpcRequestBuilder`), and its result | As above |
| `QuerySnapshotSequence`, `QuerySnapshotSequence.Iterator` | `Flow<QuerySnapshot>` | An `AsyncSequence`; it also takes the filters and transforms the lambda would |
| `OfflinePostgrestFilterBuilder` | supabase-kt's `PostgrestFilterBuilder` (the `filter { }` lambda) | A protocol the three request builders and the live read share, since Swift chains where Kotlin nests a lambda |
| `OfflinePostgrestRequestBuilder` | supabase-kt's `PostgrestRequestBuilder` | As above, for `order`, `limit`, `range`, `single`, `csv`, `geojson`, `stripNulls` |
| `OfflinePostgrestFilterBuilder.applying(_:)`, `OfflineQueryChange` | — | What each filter and transform method makes; Kotlin needs none, as its lambdas call the builder directly |
| `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `like`, `ilike`, `notIn`, `contains`, `overlaps`, `rangeLt`, `rangeGt`, `rangeGte`, `rangeLte`, `textSearch`, `filter`, `or` | the same names | Filter methods keep supabase-swift's names and labels (`value:`, `pattern:`, `range:`), so a supabase-swift chain compiles unchanged |
| `is(_:value:)`, `in(_:values:)` | `exact`, `isIn` | `is` and `in` are Kotlin keywords, not Swift's |
| `match(_:pattern:)`, `imatch(_:pattern:)` | `regexMatch`, `regexIMatch` | supabase-swift's names |
| `match(_:)` (a dictionary) | `match(map)` | — |
| `likeAllOf`, `likeAnyOf`, `iLikeAllOf`, `iLikeAnyOf` | `likeAll`, `likeAny`, `ilikeAll`, `ilikeAny` | supabase-swift's names |
| `containedBy` | `contained` | supabase-swift's name |
| `rangeAdjacent` | `adjacent` | supabase-swift's name |
| `not(_:operator:value:)` | `filterNot(column, operator, value)` | supabase-swift's name |
| `fts(_:query:config:)` | `textSearch(column, query, TextSearchType.NONE, config)` | supabase-swift's shorthand |
| `order(_:ascending:nullsFirst:referencedTable:)` | `order(column, Order, nullsFirst, referencedTable)` | `ascending: Bool` as supabase-swift has it, in place of the `Order` enum |
| `limit(_:referencedTable:)`, `range(from:to:referencedTable:)`, `single()`, `csv()`, `geojson()`, `stripNulls()` | the same names | — |
| `OfflineFilterValue`, `filterText` | — (a Kotlin filter value is `Any`, written with `toString()`) | Swift needs a protocol to accept several value types; values are written as the Kotlin API writes them (lower-case UUIDs, UTC dates, `1.0E-7`) |
| `FilterOperator` | supabase-kt's `FilterOperator` | Lower-camel cases, as Swift names them |
| `OfflineTextSearchType` | supabase-kt's `TextSearchType` | supabase-swift has a public `TextSearchType`, which an app importing both would find ambiguous; `nil` stands for `NONE` |
| `Count` | supabase-kt's `Count` | Lower-camel cases |
| `ReadStrategy` (`networkOnly`, `networkFirst(maxAge:)`, `cacheFirst(maxAge:)`, `cacheOnly(maxAge:)`, and `networkFirst`, `cacheFirst`, `cacheOnly` without an age) | `ReadStrategy` (`NetworkOnly`, `NetworkFirst(maxAge)`, `CacheFirst(maxAge)`, `CacheOnly(maxAge)`) | `Duration` for `maxAge`; Swift needs the age-less static members where Kotlin has default arguments in a constructor |
| `SnapshotStrategy` (`cacheFirstThenNetwork`, `networkFirstThenCache`, `cacheOnly`) | `FlowReadStrategy` | Swift has no `Flow`: the strategy is named for the snapshots it yields |
| `MutationStrategy` (`queueIfOffline`, `onlineOnly`) | `MutationStrategy` (`QueueIfOffline`, `OnlineOnly`) | — |
| `OfflineResponse` (`value`, `data`, `count`, `isFromCache`, `fetchedAt`, `hasPendingMutations`) | supabase-kt's `PostgrestResult` (`data`, `countOrNull()`) with the extension properties `isFromCache`, `fetchedAt`, `hasPendingMutations` | Swift cannot return an upstream type, so the provenance is stored on the response; `value` is the decoded body, as supabase-swift's `execute().value` |
| `QuerySnapshot` (`result`, `isFromCache`, `fetchedAt`, `hasPendingMutations`, `refresh`) | `QuerySnapshot` (the same fields) | `result` is the rows decoded as the type asked for, where Kotlin holds the undecoded `PostgrestResult` |
| `RefreshState` (`idle`, `refreshing`, `paused`, `stalled(cause:)`) | `RefreshState` (`Idle`, `Refreshing`, `Paused`, `Stalled(cause)`) | `cause` is an `OfflineError` |
| `MutationOutcome` (`applied(result:)`, `queued(pendingMutation:)`, `isQueued`, `result`) | `MutationOutcome` (`Applied(result)`, `Queued(pendingMutation)`, `isQueued`, `resultOrNull()`) | `result` is an optional property, as Swift writes `resultOrNull()` |
| `PendingMutation` (`id`, `table`, `operation`, `createdAt`, `rowKeys`, `body`) | `PendingMutation` (the same fields) | `Date` for `createdAt`; `OfflineJSON` for the row keys and the body |
| `MutationOperation` (`insert`, `upsert`, `update`, `delete`) | `MutationOperation` (`INSERT`, `UPSERT`, `UPDATE`, `DELETE`) | Lower-camel cases |
