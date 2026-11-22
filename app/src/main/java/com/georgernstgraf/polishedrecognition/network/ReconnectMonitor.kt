package com.georgernstgraf.polishedrecognition.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * #186: the foreground-only reconnect trigger.
 *
 * **No background uploads, no service, no WorkManager** (owner decision): a
 * session recorded offline is transcribed when the network comes back *while
 * the user is looking at the Workbench*, on the next entry into it, or on a
 * manual Retry STT. That is why this is an interface with a `start`/`stop`
 * pair instead of a long-lived observer — the Activity owns its lifetime, so
 * leaving the screen really does stop listening.
 */
interface ReconnectMonitor {

    /**
     * Starts listening; [onValidated] fires **once per reconnect** (the edge
     * from "no validated network" to "validated"), never on a repeated
     * callback for the same network. Idempotent: a second `start` before
     * [stop] replaces the callback instead of registering a second one.
     */
    fun start(onValidated: () -> Unit)

    /** Unregisters the callback. Safe to call when not started. */
    fun stop()
}

/**
 * The production [ReconnectMonitor] over `registerDefaultNetworkCallback`:
 * the default network is the one the app's requests use, so its VALIDATED
 * capability is exactly the runner's gate.
 *
 * The one-shot rule lives in [NetworkGate.reconnectFires] and the state it
 * needs sits here: `previouslyValidated` is updated on EVERY callback
 * (validated or not) and reset on `onLost`. `onAvailable` alone is not enough
 * — a freshly associated Wi-Fi is available before it is validated, which is
 * precisely the captive-portal window an offline session must not spend a
 * request on.
 *
 * Callbacks arrive on a binder thread, not the main thread: the callback is
 * handed the raw event and the *consumer* (the Activity) hops to its own
 * scope. Registering a callback per fragment is explicitly forbidden
 * (PITFALLS): one monitor per visible screen, no exceptions.
 */
class AndroidReconnectMonitor(
    private val connectivity: ConnectivityManager,
    /** The same gate the runner reads — one definition of "online". */
    private val status: NetworkStatus
) : ReconnectMonitor {

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The validated state as of the PREVIOUS callback — the edge detector. */
    @Volatile
    private var previouslyValidated = false

    override fun start(onValidated: () -> Unit) {
        stop()
        // A start mid-connection must not treat the CURRENT state as news:
        // the Workbench's own entry path already recovers on entry, so this
        // only arms the next real transition.
        previouslyValidated = status.isValidated()
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val validated =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val fires = NetworkGate.reconnectFires(validated, previouslyValidated)
                previouslyValidated = validated
                if (fires) onValidated()
            }

            override fun onLost(network: Network) {
                // The default network is gone: the next validated callback is
                // a reconnect again.
                previouslyValidated = false
            }
        }
        callback = registered
        try {
            connectivity.registerDefaultNetworkCallback(registered)
        } catch (_: Throwable) {
            // A SecurityException or a dead ConnectivityManager must never
            // take the screen down: without a monitor the session still heals
            // on the next Workbench entry and on Retry STT.
            callback = null
        }
    }

    override fun stop() {
        val registered = callback ?: return
        callback = null
        try {
            connectivity.unregisterNetworkCallback(registered)
        } catch (_: Throwable) {
            // already unregistered / manager gone — nothing to do
        }
    }
}
