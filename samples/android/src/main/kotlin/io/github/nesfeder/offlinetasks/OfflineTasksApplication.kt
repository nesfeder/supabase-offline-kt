// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.nesfeder.offlinetasks.data.PhotoEncoder
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.inmemory.InMemoryTaskRepository
import io.github.nesfeder.offlinetasks.platform.AnonymousSession
import io.github.nesfeder.offlinetasks.platform.DirectoryPhotoFileStore
import io.github.nesfeder.offlinetasks.platform.JpegPhotoEncoder
import io.github.nesfeder.offlinetasks.platform.createSampleSupabaseClient
import io.github.nesfeder.offlinetasks.platform.internetAvailability
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

/**
 * Owns the app's single instances: the Supabase client, the anonymous session on it and the task
 * repository. They live as long as the process, so a rotation or a theme change never opens a
 * second client or a second database.
 */
class OfflineTasksApplication : Application() {

    /**
     * Work that must outlive any screen: replays, refreshes, the session, writes a closing screen
     * started. The screens report their own failures to [failures]; anything else that fails here
     * is logged rather than crashing the app.
     */
    val applicationScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, error ->
                    Log.e(TAG, "Background work failed", error)
                }
        )

    /** Where every screen reports a failure, to be shown on whichever screen is in front. */
    val failures: FailureReports = FailureReports()

    /** Where every screen reads and writes tasks. */
    lateinit var repository: TaskRepository
        private set

    /** The anonymous sign-in on the sample's Supabase project. */
    lateinit var session: AnonymousSession
        private set

    /** Re-encodes picked photos into the one format the bucket takes. */
    lateinit var photoEncoder: PhotoEncoder
        private set

    override fun onCreate() {
        super.onCreate()
        val internet =
            internetAvailability().shareIn(applicationScope, SharingStarted.Eagerly, replay = 1)
        val supabase =
            createSampleSupabaseClient(
                url = BuildConfig.SUPABASE_URL,
                publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            )
        session = AnonymousSession(supabase, applicationScope, internet).also { it.start() }
        // The in-memory stand-in for the library. Adopting the library replaces this one line.
        repository =
            InMemoryTaskRepository(
                scope = applicationScope,
                photoFiles = DirectoryPhotoFileStore(File(cacheDir, "photos")),
                networkAvailability = internet,
            )
        photoEncoder = JpegPhotoEncoder(contentResolver)
    }
}

/** The log tag of the app's own messages. */
private const val TAG = "OfflineTasks"

/** The app's [OfflineTasksApplication], for a ViewModel factory's initializer. */
val CreationExtras.offlineTasksApplication: OfflineTasksApplication
    get() = checkNotNull(this[APPLICATION_KEY]) as OfflineTasksApplication
