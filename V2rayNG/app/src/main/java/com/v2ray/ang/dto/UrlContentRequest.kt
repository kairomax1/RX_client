package com.v2ray.ang.dto

data class UrlContentRequest(
    val url: String?,
    val timeout: Int = 15000,
    val httpPort: Int = 0,
    /**
     * The core's SOCKS door. Set it, rather than [httpPort], when the tunnel is
     * to carry the request: this build's core listens for SOCKS only, so an
     * HTTP proxy aimed at that port is answered by nobody.
     */
    val socksPort: Int = 0,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val userAgent: String? = null,
    val requestHeaders: String? = null
)