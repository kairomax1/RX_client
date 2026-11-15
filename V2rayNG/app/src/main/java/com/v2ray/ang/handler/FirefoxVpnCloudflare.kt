package com.v2ray.ang.handler

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An optional way in through Cloudflare.
 *
 * On some networks the address of Mozilla's proxy cannot be reached from the
 * phone at all. A small Cloudflare Worker can reach it instead: the phone keeps
 * a WebSocket to the Worker, the Worker opens the plain TCP connection to the
 * proxy, and bytes are carried between them untouched. The proxy still speaks
 * its own CONNECT with the bearer inside that pipe, so nothing else changes.
 *
 * Every installation carries its own random key, which is written into the
 * Worker's code: a Worker that has not been given that key answers nothing.
 */
object FirefoxVpnCloudflare {

    private const val PREFS = "firefox_vpn_prefs"
    private const val KEY_ENABLED = "cloudflare_relay_enabled"
    private const val KEY_ADDRESS = "cloudflare_relay_address"
    private const val KEY_SECRET = "cloudflare_relay_secret"

    private const val UA = "Mozilla/5.0 (Android 15; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ── Settings ─────────────────────────────────────────────────

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun address(context: Context): String = prefs(context).getString(KEY_ADDRESS, "").orEmpty()

    fun setAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_ADDRESS, address.trim()).apply()
    }

    /** The address to use, or null when the way through Cloudflare is not set up. */
    fun relayAddressOrNull(context: Context): String? {
        if (!isEnabled(context)) return null
        val address = address(context)
        return if (address.isEmpty()) null else address
    }

    /** This installation's own key; made once and kept. */
    fun secret(context: Context): String {
        val saved = prefs(context).getString(KEY_SECRET, null)
        if (!saved.isNullOrEmpty()) return saved
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        val made = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        prefs(context).edit().putString(KEY_SECRET, made).apply()
        return made
    }

    /**
     * The Worker's code, with this installation's key already inside it, so two
     * users never share a relay and the address cannot be found by chance.
     */
    fun workerCode(context: Context): String = WORKER.replace("__KEY__", secret(context))

    // ── The pipe ─────────────────────────────────────────────────

    /** One WebSocket turned into a pair of streams the relay can copy between. */
    class Tunnel internal constructor(
        private val socket: WebSocket,
        val input: InputStream,
        val output: OutputStream
    ) {
        fun close() {
            try {
                socket.close(1000, "done")
            } catch (_: Exception) {
            }
            try {
                socket.cancel()
            } catch (_: Exception) {
            }
        }
    }

    /** Opens a Worker pipe to [host]:[port]; null when it does not answer. */
    fun openTunnel(address: String, context: Context, host: String, port: Int): Tunnel? {
        val url = webSocketUrl(address, secret(context), host, port) ?: return null
        val room = LinkedBlockingQueue<ByteArray>()
        val opened = CountDownLatch(1)
        var failure: String? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val data = bytes.toByteArray()
                if (data.isNotEmpty()) room.offer(data)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                room.offer(CLOSED)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                room.offer(CLOSED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                failure = t.message ?: "the relay closed the pipe"
                opened.countDown()
                room.offer(CLOSED)
            }
        }

        val socket = try {
            val request = Request.Builder().url(url).header("User-Agent", UA).build()
            client.newWebSocket(request, listener)
        } catch (e: Exception) {
            AppLog.error(AppLog.Section.Connection, "Cloudflare relay: ${e.message ?: "bad address"}")
            return null
        }
        if (!opened.await(25, TimeUnit.SECONDS)) {
            socket.cancel()
            AppLog.error(AppLog.Section.Connection, "Cloudflare relay did not answer")
            return null
        }
        if (failure != null) {
            AppLog.error(AppLog.Section.Connection, "Cloudflare relay: $failure")
            return null
        }
        AppLog.info(AppLog.Section.Connection, "Cloudflare relay carries the tunnel to $host:$port")
        return Tunnel(socket, Incoming(room), Outgoing(socket))
    }

    private fun webSocketUrl(address: String, secret: String, host: String, port: Int): String? {
        val trimmed = address.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val base = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            else -> "wss://$trimmed"
        }
        val separator = if (base.contains('?')) '&' else '?'
        return "$base$separator" + "k=" + secret + "&h=" + host + "&p=" + port
    }

    private val CLOSED = ByteArray(0)

    private class Incoming(private val room: LinkedBlockingQueue<ByteArray>) : InputStream() {
        private var chunk: ByteArray = CLOSED
        private var offset = 0
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (offset >= chunk.size) {
                if (ended) return -1
                val next = room.take()
                if (next === CLOSED || next.isEmpty()) {
                    ended = true
                    return -1
                }
                chunk = next
                offset = 0
            }
            val count = minOf(len, chunk.size - offset)
            System.arraycopy(chunk, offset, b, off, count)
            offset += count
            return count
        }

        override fun available(): Int = if (offset >= chunk.size) 0 else chunk.size - offset
    }

    private class Outgoing(private val socket: WebSocket) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            socket.send(b.toByteString(off, len))
        }

        override fun flush() {}
    }

    /**
     * The Worker, ready to paste into Cloudflare's editor. It opens a plain TCP
     * connection to the address asked for in `h`/`p` and carries every byte.
     * JS template literals are avoided so the text survives any editor.
     */
    private val WORKER = """
        import { connect } from 'cloudflare:sockets';

        // RX VPN relay - this key belongs to one phone only.
        const KEY = '__KEY__';

        export default {
          async fetch(request) {
            const url = new URL(request.url);
            if (url.searchParams.get('k') !== KEY) {
              return new Response('not found', { status: 404 });
            }
            if ((request.headers.get('Upgrade') || '').toLowerCase() !== 'websocket') {
              return new Response('relay is up', { status: 200 });
            }
            const host = url.searchParams.get('h');
            const port = parseInt(url.searchParams.get('p') || '0', 10);
            if (!host || !port || port === 25) {
              return new Response('bad target', { status: 400 });
            }
            const pair = new WebSocketPair();
            const client = pair[0];
            const server = pair[1];
            server.accept();

            let socket;
            try {
              socket = connect({ hostname: host, port: port });
            } catch (e) {
              return new Response('cannot connect', { status: 502 });
            }

            const writer = socket.writable.getWriter();
            server.addEventListener('message', function (event) {
              if (typeof event.data === 'string') return;
              writer.write(new Uint8Array(event.data)).catch(function () {});
            });
            server.addEventListener('close', function () {
              try { writer.close(); } catch (e) {}
            });

            const reader = socket.readable.getReader();
            (async function () {
              try {
                for (;;) {
                  const chunk = await reader.read();
                  if (chunk.done) break;
                  server.send(chunk.value);
                }
              } catch (e) {}
              try { server.close(1000, 'closed'); } catch (e) {}
            })();

            return new Response(null, { status: 101, webSocket: client });
          }
        };
    """.trimIndent()
}
