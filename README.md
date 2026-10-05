[![CI](https://github.com/nesfeder/supabase-offline-kt/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/nesfeder/supabase-offline-kt/actions/workflows/ci.yml)

# supabase-offline-kt

A plugin for [supabase-kt](https://github.com/supabase-community/supabase-kt) that makes PostgREST
and Storage offline-first in Kotlin Multiplatform apps on Android and iOS. Reads are answered from
a cache on the device when the server cannot be reached, writes made offline wait in a queue on the
device and are sent, in the order they were made, when the network comes back, and Storage
downloads are kept on disk. You keep supabase-kt's own calls, filters and request DSL.

## Quick start

### Add the dependency

The library brings supabase-kt's Postgrest, Storage and Auth with it, at the supabase-kt minor it
is built and tested against: 3.6. supabase-kt sends its requests through Ktor, so give each target
a Ktor engine of the version supabase-kt uses.

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt:0.1.0")
        }
        androidMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.4.3") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.4.3") }
        commonTest.dependencies {
            implementation("io.github.nesfeder.supabase-offline:supabase-offline-kt-testing:0.1.0")
        }
    }
}
```

In an Android-only app, the same coordinates go in `dependencies { }`, with the OkHttp engine. Use
the testing artifact at exactly the library's version, from one version-catalog entry. Its published
metadata pins the library to its own version: an older testing artifact fails dependency
resolution, and a newer one raises the library in your test classpath to its version without a
word, so your tests would run on a library your app does not ship.

### Install the plugin

```kotlin
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.offline

val supabase = createSupabaseClient(
    supabaseUrl = "https://your-project.supabase.co",
    supabaseKey = "your-publishable-key",
) {
    install(Postgrest)
    install(Storage) // if you use Storage
    install(Offline) // last; settings go here: install(Offline) { cacheSizeBytes = … }
}
```

`supabase.offline` is the plugin, reached as `supabase.postgrest` is; it needs the
`io.github.nesfeder.supabase.offline.offline` import.

### Read

```kotlin
@Serializable
data class Task(val id: String, val title: String, val done: Boolean = false)

val tasks = supabase.offline.from("tasks")
    .select { order("title", Order.ASCENDING) }
    .decodeList<Task>()
```

Online, this is the server's answer, and the device keeps it. Offline, the same request is answered
from what the device kept. A request never answered online has nothing kept: the first time,
offline, it fails as supabase-kt would. Each request is kept on its own, so read a single task from
the list you already read rather than with a request of its own. `selectAsFlow { … }` reads the
same rows as a `Flow` that follows every change.

### Write

```kotlin
val task = Task(id = Uuid.random().toString(), title = "Buy milk")

when (val outcome = supabase.offline.from("tasks").insert(task)) {
    is MutationOutcome.Applied -> outcome.result // the server has it
    is MutationOutcome.Queued -> outcome.pendingMutation // saved on the device, sent later
}
```

Make the row's id on the device. A write that was sent but whose answer never arrived stays queued
and is sent again; with its id it is recognized as the row already there instead of being inserted
twice. Reads show a queued write at once, and the library sends the queue by itself when the
network comes back.

### Swift

The Swift package is being rebuilt as a native Swift API, and this build has no Swift API to call
yet; its quick start comes with it.

## Documentation

The [documentation site](https://nesfeder.github.io/supabase-offline-kt/), also under
[`docs/`](./docs/index.md): [the architecture](./docs/project/architecture.md), with the whole
public API and what the library promises;
[compatibility and versioning](./docs/project/compatibility.md); and
[the sample app](./docs/samples/index.md).

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md).

## License

MIT; see [LICENSE](./LICENSE).
