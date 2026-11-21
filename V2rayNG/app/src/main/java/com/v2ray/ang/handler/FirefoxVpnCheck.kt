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

    /** A page that answers 204 and nothing else: no body, no cache, no ads. */
    private const val CHECK_URL = "https://www.gstatic.com/generate_204"

    private val base = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** True when a real answer came back through the relay on [localPort]. */
    fun through(localPort: Int): Boolean {
        if (localPort <= 0) return false
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localPort))
            val client = base.newBuilder().proxy(proxy).build()
            val request = Request.Builder()
                .url(CHECK_URL)
                .header("Connection", "close")
                .header("User-Agent", "RXVPN/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                response.code in 200..399
            }
        } catch (_: Exception) {
            false
        }
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
