# Cache size and eviction

The query cache is bounded by `cacheSizeBytes`, 100 MiB by default, counted as the summed length of
the cached responses; past it, the lists fetched longest ago are deleted first. A list a live
reader shows is never deleted while it is shown, and the mutation queue is never evicted at all.

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [AppModel.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/AppModel.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:sizes"
    ```

=== "iOS"

    The iOS sample's section lands with its pull request.

## What is measured

The bound counts the length of the cached responses, the JSON text PostgREST sent, summed over
every cached list. It does not count the database file, which SQLite never shrinks when rows are
deleted (below), nor the queue, the rejected list or the library's own records, which eviction
never touches (the rejected list has its own bound: its newest 100 entries).

## When eviction runs

After a response of 4,096 characters or more, and after every 32nd response, the library sums the
cached responses. When the sum is over the bound, it deletes the lists fetched longest ago, ten at
a time, until the sum is 50 MB below the bound, or down to the bound itself when the bound is
50 MB or less. Draining below the bound means a burst of reads does not start a pass on every
read.

## What is evicted

The lists fetched longest ago, by the time the server sent them. A list a write marked stale keeps
its place by that time too. Up to 500 lists a live reader shows are kept whatever their age, and
the oldest list nobody reads goes in their place, so a reader never drops to "no data".

Past 500 live lists, or for a reader that starts while a pass is deleting, a list a reader needs
can go: a `CacheFirstThenNetwork` reader then fetches its list again, and a `CacheOnly` reader
shows no data.

## Unlimited and minimum sizes

`Offline.CACHE_SIZE_UNLIMITED` (`-1`) turns eviction off. Any other value below 4 KiB counts as
4 KiB.

## The database file

SQLite keeps the pages a delete frees for its next writes, so the database file does not shrink
when lists are evicted, and it can be larger than the bound. `clearLocalData()` rewrites the
database, which returns the space.

## The file cache

Downloaded Storage files have a bound of their own, `fileCacheSizeBytes`, 256 MiB by default. When
a download passes it, the least recently used files are deleted until the cache is a tenth below
the bound. A file being read, and a file used in the last 30 seconds, are kept; a file's last use
is recorded on disk, so it survives a restart. `CACHE_SIZE_UNLIMITED` turns this eviction off, and
any other value below zero counts as zero. Temporary download files a crash left behind are
deleted once they are more than a day old. See [Storage caching](storage.md).

## Eviction and live queries

Facts about the engine. Eviction takes the lists fetched longest ago first; a list marked stale
keeps its place by the time it was fetched. A list a live reader shows is never evicted while it
is shown: the oldest list nobody observes goes instead, and the reader never drops to "no data".
Up to 500 live queries are protected this way; past that, or for a reader that starts while an
eviction pass is deleting, a `CacheFirstThenNetwork` reader fetches its list again and a
`CacheOnly` one shows no data.
<small>`LiveEvictionTest.eviction_keeps_the_oldest_payload_while_a_reader_shows_it`,
`QueryCacheEvictionTest.eviction_removes_oldest_rows_until_within_limit_keeping_newest`,
`QueryCacheEvictionTest.eviction_drains_below_the_ceiling_to_the_hysteresis_floor`,
`QueryCacheEvictionTest.unlimited_cache_never_evicts`,
`QueryCacheEvictionTest.add_payload_enforces_on_the_write_interval_for_small_writes`</small>
