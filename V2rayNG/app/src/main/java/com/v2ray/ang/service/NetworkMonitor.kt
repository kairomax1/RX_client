package com.v2ray.ang.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.delay
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** The VPN's underlay must be a physical, unrestricted Internet network, never another VPN. */
internal val NETWORK_MONITOR_REQUIRED_CAPABILITIES = setOf(
    NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
    NetworkCapabilities.NET_CAPABILITY_INTERNET,
    NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
)

/** Only an unrestricted physical Internet network is safe to set as the VPN's own underlay. */
internal fun isEligibleUpstream(hasNotVpn: Boolean, hasInternet: Boolean, hasNotRestricted: Boolean): Boolean =
    hasNotVpn && hasInternet && hasNotRestricted

/** Tracks current-network events independently so handover ordering is deterministic and testable. */
internal class NetworkHandoverState<N : Any> {
    private var current: N? = null
    private var hasSeenUpstream = false

    /** Returns true when a previously observed upstream was replaced or restored after loss. */
    fun onAvailable(network: N): Boolean {
        val changed = hasSeenUpstream && current != network
        current = network
        hasSeenUpstream = true
        return changed
    }

    /** A late loss from an old network must not clear the current one. */
    fun onLost(network: N): Boolean {
        if (current != network) return false
        current = null
        return true
    }

    fun isCurrent(network: N): Boolean = current == network

    fun reset() {
        current = null
        hasSeenUpstream = false
    }
}

/**
 * Watches the network that carries the tunnel and reports topology changes.
 *
 * Cellular -> Wi-Fi is a make-before-break handover: the new network is announced while the old one
 * is still connected, so the socket to the server is never reset and the core keeps using a dead
 * connection. Deciding that a handover happened is what this class is for, acting on it is not.
 *
 * Only used from Android P and above, see CoreServiceManager.startNetworkMonitor().
 * [onHandover] is invoked after the debounce window and must hand off long work to its owner scope.
 */
class NetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val onUnderlyingNetworksChanged: (Array<Network>?) -> Unit,
    private val onHandover: () -> Unit,
) {
    private companion object {
        const val HANDOVER_DEBOUNCE_MS = 1500L
    }

    private val stateLock = Any()
    private val upstreamState = NetworkHandoverState<Network>()
    private val handoverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handoverJob: Job? = null
    private var registered = false

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private val request by lazy {
        NetworkRequest.Builder().apply {
            // This callback runs from inside the VPN service. Excluding VPN overlays prevents the
            // app from selecting its own TUN as the physical underlay and routing the tunnel into itself.
            NETWORK_MONITOR_REQUIRED_CAPABILITIES.forEach { addCapability(it) }
        }.build()
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(stateLock) {
                if (!registered) return@synchronized
                // requestNetwork only reports networks matching NETWORK_MONITOR_REQUIRED_CAPABILITIES.
                // Track the callback order now; wait for capabilities before setting the underlay.
                val changed = upstreamState.onAvailable(network)
                if (changed) scheduleHandoverLocked(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(stateLock) {
                // A late callback from a network already replaced must not restore it.
                if (!registered || !upstreamState.isCurrent(network)) return@synchronized
                val eligible = isEligibleUpstream(
                    hasNotVpn = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                    hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    hasNotRestricted = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
                )
                if (!eligible) {
                    LogUtil.d(AppConfig.TAG, "NetworkMonitor: Ignoring non-physical or restricted network $network")
                    return@synchronized
                }
                onUnderlyingNetworksChanged(arrayOf(network))
            }
        }

        override fun onLost(network: Network) {
            val wasCurrent = synchronized(stateLock) {
                if (!registered) {
                    null
                } else if (!upstreamState.onLost(network)) {
                    false
                } else {
                    handoverJob?.cancel()
                    handoverJob = null
                    onUnderlyingNetworksChanged(null)
                    true
                }
            }
            if (wasCurrent == false) {
                LogUtil.d(AppConfig.TAG, "NetworkMonitor: Ignoring loss of stale upstream $network")
            }
        }
    }

    /**
     * Starts watching. Safe to call more than once, only the first call registers.
     */
    fun register() {
        val shouldRegister = synchronized(stateLock) {
            if (registered) false else {
                registered = true
                true
            }
        }
        if (!shouldRegister) return
        try {
            connectivity.requestNetwork(request, callback)
        } catch (e: Exception) {
            synchronized(stateLock) {
                registered = false
                handoverJob?.cancel()
                handoverJob = null
                upstreamState.reset()
                handoverScope.coroutineContext.cancelChildren()
            }
            LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to request network", e)
        }
    }

    /**
     * Stops watching and drops the tracked state. Safe to call more than once.
     */
    fun unregister() {
        val shouldUnregister = synchronized(stateLock) {
            val wasRegistered = registered
            registered = false
            handoverJob?.cancel()
            handoverJob = null
            upstreamState.reset()
            handoverScope.coroutineContext.cancelChildren()
            wasRegistered
        }
        if (!shouldUnregister) return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "NetworkMonitor: Failed to unregister callback", e)
        }
    }

    /** Must be called while holding [stateLock]. */
    private fun scheduleHandoverLocked(network: Network) {
        LogUtil.i(AppConfig.TAG, "NetworkMonitor: Upstream is now $network")
        handoverJob?.cancel()
        handoverJob = handoverScope.launch {
            try {
                delay(HANDOVER_DEBOUNCE_MS)
                val ownJob = currentCoroutineContext()[Job]
                synchronized(stateLock) {
                    if (registered && upstreamState.isCurrent(network) && handoverJob === ownJob) {
                        handoverJob = null
                        onHandover()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "NetworkMonitor: Failed to handle upstream change", e)
            }
        }
    }
}
