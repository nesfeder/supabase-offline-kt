# Testing your app

The testing artifact builds the client the way your app builds it, with
`install(Offline) { inMemory() }`: the cache, the mutation queue and the file cache live in the
client's memory, and nothing touches the disk. Its factories build the library's value types, for
your own test doubles. The sample apps' tests use both: the client over a fake of the samples'
project, and the factories for the library's values.

From the samples: [FakeSupabaseServer.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/test/kotlin/io/github/nesfeder/offlinetasks/testing/FakeSupabaseServer.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "testing/FakeSupabaseServer.kt:in-memory-client"
    ```

=== "iOS"

    ```swift
    --8<-- "App/AppModel.swift:in-memory-client"
    ```

## Add the test artifact

```kotlin
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:0.2.0")
            implementation("io.ktor:ktor-client-mock:3.4.3")
        }
    }
}
```

**Use it at exactly the library's version**, from one version-catalog entry. Its factories and its
in-memory client call into the library outside the API records, so they work only with the library
of their own version. Its published metadata pins the library to that version with a strict
constraint, and that works in one direction only:

- an older testing artifact beside a newer library fails dependency resolution, rather than a test
  failing with a missing method;
- a newer testing artifact raises the library in your test classpath to its own version without a
  word, so your tests would run on a library your app does not ship.

## The in-memory client

`inMemory(clock)` keeps the plugin's local data in the client. Every other setting applies as in
your app. What differs:

- **Each client starts empty**, and a second client over the same configuration shares nothing with
  the first, unless both are built over one `InMemoryStore` ([below](#simulate-a-relaunch)).
- **No connectivity monitor.** `syncStatus.value.network` stays `UNKNOWN`, which counts as online,
  until the test sets `networkAvailability` or calls `disableNetwork()`.
- **The clock** is the time the client records: when rows were fetched and writes queued, and so
  how old a cached read is against a `maxAge`. It defaults to `Clock.System`.
- **Background failures are logged**, never thrown into the test: the client's work runs in the
  library's own scope, not the test's.
- **supabase-kt's own plugins keep their own state.** Give Storage a `MemoryResumableCache` and
  Auth a session manager that keeps the session in memory.
- **A `CachedFile.path`** names a file in the client's memory, not on disk: read it through the
  offline bucket.

Use `inMemory()` under Robolectric too. The platform's own database needs a SQLite binary built for
the host, and it records the application context once per process, so a later test's client would
open an earlier test's database.

## Pair it with a Ktor `MockEngine`

Give the client an engine that answers as your project would, and install PostgREST with
`maxRetries = 0`, as above: supabase-kt retries a request that fails at the transport, so without
it a read the cache cannot answer waits out those retries, about seven seconds, before it fails.

## Simulate being offline

Call `disableNetwork()`: reads are answered from the cache and writes are queued, as on a device in
airplane mode, until `enableNetwork()`. To see how your app meets a server that fails or a
connection that drops, fail the requests in the mock engine instead. The sample's test goes
through its own repository, whose Simulate offline is `disableNetwork()`:

From the samples: [OfflineTaskRepositoryTest.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/test/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepositoryTest.kt) · [ApiTourTests.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasksTests/ApiTourTests.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepositoryTest.kt:offline-test"
    ```

=== "iOS"

    ```swift
    --8<-- "ApiTourTests.swift:offline-test"
    ```

## Simulate a relaunch

Each in-memory client starts empty. To test what your app does after it is killed and relaunched
(a write queued offline is sent, a rejected write is still listed, a cached list still shows),
build both clients over one `InMemoryStore` with `inMemory(store = store)`. The second finds what
the first left when it closed: the queued writes, which it replays, the rejected and conflicted
lists, the cached queries and the cached files. Nothing a close does clears the store;
`clearLocalData()` does. What the library keeps only while a client runs starts over, as after a
real relaunch: the status's `lastSyncedAt` and `lastSyncError`, the `disableNetwork()` and
`disableRealtime()` switches, and the waits between failed passes.

One client holds a store at a time, as one holds a database: building a client over a store
another holds, open or still closing, fails with an `IllegalStateException`, since both would
replay the same queued writes. Close the first client before building the next. In this test from
the testing artifact's own suite, `store` is an `InMemoryStore()` and `server.supabaseClient { … }`
builds a client on the test's `MockEngine` with `install(Offline) { … }`:

From the tests: [InMemoryStoreTest.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/supabase-offline-kt-testing/src/commonTest/kotlin/io/github/nesfeder/supabase/offline/testing/InMemoryStoreTest.kt)

```kotlin
--8<-- "InMemoryStoreTest.kt:relaunch"
```

The relaunched client replays the write by itself once it opens, with `autoSync` on. In Swift,
`SupabaseOfflineClient.inMemory(…, store:)` takes the testing product's `InMemoryStore` the same
way.

## Report Realtime changes

A test of an app that names tables in `realtime { }` gives the in-memory client a `FakeRealtime`
in place of a Realtime channel, so `install(Realtime)` is not needed. The client joins the fake's
channel as it would Realtime's (signed in or without Auth, the network not known down, the
subscription on). `subscribedTables` says which tables it follows as soon as it opens their
channels, and `joinCount` moves once it has handled a join: it has reported `Subscribed` and asked
for the joined tables' cached queries to be read again, since nothing is replayed. Wait for
`joinCount` before reporting a change, or the join may be handled after it.

Each report reaches the cached queries as `applyServerRecord` and `applyServerDelete` apply a
record, as Realtime would send it. A report to a table nothing follows is dropped and counted in
`droppedReportCount`, and so is an insert or an update whose record does not meet the table's
`RealtimeFilter`, which the server evaluates before it sends: numbers are compared as numbers,
other values as text, and a missing or `null` column meets no filter. A delete reaches the client
whatever the filter, as Realtime sends a delete to every subscriber of the table.
`failChannel(cause)` ends every table's channel as a server that refuses them would, and
`dropChannel()` as a lost socket: `realtimeStatus` reads `Stalled`, and the client joins again
after a short wait. `failTable(table, cause)` refuses one table alone, as a server refuses a
binding: its entry in `realtimeTableStatus` reads `Stalled` while the other tables stay joined
and keep receiving reports.

The `MockEngine` plays the server, and the fake only the channel. The client reads the named tables
from the engine on every join, and reads a query again after a change that met a read in flight or
that it cannot apply to the query exactly. So the engine must answer with the rows as they are
after each change the test reports, as a server would; an engine that still answers with the rows
from before has those reads take the change back out of the cache.

```kotlin
var tasks = JsonArray(emptyList()) // the server's rows: the engine answers every read with them
val fakeRealtime = FakeRealtime()
val supabase = createSupabaseClient(url, key) {
    httpEngine = MockEngine { respond(tasks.toString(), headers = jsonHeaders) }
    install(Postgrest) { maxRetries = 0 }
    install(Offline) {
        inMemory(realtime = fakeRealtime)
        realtime { table("tasks") }
    }
}
fakeRealtime.joinCount.first { it > 0 }
val milk = buildJsonObject { put("id", "t1"); put("title", "Milk") }
tasks = JsonArray(tasks + milk) // the row is on the server, then its change is sent
fakeRealtime.reportInsert("tasks", milk)
tasks = JsonArray(emptyList())
fakeRealtime.reportDelete("tasks", buildJsonObject { put("id", "t1") })
```

In Swift, `SupabaseOfflineClient.inMemory(…, realtime:)` takes the testing product's `FakeRealtime`
the same way, with `joinCount`, `reportInsert(table:record:)` and the rest; the Swift API
reference's Testing your app article has the listing.

## Stage a conflict

To test your resolver or your conflicted-list screen, move the row on the server between the moment
a write is queued and the moment it replays. The server lives in the test's `MockEngine`, so stage
it there: answer the first read with the row at one version, the replayed PATCH preconditioned on
that version with `[]`, the read by key with the row at a new version, and the resend with the
row. This test, from the testing artifact's own suite, does exactly that; `BASE`, `MOVED` and
`RESENT` are constants of the test (`2026-10-18T13:00:27.486123+00:00`,
`2026-10-18T13:04:00.000001+00:00` and `2026-10-18T13:05:00.000002+00:00`):

From the tests: [ConflictStagingTest.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/supabase-offline-kt-testing/src/commonTest/kotlin/io/github/nesfeder/supabase/offline/testing/ConflictStagingTest.kt)

```kotlin
--8<-- "ConflictStagingTest.kt:stage-conflict-rows"

--8<-- "ConflictStagingTest.kt:stage-conflict"
```

The resolver is then shown `conflictingColumns == setOf("title")`, the first PATCH carries
`updated_at=eq.2026-10-18T13%3A00%3A27.486123%2B00%3A00` and `Prefer: return=representation`, the
resend carries the moved version, and the report counts one write synced and one conflict
resolved. A resolver that answers `Hold` instead puts the write on `conflictedMutations()`. Tell the
read by key from the first read by its `id=eq.…` filter, and keep `autoSync = false`, so that the
test's own `syncPendingMutations()` is the pass that replays.

In Swift, the testing product's `FakeServer` stages the same: answer the guarded PATCH with `[]`
and the read by key with the moved row, and the closure in `Config.conflictResolver` is shown the
conflict.

## Waiting for the client

The client runs on real time, not the test's virtual clock: `inMemory(clock)` sets the times it
records, not how long it waits. A refresh that failed tries again by itself after a few seconds,
then up to a minute; a replay that failed runs again only when something asks for a pass, and no
sooner than its wait ([Replaying the queue](../behaviors/sync.md#waiting-between-passes)). To cut
that wait, give the client a `networkAvailability` flow and turn it `true`, which wakes a waiting
refresh, and the replay with `autoSync`, at once; `syncPendingMutations()` sends without waiting.
Await what you expect (`first { … }` on a flow) rather than advancing the test's clock.
`syncStatus`'s counts follow the queue shortly after each change, so wait for the value you expect
there too.

## Values for your own test doubles

The library's value types have no public constructors, so a test of code that reads them, or a
fake of your own repository, builds them with the testing artifact's factories. The sample tests
the mappings from the library's values to its own screens' this way:

From the samples: [LibraryMappingsTest.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/test/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappingsTest.kt) · [WordingTests.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasksTests/WordingTests.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/LibraryMappingsTest.kt:factories"
    ```

=== "iOS"

    ```swift
    --8<-- "WordingTests.swift:factories"
    ```

| Factory | Builds |
| --- | --- |
| `appliedOutcome`, `queuedOutcome` | `MutationOutcome.Applied`, `MutationOutcome.Queued` |
| `pendingMutation`, `rejectedMutation`, `serverError` | The queue's values; an overload of `pendingMutation` takes `baseVersion` |
| `mutationConflict`, `conflictedMutation` | What a resolver is shown, and an entry of the conflicted list |
| `querySnapshot`, `stalledRefresh` | A live read's emission, and a stalled refresh |
| `cachedResult` | A one-shot read's result as the cache answered it: `isFromCache`, `fetchedAt` and `hasPendingMutations` read as given |
| `syncReport`, `syncStatus` | What a sync returns, and the status; overloads take the conflict counts, `conflicted` and `conflictedMutationCount` |
| `cachedFile`, `downloadProgress` | A Storage file in the cache, and a download's progress |
| `cacheMissException`, `mutationNotQueueableException`, `networkDisabledException`, `offlineClientClosedException`, `syncFailedException` | The exceptions; `syncFailedException(isConnectivityFailure = true)` is one `isNetworkOrConnectivityFailure()` accepts |

Every field is a parameter, and the ones a test rarely sets have defaults. The factories that take
a `PostgrestResult` need one built over a client with an engine, since supabase-kt's `Postgrest`
needs one: `PostgrestResult(data, Headers.Empty, supabase.postgrest)`, which reads as a result
supabase-kt returned itself, or `cachedResult(data, supabase, fetchedAt)`, which reads as the
cache's.
