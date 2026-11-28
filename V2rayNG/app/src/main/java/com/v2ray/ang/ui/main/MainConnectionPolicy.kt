package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.ui.perappproxy.PerAppRoutingMode

/** Keeps an empty allowlist from being presented as a ready-to-connect state. */
internal object MainConnectionPolicy {
    fun canConnect(
        isRunning: Boolean,
        selectedGuid: String?,
        routingMode: PerAppRoutingMode,
        routedAppCount: Int,
    ): Boolean = isRunning || (!selectedGuid.isNullOrEmpty() &&
        (routingMode != PerAppRoutingMode.ONLY_SELECTED || routedAppCount > 0))

    /** A Mozilla profile is a loopback relay, not a durable proxy endpoint. */
    fun shouldOpenFirefoxVpnScreen(
        selectedGuid: String?,
        serviceRunning: Boolean,
        tunnelPort: Int,
    ): Boolean = selectedGuid == AppConfig.FIREFOX_VPN_PROFILE_ID &&
        (!serviceRunning || tunnelPort <= 0)
}
