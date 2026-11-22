package com.example.neptuno.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkStatus(
    val isConnected: Boolean,
    val transportLabel: String
)

/**
 * Monitor real de conectividad a Internet basado en ConnectivityManager (Android 10+ / API 29+).
 */
open class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    open fun observeNetworkStatus(): Flow<NetworkStatus> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(NetworkStatus(isConnected = false, transportLabel = "Sin servicio de red"))
            awaitClose { }
            return@callbackFlow
        }

        fun currentStatus(): NetworkStatus {
            val activeNetwork = manager.activeNetwork ?: return NetworkStatus(false, "Sin conexión")
            val caps = manager.getNetworkCapabilities(activeNetwork)
                ?: return NetworkStatus(false, "Sin conexión")
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Datos móviles"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Red activa"
            }
            return NetworkStatus(
                isConnected = hasInternet,
                transportLabel = if (hasInternet) transport else "Sin conexión"
            )
        }

        trySend(currentStatus())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentStatus())
            }

            override fun onLost(network: Network) {
                trySend(currentStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(currentStatus())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure { trySend(NetworkStatus(false, "No disponible")) }

        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}
