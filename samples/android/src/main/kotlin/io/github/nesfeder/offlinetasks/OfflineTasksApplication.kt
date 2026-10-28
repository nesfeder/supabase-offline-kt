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
import io.github.nesfeder.offlinetasks.data.supabase.reportableDescription
import io.github.nesfeder.offlinetasks.platform.AnonymousSession
import io.github.nesfeder.offlinetasks.platform.JpegPhotoEncoder
import io.github.nesfeder.offlinetasks.platform.createSampleSupabaseClient
import io.github.nesfeder.offlinetasks.platform.validatedInternet
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.Notices
import io.github.nesfeder.offlinetasks.ui.feedback.launchReporting
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
                    // Its type, status and cause only: a message may carry a row or a token.
                    Log.e(TAG, "Background work failed: ${error.reportableDescription()}")
                }
        )

    /** Where every screen reports a failure, to be shown on whichever screen is in front. */
    val failures: FailureReports = FailureReports()

    /** Where every screen says what became of its work, to be shown on the screen in front. */
    val notices: Notices = Notices()

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
                networkAvailability = validatedInternet(),
                debugBuild = BuildConfig.DEBUG,
            ) ?: return
        session = AnonymousSession(supabase, applicationScope).also { it.start() }
        repository =
            OfflineTaskRepository(
                supabase = supabase,
                session = session,
                scope = applicationScope,
                failedPhotoDirectory = File(filesDir, FAILED_PHOTOS_DIRECTORY),
            )
        photoEncoder = JpegPhotoEncoder(contentResolver)
        applicationScope.launch(Dispatchers.IO) {
            File(filesDir, EARLIER_PENDING_PHOTOS_DIRECTORY).deleteRecursively()
        }
        isConfigured = true
    }

    // --8<-- [start:foreground]
    /**
     * Sends what waits in the mutation queue as the app comes to the foreground: the library starts
     * a replay by itself on a reconnection or a sign-in, and on nothing else.
     */
    fun syncInForeground() {
        if (!isConfigured) return
        applicationScope.launchReporting(failures) { repository.syncPendingMutations() }
    }
    // --8<-- [end:foreground]
}

/** The log tag of the app's own messages. */
private const val TAG = "OfflineTasks"

/**
 * The directory under the app's files where an earlier version of the app kept the photos waiting
 * to upload, before the library could queue an upload. Deleted at start: what it holds is never
 * uploaded.
 */
private const val EARLIER_PENDING_PHOTOS_DIRECTORY = "pending-photos"

/**
 * The directory under the app's files where a photo whose upload failed when it was sent at once is
 * kept, with its bytes, until it is retried or removed.
 */
private const val FAILED_PHOTOS_DIRECTORY = "failed-photo-adds"

/** The app's [OfflineTasksApplication], for a ViewModel factory's initializer. */
val CreationExtras.offlineTasksApplication: OfflineTasksApplication
    get() = checkNotNull(this[APPLICATION_KEY]) as OfflineTasksApplication
