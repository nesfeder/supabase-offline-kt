// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

// --8<-- [start:connectivity-signal]
/**
 * Whether the device's default network reaches the internet, as Android has validated it: the
 * app's own connectivity signal, handed to the plugin as `networkAvailability`. An app with a
 * signal of its own passes it, so the app and the plugin never disagree about being online.
 */
fun Context.validatedInternet(): Flow<Boolean> =
    callbackFlow {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        trySend(capabilities.isValidatedInternet())
                    }

                    override fun onLost(network: Network) {
                        trySend(false)
                    }
                }
            val current = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            trySend(current?.isValidatedInternet() == true)
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }
        .distinctUntilChanged()

/** Whether this network has internet that Android has checked it can reach. */
private fun NetworkCapabilities.isValidatedInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
// --8<-- [end:connectivity-signal]
