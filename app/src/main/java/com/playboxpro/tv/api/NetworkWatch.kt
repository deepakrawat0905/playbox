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

    /**
     * What to tell the user about a failed request. Raw network errors name
     * the server's host and IP ("Unable to resolve host ...", "Failed to
     * connect to .../1.2.3.4"), which is noise to a viewer and not something
     * to put on screen; offline, the only message is [NO_INTERNET].
     */
    fun messageFor(e: Throwable?, fallback: String): String {
        val causes = generateSequence(e) { it.cause }.toList()
        return when {
            !isOnline || causes.any { it is java.net.UnknownHostException } -> NO_INTERNET
            causes.any {
                it is java.net.ConnectException || it is java.net.SocketTimeoutException ||
                    it is java.net.NoRouteToHostException || it is javax.net.ssl.SSLException
            } -> "Unable to reach the server. Please try again."
            else -> e?.message?.takeIf { it.isNotBlank() } ?: fallback
        }
    }
}
