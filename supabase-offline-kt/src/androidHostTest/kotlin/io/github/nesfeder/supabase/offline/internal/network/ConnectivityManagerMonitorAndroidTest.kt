// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.ApplicationContextInitializer
import io.github.nesfeder.supabase.offline.offline
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * The Android connectivity monitor: online while a network with internet access that Android has
 * validated is up. Collecting it registers one network callback with the `ConnectivityManager`, and
 * ending the collection unregisters it; the plugin's monitor is registered while its client is
 * open, and gone once it is closed.
 */
@RunWith(AndroidJUnit4::class)
class ConnectivityManagerMonitorAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val directory = File(context.cacheDir, "offline-monitor-${Random.nextLong().toULong()}")

    @BeforeTest
    fun startUp() {
        ApplicationContextInitializer().create(context)
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun capabilities(validated: Boolean): NetworkCapabilities {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated)
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return capabilities
    }

    @Test
    fun a_validated_network_reads_as_online_and_its_loss_as_offline() = runTest {
        // Given — the device's network has internet, validated
        val network = checkNotNull(manager.activeNetwork)
        shadowOf(manager).setNetworkCapabilities(network, capabilities(validated = true))
        val seen = mutableListOf<Boolean>()
        val collection =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                connectivityManagerNetworkAvailability(context).collect { online -> seen += online }
            }
        val callback = shadowOf(manager).networkCallbacks.single()

        // When — it is lost, comes back, then loses its validation
        callback.onLost(network)
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, capabilities(validated = false))

        // Then
        assertEquals(listOf(true, false, true, false), seen)
        collection.cancelAndJoin()
    }

    @Test
    fun ending_the_collection_unregisters_the_callback() = runTest {
        // Given
        val collection =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                connectivityManagerNetworkAvailability(context).collect {}
            }
        val whileCollected = shadowOf(manager).networkCallbacks.size

        // When
        collection.cancelAndJoin()

        // Then
        assertEquals(1 to 0, whileCollected to shadowOf(manager).networkCallbacks.size)
    }

    @Test
    fun the_plugin_watches_the_network_while_its_client_is_open_and_lets_go_when_it_closes() =
        runTest {
            // Given — the plugin installed with no signal of the app's own
            val network = checkNotNull(manager.activeNetwork)
            shadowOf(manager).setNetworkCapabilities(network, capabilities(validated = true))
            val supabase = newTestSupabaseClient {
                install(Postgrest)
                install(Offline) { dataDirectory = directory.path }
            }
            val status = supabase.offline.syncStatus

            // When — the device loses its network, then the client closes
            val registered = awaitCallbacks(1)
            shadowOf(manager).networkCallbacks.single().onLost(network)
            val offline = awaitNetwork(NetworkStatus.OFFLINE) { status.value.network }
            supabase.close()

            // Then
            assertEquals(1, registered)
            assertEquals(NetworkStatus.OFFLINE, offline)
            assertEquals(0, shadowOf(manager).networkCallbacks.size)
        }

    /** Waits, in real time, until [count] callbacks are registered; the count then. */
    private suspend fun awaitCallbacks(count: Int): Int =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(WAIT) {
                while (shadowOf(manager).networkCallbacks.size != count) delay(WAIT_STEP)
            }
            shadowOf(manager).networkCallbacks.size
        }

    /** Waits, in real time, until [read] gives [expected]; what it gives then. */
    private suspend fun awaitNetwork(
        expected: NetworkStatus,
        read: () -> NetworkStatus,
    ): NetworkStatus =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(WAIT) { while (read() != expected) delay(WAIT_STEP) }
            read()
        }

    private companion object {
        val WAIT = 5.seconds
        val WAIT_STEP = 5.milliseconds
    }
}
