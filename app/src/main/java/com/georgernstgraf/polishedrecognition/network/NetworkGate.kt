package com.georgernstgraf.polishedrecognition.network

/**
 * #186: the ONE rule that decides whether a request may go out, and when a
 * reconnect counts as news.
 *
 * Deliberately pure and free of Android types: the app's
 * [AndroidNetworkStatus] supplies "is the network validated right now", the
 * runner and the pipeline ask [shouldAttempt] before any request, and
 * [AndroidReconnectMonitor] asks [reconnectFires] before it calls back. The
 * gate never retries, never buffers and never schedules — it can only say
 * "no", which is what keeps an offline session at zero requests.
 */
object NetworkGate {

    /**
     * May a request be attempted? `online` is the platform's
     * `NET_CAPABILITY_VALIDATED` read (see [AndroidNetworkStatus]) — the same
     * signal the OS uses before it routes traffic, so "validated" is the
     * honest bar: a Wi-Fi network without internet access is not online.
     */
    fun shouldAttempt(online: Boolean): Boolean = online

    /**
     * Is a connectivity callback a RECONNECT — the one-shot edge from "no
     * validated network" to "validated network"?
     *
     * The platform repeats `onCapabilitiesChanged` for the same network
     * (bandwidth updates, captive-portal re-checks), so reacting to the
     * validated flag instead of the edge would fire a recovery run on every
     * repeat. `previouslyValidated` is the state BEFORE this callback: two
     * validated callbacks in a row fire exactly once, and a callback after
     * `onLost` fires again — which is right, the device really did come back.
     */
    fun reconnectFires(validated: Boolean, previouslyValidated: Boolean): Boolean =
        validated && !previouslyValidated
}
