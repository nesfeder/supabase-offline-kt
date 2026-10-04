# Testing your app

Unit-testing code that uses the library, with the testing artifact.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## The in-memory client

## Add the test artifact

## Pair it with a Ktor `MockEngine`

Give the client an engine that answers as your project would, and install PostgREST with
`maxRetries = 0`: supabase-kt retries a request that fails at the transport, so without it a read
the cache cannot answer waits out those retries, about seven seconds, before it fails.

```kotlin
val supabase = createSupabaseClient("https://test.supabase.co", "test-key") {
    httpEngine = MockEngine { request ->
        respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    install(Postgrest) { maxRetries = 0 }
    install(Offline) { inMemory() }
}
```

The factories that take a `PostgrestResult` (`appliedOutcome`, `querySnapshot`) need one built
over such a client, since supabase-kt's `Postgrest` needs a client with an engine:
`PostgrestResult(data, Headers.Empty, supabase.postgrest)`.

## Drive refreshes with a `TestScope`

The client runs on real time, not the test's virtual clock: `inMemory(clock)` sets the times it
records, not how long it waits. A refresh or a replay that failed waits a few seconds, then up to a
minute, before it tries again by itself. To cut that wait, give the client a `networkAvailability`
flow and turn it `true`, which wakes a waiting refresh, and the replay with `autoSync`, at once;
`syncPendingMutations()` sends without waiting. Await what you expect (`first { … }` on a flow)
rather than advancing the test's clock.

## Simulate being offline

## Assert queued writes

## Limits

Use `inMemory()` under Robolectric too. The platform's own database needs a SQLite binary built for
the host, and it records the application context once per process, so a later test's client would
open an earlier test's database.

A `CachedFile.path` from the in-memory client names a file in its memory, not on disk: read it
through the offline bucket.
