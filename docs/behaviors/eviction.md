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
entries). The library keeps a running count of that length. It reads the cached lists once, in the
background after the first response of a process is written, and moves the count by each response
written, less the list it replaced, and each list deleted since. A response is returned to its
reader before any of this runs. When the count passes the bound, a pass runs in the background: it
reads every list's length once, oldest fetch first, chooses the lists to delete until the count is
50 MB below the bound (down to the bound itself when the bound is 50 MB or less), deletes them in
one transaction, and sets the count from what it read. A list fetched again while the pass runs is
left in place, as the newest. A burst of reads past the bound costs one pass, not one per read, and
responses that land during a pass get a pass of their own.
`Offline.CACHE_SIZE_UNLIMITED` (`-1`) turns eviction off; any other value below 4 KiB counts as 4
KiB.

## What is evicted

The lists fetched longest ago, by the time the server sent them; a list a write marked stale keeps
its place by that time too. Up to 500 lists a live reader shows are kept whatever their age, and
the oldest list nobody reads goes in their place, so a reader never drops to "no data". Past 500
live lists, or for a reader that starts while a pass is choosing, a list a reader needs can go: a
`CacheFirstThenNetwork` reader then fetches it again, and a `CacheOnly` reader shows no data. A
write patched into the table while a pass runs changes the lists still there and brings none back.

SQLite keeps the pages a delete frees for its next writes, so the database file does not shrink
when lists are evicted, and it can be larger than the bound; `clearLocalData()` rewrites the
database, which returns the space.

## The file cache

Downloaded Storage files have a bound of their own, `fileCacheSizeBytes`, 256 MiB by default. When
a download passes it, the least recently used files are deleted until the cache is a tenth below
the bound. A file being read, and a file used in the last 30 seconds, are kept; a file's last use
is recorded on disk, to the minute, so it survives a restart. `CACHE_SIZE_UNLIMITED` turns this eviction off, and
any other value below zero counts as zero. Temporary download files a crash left behind are
deleted once they are more than a day old. An app can delete files itself too: one object's copies
with a bucket's `evictCachedFile(path)`, and every file with `offline.storage.clearFileCache()`,
which keeps the mutation queue. See [Storage caching](storage.md#evicting-and-clearing).
