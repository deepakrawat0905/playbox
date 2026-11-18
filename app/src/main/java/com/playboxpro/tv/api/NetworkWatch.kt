package com.playboxpro.tv.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the device has internet right now. Screens keep showing what is
 * saved on the device while offline; players say "No internet connection"
 * instead of "offline", and start again by themselves when it returns.
 *
 * "Connected to Wi-Fi" is not the same as "has internet": when the router
 * loses its line the Wi-Fi stays up. Android marks a network VALIDATED once
 * it has reached the internet, and drops the mark when it stops doing so,
 * so a network that has validated before and is no longer validated counts
 * as offline. A network that has never validated (some boxes cannot reach
 * Android's check server) is taken at its word, as before.
 */
object NetworkWatch {
    private val state = MutableStateFlow(true)
    val online: StateFlow<Boolean> get() = state

    val isOnline: Boolean get() = state.value

    /**
     * Fires each time internet comes back: the connection returns, or the
     * current network is validated again. Players in an error state retry
     * the same channel on it.
     */
    private val restoredEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val restored: SharedFlow<Unit> get() = restoredEvents

    private var network: Network? = null
    private var validated = false
    private var seenValidated = false

    fun start(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { update(cm.activeNetwork, cm.getNetworkCapabilities(cm.activeNetwork)) }
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Validation usually lands just after this, in onCapabilitiesChanged.
                    runCatching { update(network, cm.getNetworkCapabilities(network)) }
                }

                override fun onLost(network: Network) {
                    // Another network (Wi-Fi / Ethernet / mobile) may already be the
                    // default. While this callback runs Android can still report the
                    // lost network as active, with its old capabilities: ignore it.
                    runCatching {
                        val active = cm.activeNetwork?.takeIf { it != network }
                        update(active, active?.let { cm.getNetworkCapabilities(it) })
                    }.onFailure { update(null, null) }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    update(network, caps)
                }
            })
        }
    }

    @Synchronized
    private fun update(net: Network?, caps: NetworkCapabilities?) {
        if (net != network) {
            network = net
            validated = false
            seenValidated = false
        }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val nowValidated = internet && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (nowValidated) seenValidated = true

        val wasOnline = state.value
        val wasValidated = validated
        validated = nowValidated
        val nowOnline = internet && (nowValidated || !seenValidated)
        state.value = nowOnline

        if (nowOnline && (!wasOnline || (nowValidated && !wasValidated))) restoredEvents.tryEmit(Unit)
    }

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
