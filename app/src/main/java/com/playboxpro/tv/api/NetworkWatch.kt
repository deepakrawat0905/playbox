package com.playboxpro.tv.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the device has internet right now. Screens keep showing what is
 * saved on the device while offline; players say "No internet connection"
 * instead of "offline", and start again by themselves when it returns.
 */
object NetworkWatch {
    private val state = MutableStateFlow(true)
    val online: StateFlow<Boolean> get() = state

    val isOnline: Boolean get() = state.value

    fun start(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        state.value = currentlyOnline(cm)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { state.value = true }
                override fun onLost(network: Network) { state.value = currentlyOnline(cm) }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    state.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
    }

    private fun currentlyOnline(cm: ConnectivityManager): Boolean = runCatching {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    const val NO_INTERNET = "No internet connection"
}
