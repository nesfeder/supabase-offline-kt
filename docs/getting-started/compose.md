# Android apps in Compose

The library has no Compose dependency, and nothing in it returns Compose `State`: every value it
keeps current for an app, the status of syncing, a live read's rows, the queue, is a `Flow` or a
`StateFlow`, so a Compose screen collects it as it collects any other. This page is the few
decisions that come with that: where to collect, what to draw before the first value, and how the
sample turns the status into one banner.

## Collect in a ViewModel, read in the screen

Each of the sample's screens has a `ViewModel` that combines what the screen needs into one value,
and the screen collects that one `StateFlow`:

From the samples: [PhotosViewModel.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/ui/photos/PhotosViewModel.kt) · [PhotosScreen.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/ui/photos/PhotosScreen.kt)

```kotlin
--8<-- "ui/photos/PhotosViewModel.kt:screen-state"

--8<-- "ui/photos/PhotosScreen.kt:route"
```

- **`collectAsStateWithLifecycle`**, from `androidx.lifecycle:lifecycle-runtime-compose`, collects
  while the screen's lifecycle is at least started and stops while the app is in the background.
  The Compose runtime's `collectAsState` keeps collecting there, which for the library means a
  live read's refresh keeps trying, and a status keeps being mapped, for a screen nobody sees.
- **`SharingStarted.WhileSubscribed(5_000)`** keeps the upstream flows for five seconds after the
  last collector goes, so a rotation does not end the live read and start it again. A read started
  again after every collector has gone fetches again
  ([Reactive reads](../behaviors/reactive-reads.md#when-it-fetches-again)); the five seconds are
  what keep a rotation from costing a request.
- **The initial value** is what the screen draws before anything is known, here "nothing yet,
  loading". A `StateFlow` always has a value, so the screen never meets a `null` it did not choose.
- **A screen composable takes values, not flows.** `PhotosScreen` is given its state, so the
  sample's tests render each designed state without a client, and a preview can
  ([TasksScreenTest.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/test/kotlin/io/github/nesfeder/offlinetasks/ui/tasks/TasksScreenTest.kt)).

## Collect in a composable

A small, self-contained read can live in the composable that shows it. This listing is compiled by
the sample's tests rather than taken from one of its screens:

From the samples: [ComposeListings.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/test/kotlin/io/github/nesfeder/offlinetasks/docs/ComposeListings.kt)

```kotlin
--8<-- "docs/ComposeListings.kt:live-read"
```

- **`remember(inputs) { selectAsFlow { … } }`** builds the flow once per set of inputs. Built in
  the body without `remember`, each recomposition would make a new flow, and
  `collectAsStateWithLifecycle` would end the collection and start a new one each time.
- **`initialValue = null`** stands for "no value yet", which means the same as an emission whose
  `result` is `null`: nothing cached, and no queued write the query admits. The two together are
  the loading state; an empty body is an empty list
  ([What each emission says](../behaviors/reactive-reads.md#what-each-emission-says)).
- **`isFromCache`, `refresh` and `pendingRowKeys`** come in the same emission as the rows, so a
  "from this device" caption or a per-row pending mark never trails the rows it describes.
- **Decode once per emission**, under `remember(result)`, not on every recomposition.

A one-shot `suspend` read belongs in `produceState`, keyed on whatever should start it again. The
sample reads a photo's file this way, again when the server comes back in reach and once its
upload is done:

From the samples: [PhotoThumbnail.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/ui/photos/PhotoThumbnail.kt)

```kotlin
--8<-- "ui/photos/PhotoThumbnail.kt:produce-state"
```

## The status as one value

`syncStatus` is a `StateFlow` whose value changes as a whole, with its counts read together
([The status](../behaviors/sync.md#the-status)), so a composable can draw it directly:

```kotlin
--8<-- "docs/ComposeListings.kt:status"
```

The sample keeps the status in a type of its own and keeps the flow a `StateFlow`, by giving
`stateIn` the current value to start from:

From the samples: [OfflineTaskRepository.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/data/supabase/OfflineTaskRepository.kt) · [SyncHeadline.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/ui/status/SyncHeadline.kt) · [TasksViewModel.kt](https://github.com/nesfeder/supabase-offline-kt/blob/main/samples/android/src/main/kotlin/io/github/nesfeder/offlinetasks/ui/tasks/TasksViewModel.kt)

```kotlin
--8<-- "data/supabase/OfflineTaskRepository.kt:sync-status"
```

Then one function decides what the Tasks screen says, from the status and the list's own refresh
together, in the order that matters most; the Tasks `ViewModel` combines the two flows into it:

```kotlin
--8<-- "ui/status/SyncHeadline.kt:headline"

--8<-- "ui/tasks/TasksViewModel.kt:headline-flow"
```

`withSyncedMoment` holds "All changes synced" for two and a half seconds after a sync empties the
queue, then lets the banner go. The banner composable takes a `SyncHeadline` and draws it, with
nothing of the library in it. The mapping from the library's `SyncStatus` to the app's type is on
[Replaying the queue](../behaviors/sync.md#the-status), and what each refresh state should make a
screen say on [Refresh lifecycle](../behaviors/refresh-lifecycle.md).

## Compose `State` and AndroidX AppState

The library's values are flows, not Compose `State`, so that every value is seen, in order: a
status, and a read's snapshots, are values an app reads one after another, and the Swift package
wraps the same flows for SwiftUI. A composable turns a flow into `State` with
`collectAsStateWithLifecycle`, as above; code outside a composable that wants `State` derives it
with a headless composition, as Cash App's Molecule and AndroidX's `transform` do, collecting the
plugin's flows inside it as it would any other.

The [AndroidX AppState](https://developer.android.com/topic/libraries/architecture/appstate)
libraries, `androidx.appstate:statestore` and `androidx.appstate:transform`, are at their first
alpha as of October 2026. They need API 24 and bring the Compose runtime to every module that
depends on them, so the library does not depend on them and the samples do not use them. An app
that adopts them keeps using the plugin as this page shows: the flows are the surface, and what
derives `State` from them is the app's choice. Google's own guide to
[choosing a state API](https://developer.android.com/topic/libraries/architecture/appstate/state-beyond-ui#choose-state-api)
says the same: a `Flow` where every value must be processed in order.

## Compose Multiplatform

The library's API is the same in `commonMain`, so a Compose Multiplatform app collects the same
flows with the same code on Android and iOS, and `lifecycle-runtime-compose` is multiplatform too.
The samples are an Android app in Compose and an iOS app in SwiftUI
([iOS apps in Swift](swift.md)).

## Next steps

- [Reactive reads](../behaviors/reactive-reads.md): what each emission of a live read says.
- [Replaying the queue](../behaviors/sync.md): the status, and when the library sends by itself.
- [Testing your app](testing.md): the in-memory client, and the factories for test doubles.
