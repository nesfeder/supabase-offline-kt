// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the device has a usable network, from [context]'s `ConnectivityManager`: `true` while a
 * network with internet access that Android has validated is up. A cold flow: collecting it
 * registers a network callback, and ending the collection unregisters it. Needs the
 * `ACCESS_NETWORK_STATE` permission, which the library's manifest declares.
 *
 * @return the flow, starting with the state at collection; empty when the device has no
 *   `ConnectivityManager`.
 */
internal fun connectivityManagerNetworkAvailability(context: Context): Flow<Boolean> =
    callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            close()
            return@callbackFlow
        }
        // Every network with internet, and whether each is usable: online while any is
        val usable = HashMap<Network, Boolean>()
        fun publish() {
            trySend(usable.values.any { it })
        }
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(usable) {
                        usable[network] = manager.getNetworkCapabilities(network).isUsable()
                        publish()
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    synchronized(usable) {
                        usable[network] = capabilities.isUsable()
                        publish()
                    }
                }

                override fun onLost(network: Network) {
                    synchronized(usable) {
                        usable.remove(network)
                        publish()
                    }
                }
            }
        synchronized(usable) {
            manager.activeNetwork?.let { network ->
                usable[network] = manager.getNetworkCapabilities(network).isUsable()
            }
            publish()
        }
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        manager.registerNetworkCallback(request, callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }
    .distinctUntilChanged()

/** Whether these capabilities make a usable network: internet access, validated by Android. */
private fun NetworkCapabilities?.isUsable(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
