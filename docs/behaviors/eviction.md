# Cache size and eviction

The query cache is bounded by `cacheSizeBytes`, 100 MiB by default; past it, the lists fetched
longest ago are deleted first. A list a live reader shows is kept while it is shown, and the
mutation queue is never evicted. The samples write both bounds out at their defaults:

From the samples: [SampleSupabaseClient.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/platform/SampleSupabaseClient.kt) · [ClientSettings.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/App/ClientSettings.swift)

=== "Android"

    ```kotlin
    --8<-- "platform/SampleSupabaseClient.kt:sizes"
    ```

=== "iOS"

    ```swift
    --8<-- "App/ClientSettings.swift:sizes"
    ```

## What is measured and when

The bound counts the length of the cached responses, the JSON text PostgREST sent, summed over
every cached list. It does not count the database file, nor the queue, the rejected list or the
library's own records, which eviction never touches (the rejected list keeps its newest 100
entries). After a response of 4,096 characters or more, and after every 32nd response, the library
sums the cached responses; over the bound, it deletes the lists fetched longest ago, ten at a time,
until the sum is 50 MB below the bound (down to the bound itself when the bound is 50 MB or less),
so a burst of reads does not start a pass on every read. `Offline.CACHE_SIZE_UNLIMITED` (`-1`)
turns eviction off; any other value below 4 KiB counts as 4 KiB.

## What is evicted

The lists fetched longest ago, by the time the server sent them; a list a write marked stale keeps
its place by that time too. Up to 500 lists a live reader shows are kept whatever their age, and
the oldest list nobody reads goes in their place, so a reader never drops to "no data". Past 500
live lists, or for a reader that starts while a pass is deleting, a list a reader needs can go: a
`CacheFirstThenNetwork` reader then fetches it again, and a `CacheOnly` reader shows no data. A
write patched into the table while a pass runs changes the lists still there and brings none back.

SQLite keeps the pages a delete frees for its next writes, so the database file does not shrink
when lists are evicted, and it can be larger than the bound; `clearLocalData()` rewrites the
database, which returns the space.

## The file cache

Downloaded Storage files have a bound of their own, `fileCacheSizeBytes`, 256 MiB by default. When
a download passes it, the least recently used files are deleted until the cache is a tenth below
the bound. A file being read, and a file used in the last 30 seconds, are kept; a file's last use
is recorded on disk, so it survives a restart. `CACHE_SIZE_UNLIMITED` turns this eviction off, and
any other value below zero counts as zero. Temporary download files a crash left behind are
deleted once they are more than a day old. See [Storage caching](storage.md).
