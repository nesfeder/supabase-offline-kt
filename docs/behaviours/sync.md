# Replaying the queue

When and how queued writes are sent to the server.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## Automatic and manual sync

## When to call sync

With `autoSync` on (the default), the library sends queued writes by itself when the network comes
back and when the session settles. Nothing fires when the app comes to the foreground: after a pass
that failed in a way that may pass (a `5xx`, a timeout), the library's own next pass waits up to a
minute, or as long as a server's `Retry-After` asks, up to an hour. To send at once when the user
returns, call `syncPendingMutations()` as the app comes to the foreground. It returns promptly
offline, throws nothing for a failure that may pass, and waits for a pass already sending:

```kotlin
// Android, in Application.onCreate(); appScope is a scope that lives as long as the app
ProcessLifecycleOwner.get().lifecycle.addObserver(
    LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            appScope.launch { supabase.offline.syncPendingMutations() }
        }
    }
)
```

On iOS, do the same when `UIApplication.willEnterForegroundNotification` is posted.

## Order

## Sync and authentication

## Outcome of each write

A write the server refuses for good on replay goes to `rejectedMutations()`, kept on the device
until the app dismisses or retries it. The list holds the newest 100 entries of every user together:
when it is full the oldest go, with their writes, and nothing reports it. Show the user's entries
and let them dismiss or retry each, so the list stays short.

## Return values and errors

## Background sync

## Calling sync concurrently
