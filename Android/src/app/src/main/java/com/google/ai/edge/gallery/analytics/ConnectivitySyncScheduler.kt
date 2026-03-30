package com.google.ai.edge.gallery.analytics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Listens for network connectivity changes and triggers an analytics sync
 * whenever the device comes online. This ensures queued Firebase events
 * are flushed as soon as possible — critical for low-resource environments
 * where connectivity is intermittent.
 */
object ConnectivitySyncScheduler {

    private const val TAG = "ConnectivitySync"
    private var registered = false

    fun register(context: Context) {
        if (registered) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available — triggering analytics sync")
                AnalyticsSyncWorker.syncNow(context)
            }
        })

        registered = true
        Log.d(TAG, "Connectivity listener registered")
    }
}
