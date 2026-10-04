# FAQ and troubleshooting

Answers to common questions, and fixes for common problems.

> **Not written yet.** This page is a placeholder in the planned site. The headings below are its
> outline; the content will be written for 0.1.0, once the API it describes has settled.

## Plugin installation

## First read while offline

The cache holds the answers the server gave, one per request: a request's columns, filters, order
and range make its key. A request never answered online has nothing cached, so offline it fails
(`NetworkFirst`), or shows nothing (`selectAsFlow` emits `null` rows), even when another cached request of the same
table holds the rows it asks for. Read a detail screen's row from the list a list screen already
reads, with the same request, and pick it out on the device:

```kotlin
fun task(id: String): Flow<Task?> =
    supabase.offline.from("tasks").selectAsFlow { order("title", Order.ASCENDING) }
        .map { snapshot -> snapshot.result?.decodeList<Task>()?.firstOrNull { it.id == id } }
```

## Offline fallback timing

## Lists and loading states

## Reading back a queued row

A queued write shows in cached reads at once, as it was sent. The rows a queued insert or upsert
adds come after the server's rows, whatever the request's order and limit, so sort on the device if
the order matters before the write is sent. They lack the columns the server fills in (defaults,
triggers) until the server has the write: decode them into a type whose server-filled fields are
optional.

## Upserts

## iOS builds

## Using the library alongside Room
