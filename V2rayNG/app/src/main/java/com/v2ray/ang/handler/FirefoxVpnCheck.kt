package com.v2ray.ang.handler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Asks the tunnel whether it really carries traffic.
 *
 * A connection can be announced and still carry nothing, which is exactly what
 * "connected" must never mean. So a real page is fetched through the local
 * relay; only an answer counts, and the screen says which of the two it is.
 */
object FirefoxVpnCheck {

    /** Lightweight pages used to verify that the tunnel really carries traffic. */
    private val CHECK_URLS = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://www.apple.com/library/test/success.html",
    )

    private val base = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** True when a real answer came back through the relay on [localPort]. */
    fun through(localPort: Int): Boolean {
        if (localPort <= 0) return false
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localPort))
        val client = base.newBuilder().proxy(proxy).build()
        for (url in CHECK_URLS) {
            val ok = try {
                val request = Request.Builder()
                    .url(url)
                    .header("Connection", "close")
                    .header("User-Agent", "RXVPN/1.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    response.code in 200..399
                }
            } catch (_: Exception) {
                false
            }
            if (ok) return true
        }
        return false
    }

    /**
     * Waits for the core to come up and then for one real answer. The core needs
     * a moment after it is started, so a failing check is repeated; only the
     * last word counts.
     */
    suspend fun waitUntilCarries(localPort: Int, timeoutMillis: Long = 25_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val ok = withContext(Dispatchers.IO) { through(localPort) }
            if (ok) return true
            delay(1_500)
        }
        return false
    }
}
