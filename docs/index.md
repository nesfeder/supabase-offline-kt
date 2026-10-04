# supabase-offline-kt

supabase-offline-kt is a plugin for [supabase-kt](https://github.com/supabase-community/supabase-kt)
that keeps a Kotlin Multiplatform app working without a network, on Android and iOS. You install
it beside PostgREST and reach it as `supabase.offline`, and it takes supabase-kt's own calls. The
server stays the source of truth: the device holds what the app has read, and the writes it has
not sent yet.

## What it does

- **Cached reads.** A read is answered by the server, and the answer is kept on the device. When
  the server cannot be reached, the kept rows are served instead, with the writes still waiting
  laid over them. A live read emits again whenever those rows change.
- **Queued writes.** An insert, upsert, update or delete is saved on the device first, then sent
  at once when nothing of the same user waits ahead of it. Offline, it waits in the mutation queue
  and is sent, in the order it was made, when the network comes back.
- **Cached files.** A Storage download is kept on disk and served from there when the network is
  down.

## An example

```kotlin
--8<-- "GettingStartedExamples.kt:home"
```

A write returns a `MutationOutcome` rather than the server's result, because a queued write has
no answer yet. That is the one difference in what a call returns.

## Where next

- [Project setup](getting-started/setup.md): add the dependency and make the first offline read
  and write.
- [Coming from supabase-kt](getting-started/migrating.md): what stays the same, and the few call
  shapes that need an edit.
- [Behaviors](behaviors/index.md): what each read and write does online, offline and in
  between.
- [Sample apps](samples/index.md): a to-do app with photos that shows all of it.

## Platforms

| | |
| --- | --- |
| Android | API 23 and later |
| iOS | 15 and later, `iosArm64` and `iosSimulatorArm64`, from Kotlin |
| supabase-kt | 3.6, the one minor each release is built and tested against ([why](project/compatibility.md#supabase-kt)) |
| Other Kotlin targets | none: no JVM, JS or Wasm |

The library is not affiliated with Supabase.
