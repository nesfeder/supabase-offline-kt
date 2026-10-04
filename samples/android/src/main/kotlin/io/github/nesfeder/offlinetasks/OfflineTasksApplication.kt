// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.nesfeder.offlinetasks.data.PhotoEncoder
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.supabase.OfflineTaskRepository
import io.github.nesfeder.offlinetasks.platform.AnonymousSession
import io.github.nesfeder.offlinetasks.platform.JpegPhotoEncoder
import io.github.nesfeder.offlinetasks.platform.createSampleSupabaseClient
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the app's single instances: the Supabase client with the offline plugin, the anonymous
 * session on it and the task repository. They live as long as the process, so a rotation or a theme
 * change never opens a second client or a second database.
 */
class OfflineTasksApplication : Application() {

    /**
     * Work that must outlive any screen: the session, writes a closing screen started, Start over.
     * The screens report their own failures to [failures]; anything else that fails here is logged
     * rather than crashing the app.
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

    /**
     * False when `local.properties` names no Supabase project: the app then shows how to add one,
     * and none of the members below is set.
     */
    var isConfigured: Boolean = false
        private set

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
        val supabase =
            createSampleSupabaseClient(
                url = BuildConfig.SUPABASE_URL,
                publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            ) ?: return
        session = AnonymousSession(supabase, applicationScope).also { it.start() }
        repository = OfflineTaskRepository(supabase, session, applicationScope)
        photoEncoder = JpegPhotoEncoder(contentResolver)
        isConfigured = true
    }
}

/** The log tag of the app's own messages. */
private const val TAG = "OfflineTasks"

/** The app's [OfflineTasksApplication], for a ViewModel factory's initializer. */
val CreationExtras.offlineTasksApplication: OfflineTasksApplication
    get() = checkNotNull(this[APPLICATION_KEY]) as OfflineTasksApplication
