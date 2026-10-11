# Testing your app

The testing artifact builds the client the way your app builds it, with
`install(Offline) { inMemory() }`: the cache, the mutation queue and the file cache live in the
client's memory, and nothing touches the disk. Its factories build the library's value types, for
your own test doubles. The sample apps' tests use both: the client over a fake of the samples'
project, and the factories for the library's values.

From the samples: [FakeSupabaseServer.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/test/kotlin/io/github/nesfeder/offlinetasks/testing/FakeSupabaseServer.kt) · [TestSupport.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasksTests/TestSupport.swift)

=== "Android"

    ```kotlin
    --8<-- "testing/FakeSupabaseServer.kt:in-memory-client"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

## Add the test artifact

```kotlin
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:0.1.0")
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
  the first.
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

From the samples: [OfflineTaskRepositoryTest.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/test/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepositoryTest.kt) · [OfflineTaskTests.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasksTests/OfflineTaskTests.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepositoryTest.kt:offline-test"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

## Waiting for the client

The client runs on real time, not the test's virtual clock: `inMemory(clock)` sets the times it
records, not how long it waits. A refresh or a replay that failed waits a few seconds, then up to a
minute, before it tries again by itself. To cut that wait, give the client a `networkAvailability`
flow and turn it `true`, which wakes a waiting refresh, and the replay with `autoSync`, at once;
`syncPendingMutations()` sends without waiting. Await what you expect (`first { … }` on a flow)
rather than advancing the test's clock. `syncStatus`'s counts follow the queue shortly after each
change, so wait for the value you expect there too.

## Values for your own test doubles

The library's value types have no public constructors, so a test of code that reads them, or a
fake of your own repository, builds them with the testing artifact's factories. The sample tests
the mappings from the library's values to its own screens' this way:

From the samples: [LibraryMappingsTest.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/test/kotlin/io/github/nesfeder/offlinetasks/data/supabase/LibraryMappingsTest.kt) · [SyncHeadlineTests.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasksTests/SyncHeadlineTests.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/LibraryMappingsTest.kt:factories"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

| Factory | Builds |
| --- | --- |
| `appliedOutcome`, `queuedOutcome` | `MutationOutcome.Applied`, `MutationOutcome.Queued` |
| `pendingMutation`, `rejectedMutation`, `serverError` | The queue's values |
| `querySnapshot`, `stalledRefresh` | A live read's emission, and a stalled refresh |
| `syncReport`, `syncStatus` | What a sync returns, and the status |
| `cachedFile` | A Storage file in the cache |
| `cacheMissException`, `mutationNotQueueableException`, `networkDisabledException`, `syncFailedException` | The exceptions; `syncFailedException(isConnectivityFailure = true)` is one `isNetworkOrConnectivityFailure()` accepts |

Every field is a parameter, and the ones a test rarely sets have defaults. The factories that take
a `PostgrestResult` need one built over a client with an engine, since supabase-kt's `Postgrest`
needs one: `PostgrestResult(data, Headers.Empty, supabase.postgrest)`.
