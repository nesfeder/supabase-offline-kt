# Signing out and switching users

With supabase-kt's Auth plugin installed, the query cache and the file cache belong to one user at
a time: when the session settles as someone else they are dropped, and nothing cached for one user
is ever served to the next. Queued writes stay with the user who made them until you call
`clearLocalData()`, which deletes everything the library keeps on the device:

```kotlin
--8<-- "QueueExamples.kt:sign-out"
```

Signing out in supabase-kt alone clears the session, not the device.

## What `clearLocalData()` deletes

The cached query results, the downloaded Storage files, every write still in the mutation queue
(never sent), the rejected list and the conflicted list. It does not touch the server, and the
client stays usable.

- A replay pass running at that moment is canceled first, and nothing queued is sent once the
  clear has begun.
- A write already on the wire is neither applied to the cache nor queued when it answers. A
  Storage download in flight may still hand its bytes to its caller, but they are not cached.
- A live read open through the clear that refreshes (any strategy but `CacheOnly`) fetches again:
  at once online, and once the network is back offline (`Paused` meanwhile).
- Keeping the deleted rows out of the database's files is best effort. The database is rewritten
  without them, and its write-ahead log, which still holds them, is emptied once no open read
  holds it: the clear waits up to a second for such reads, and when one outlasts that, the library
  empties the log within a few seconds of the read ending, and once more at `close()`. It never
  fails the clear for it.

## Order of operations

1. **Decide about the unsent writes.** `syncPendingMutations()` sends what can be sent now, as the
   user who made it; offline it returns at once. The clear deletes whatever is still queued, so an
   app that must not lose writes can show `syncStatus.value.pendingMutationCount` and ask first.
2. **Sign out, and clear in a `finally`.** Offline, supabase-kt's `signOut()` throws
   `HttpRequestException` and keeps the session, since it clears the session only when the server
   answers; clearing in a `finally` deletes the device's data either way. The listing above does
   the first two.
3. **Decide about the session.** After an offline sign-out the user is still signed in: the
   exception reaches your code with the session kept. To end it on this device anyway, forget it
   without the server; the server-side session stays valid until it expires:

    ```kotlin
    --8<-- "QueueExamples.kt:forget-session"
    ```

## Switching accounts

Facts about the engine, with the Auth plugin installed. The query cache and the Storage file
cache belong to one identity at a time: a signed-in user, or nobody signed in.

- A read never returns what was cached for another identity, and a response or download that
  started before the switch is never written into the new identity's cache.
- When the session settles into a different identity, both caches are dropped. The mutation queue
  is kept whole: each identity's writes wait for it.
- Without the Auth plugin, or with the app's own token provider, there is one identity, nobody
  signed in, for the life of the install: the cache is served as it is and never dropped for a
  change of user, since nothing the engine can read tells one user from another. An app whose own
  token provider serves more than one user must call `clearLocalData()` itself when its user
  changes, and only once the provider has stopped returning the outgoing user's token: a live read
  open through the clear that refreshes fetches again with whatever token the provider returns
  then.

Queued writes follow their owner. A write is recorded with the user who made it; another user's
writes are never listed, counted, sent, discarded or retried, and their ids do nothing. With Auth
installed, a queued write is sent with its owner's token or not at all, so user A's writes wait,
untouched, while user B is signed in, and go when A signs back in.

## While a stored session restores

Until supabase-kt's Auth plugin has restored the stored session, every request goes out on the
anon key, and the library keeps its own requests off it as far as it can. A queued write waits, as
the stored user's, and is sent with that user's token once the session settles, online or not. A
read the cache can answer for the stored user sends nothing, and one that needs the server is not
cached ([One-shot reads](read-strategies.md#edge-cases)). An `OnlineOnly` write, every Storage
request and every `rpc` are sent at once, on the anon key; a download's answer is cached, and a
private object refused on the anon key reads as "not found", which keeps its cached copy and is
answered from it. Under row-level security the anon key reaches less than the user does, so such a
write, `rpc` or Storage call can be refused, and a private download with nothing cached fails:
make them after `supabase.auth.awaitInitialization()`.

An expired session offline is still its user's: a write made with it is queued as that user's and
sent once the token is refreshed.

## Deleting an account

Delete a user's data on the server, then on the device, online, and clear the device first, so no
queued write can be replayed after your deletes and leave a row behind. Send the deletes
`OnlineOnly`: a queued delete could outlive the user it is for. Delete the user's Storage objects
through the bucket before anything that deletes the user: Supabase refuses to delete a user who
still owns objects, and deleting a row never deletes its file. The sample's Start over does all of
it for its anonymous user, whom nobody could sign back in as, then signs in as a new one:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [TaskRepository.swift](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/ios/OfflineTasks/Data/TaskRepository.swift)

=== "Android"

    ```kotlin
    --8<-- "data/supabase/OfflineTaskRepository.kt:start-over"

    --8<-- "data/supabase/OfflineTaskRepository.kt:delete-my-data"
    ```

=== "iOS"

    ```swift
    --8<-- "Data/TaskRepository.swift:start-over"

    --8<-- "Data/TaskRepository.swift:delete-my-data"
    ```

    The iOS sample discards the queued writes first and clears after the sign-out, with the
    client's network off in between: the order the Swift guide's
    [When the user changes](../getting-started/swift.md#when-the-user-changes) gives, and why.

## Caveats

On Android, Auto Backup includes the database and the file cache unless you leave them out, and a
restore brings them back under whoever signs in next ([Backups](../getting-started/setup.md#backups)).
The library cannot see a client in another process of your app
([A second process](../getting-started/setup.md#a-data-directory-and-a-second-process)).
