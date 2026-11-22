package com.georgernstgraf.polishedrecognition.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * #186: "does the device have a network the OS is willing to route over?"
 *
 * The app had no `ConnectivityManager` usage before this (measured: grep over
 * `app`, `core`, `analyzer`), so this is the single place that asks. It is an
 * interface because the answer must be replaceable in a test — the real one
 * needs a live `ConnectivityManager`, and no test may depend on the host's
 * network state.
 *
 * The check is `activeNetwork → getNetworkCapabilities →
 * NET_CAPABILITY_VALIDATED`: VALIDATED (not merely CONNECTED) is the bar,
 * because a captive portal or a Wi-Fi without upstream still connects and
 * would turn an offline dictation into a wasted round-trip.
 */
interface NetworkStatus {
    fun isValidated(): Boolean
}

/**
 * The production [NetworkStatus] — a thin, side-effect-free read.
 *
 * Every null step means `false`: no active network, or a race where the
 * network went away between the two calls. Being wrong in that direction is
 * harmless (the fragment is saved and retried later); being wrong in the
 * other direction would send a request into a void.
 */
class AndroidNetworkStatus(
    private val connectivity: ConnectivityManager
) : NetworkStatus {

    override fun isValidated(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
