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

    /**
     * Asks the deployed Worker whether it knows this phone's key. Returns a
     * short verdict for the screen: "ready", "wrong-key", "missing",
     * "refused: …" when the Worker answered but Mozilla's proxy did not take
     * the tunnel, "error: …" when the address itself is wrong.
     *
     * With [proxyHost] and [token] the Worker is asked for a real tunnel too,
     * so "ready" means traffic went through and not only that the code is
     * deployed.
     */
    fun check(
        address: String,
        context: Context,
        proxyHost: String = "",
        proxyPort: Int = 443,
        token: String = ""
    ): String {
        val trimmed = address.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "missing"
        val base = when {
            trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("http://") -> trimmed
            trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
            trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
            else -> "https://$trimmed"
        }
        val separator = if (base.contains('?')) '&' else '?'
        val url = buildString {
            append(base).append(separator).append("k=").append(secret(context))
            if (proxyHost.isNotEmpty() && token.isNotEmpty()) {
                append("&probe=1")
                append("&h=").append(android.net.Uri.encode(proxyHost))
                append("&p=").append(proxyPort)
                append("&t=").append(android.net.Uri.encode(PROBE_TARGET))
                append("&a=").append(android.net.Uri.encode(token))
                append("&hh=").append(android.net.Uri.encode(PROBE_TARGET))
                append("&u=1")
            }
        }
        return try {
            val request = Request.Builder().url(url).header("User-Agent", UA).get().build()
            client.newCall(request).execute().use { response ->
                val body = try {
                    response.body?.string().orEmpty().trim()
                } catch (_: Exception) {
                    ""
                }
                when (response.code) {
                    200 -> when {
                        body.startsWith("OK") -> "ready"
                        body.startsWith("ERR") -> "refused: " + body.removePrefix("ERR").trim()
                        body.isEmpty() -> "ready"
                        else -> "refused: $body"
                    }
                    401 -> "wrong-key"
                    404 -> "missing"
                    else -> "error: HTTP ${response.code}"
                }
            }
        } catch (e: Exception) {
            "error: ${e.message ?: "no answer"}"
        }
    }

    /** The target the Worker is asked to open when the relay is checked. */
    private const val PROBE_TARGET = "www.gstatic.com:443"

    /** Why the last pipe through the Worker was refused; null when one opened. */
    @Volatile
    var problem: String? = null
        private set

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

    /**
     * Opens a Worker pipe that is already connected to [target].
     *
     * The Worker makes the TLS connection to the proxy, sends the CONNECT with
     * the bearer itself and answers `OK` before carrying anything, so the
     * relay here only copies bytes. Null when it does not answer.
     */
    fun openTunnel(
        address: String,
        context: Context,
        proxyHost: String,
        proxyPort: Int,
        target: String,
        token: String,
        hostHeader: String,
        withAgent: Boolean
    ): Tunnel? {
        val url = webSocketUrl(
            address, secret(context), proxyHost, proxyPort, target, token, hostHeader, withAgent
        ) ?: return null
        val room = LinkedBlockingQueue<ByteArray>()
        val opened = CountDownLatch(1)
        val answered = CountDownLatch(1)
        var failure: String? = null
        var answer: String? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                opened.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (answer == null) {
                    answer = text
                    answered.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                val data = bytes.toByteArray()
                if (data.isNotEmpty()) room.offer(data)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                room.offer(CLOSED)
                answered.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                room.offer(CLOSED)
                answered.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                failure = t.message ?: "the relay closed the pipe"
                opened.countDown()
                answered.countDown()
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
            problem = "the Worker did not answer"
            AppLog.error(AppLog.Section.Connection, "Cloudflare relay did not answer")
            return null
        }
        if (failure != null) {
            val message = failure.orEmpty()
            problem = if (message.contains("401")) {
                "the Worker holds another phone's key"
            } else {
                message
            }
            AppLog.error(
                AppLog.Section.Connection,
                if (message.contains("401")) {
                    "Cloudflare relay: the Worker holds another phone's key; copy the code again and Deploy"
                } else {
                    "Cloudflare relay: $message"
                }
            )
            socket.cancel()
            return null
        }
        if (!answered.await(45, TimeUnit.SECONDS)) {
            socket.cancel()
            problem = "the relay did not answer in time"
            AppLog.error(AppLog.Section.Connection, "Cloudflare relay: the proxy did not answer in time")
            return null
        }
        val verdict = answer.orEmpty()
        if (!verdict.startsWith("OK")) {
            problem = verdict.removePrefix("ERR ").ifEmpty { "the relay refused without a word" }
            AppLog.error(AppLog.Section.Connection, "Cloudflare relay: the proxy refused $problem")
            socket.cancel()
            return null
        }
        problem = null
        AppLog.info(AppLog.Section.Connection, "Cloudflare relay carries the tunnel to $target")
        return Tunnel(socket, Incoming(room), Outgoing(socket))
    }

    private fun webSocketUrl(
        address: String,
        secret: String,
        proxyHost: String,
        proxyPort: Int,
        target: String,
        token: String,
        hostHeader: String,
        withAgent: Boolean
    ): String? {
        val trimmed = address.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val base = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
            else -> "wss://$trimmed"
        }
        val separator = if (base.contains('?')) '&' else '?'
        return base + separator +
            "k=" + secret +
            "&h=" + android.net.Uri.encode(proxyHost) +
            "&p=" + proxyPort +
            "&t=" + android.net.Uri.encode(target) +
            "&a=" + android.net.Uri.encode(token) +
            "&hh=" + android.net.Uri.encode(hostHeader) +
            "&u=" + if (withAgent) "1" else "0"
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
     * The Worker, ready to paste into Cloudflare's editor.
     *
     * It makes the TLS connection to the proxy, sends the CONNECT with the
     * bearer itself, answers `OK` once the proxy agrees, and then carries every
     * byte in both directions. Doing the TLS there is the point: this phone
     * cannot upgrade a WebSocket to TLS by itself.
     */
    private val WORKER = """
        import { connect } from 'cloudflare:sockets';

        // RX VPN relay - this key belongs to one phone only.
        const KEY = '__KEY__';
        const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0';

        function joinBytes(first, second) {
          const merged = new Uint8Array(first.length + second.length);
          merged.set(first, 0);
          merged.set(second, first.length);
          return merged;
        }

        function endOfHead(bytes) {
          for (let i = 3; i < bytes.length; i++) {
            if (bytes[i] === 10 && bytes[i - 1] === 13 && bytes[i - 2] === 10 && bytes[i - 3] === 13) {
              return i + 1;
            }
          }
          return -1;
        }

        // Opens the TLS connection to Mozilla's proxy, sends the CONNECT and
        // reads its answer. Used both by the pipe and by the plain check, so
        // what the phone is told was really asked of the proxy.
        async function openProxy(host, port, target, token, hostHeader, withAgent) {
          const socket = connect({ hostname: host, port: port }, { secureTransport: 'on' });
          const writer = socket.writable.getWriter();
          const reader = socket.readable.getReader();
          let head =
            'CONNECT ' + target + ' HTTP/1.1\r\n' +
            'Host: ' + hostHeader + '\r\n' +
            'Proxy-Authorization: Bearer ' + token + '\r\n';
          if (withAgent) {
            head += 'User-Agent: ' + UA + '\r\n';
          }
          head += '\r\n';
          await writer.write(new TextEncoder().encode(head));
          let buffer = new Uint8Array(0);
          let end = -1;
          while (end < 0) {
            const chunk = await reader.read();
            if (chunk.done) {
              throw new Error('the proxy closed the connection');
            }
            buffer = joinBytes(buffer, new Uint8Array(chunk.value));
            end = endOfHead(buffer);
            if (end < 0 && buffer.length > 32768) {
              throw new Error('the proxy answer is too long');
            }
          }
          return {
            socket: socket,
            writer: writer,
            reader: reader,
            buffer: buffer,
            end: end,
            answer: new TextDecoder().decode(buffer.slice(0, end)),
          };
        }

        export default {
          async fetch(request) {
            const url = new URL(request.url);
            if (url.searchParams.get('k') !== KEY) {
              return new Response('another phone key', { status: 401 });
            }
            const host = url.searchParams.get('h');
            const port = parseInt(url.searchParams.get('p') || '0', 10);
            const target = url.searchParams.get('t');
            const token = url.searchParams.get('a');
            const hostHeader = url.searchParams.get('hh') || target;
            const withAgent = url.searchParams.get('u') === '1';
            if (!host || !port || !target || !token) {
              return new Response('relay is up', { status: 200 });
            }

            // A plain visit with an address and a pass asks for a real tunnel:
            // the answer is what Mozilla's proxy said, word for word, so the
            // phone is told the truth instead of only 'the relay is there'.
            if ((request.headers.get('Upgrade') || '').toLowerCase() !== 'websocket' &&
                url.searchParams.get('probe') === '1') {
              try {
                const first = await openProxy(host, port, target, token, hostHeader, withAgent);
                try { first.socket.close(); } catch (e) {}
                if (first.answer.indexOf(' 200') < 0) {
                  return new Response('ERR ' + first.answer.split('\r\n')[0], { status: 200 });
                }
                return new Response('OK ' + first.answer.split('\r\n')[0], { status: 200 });
              } catch (e) {
                const reason = e && e.message ? e.message : 'cannot connect';
                return new Response('ERR ' + reason, { status: 200 });
              }
            }

            if ((request.headers.get('Upgrade') || '').toLowerCase() !== 'websocket') {
              return new Response('relay is up', { status: 200 });
            }

            const pair = new WebSocketPair();
            const client = pair[0];
            const server = pair[1];
            server.accept();

            let socket;
            let writer;
            let reader;
            try {
              socket = connect({ hostname: host, port: port }, { secureTransport: 'on' });
              writer = socket.writable.getWriter();
              reader = socket.readable.getReader();

              let head =
                'CONNECT ' + target + ' HTTP/1.1\r\n' +
                'Host: ' + hostHeader + '\r\n' +
                'Proxy-Authorization: Bearer ' + token + '\r\n';
              if (withAgent) {
                head += 'User-Agent: ' + UA + '\r\n';
              }
              head += '\r\n';
              await writer.write(new TextEncoder().encode(head));

              let buffer = new Uint8Array(0);
              let end = -1;
              while (end < 0) {
                const chunk = await reader.read();
                if (chunk.done) {
                  throw new Error('the proxy closed the connection');
                }
                buffer = joinBytes(buffer, new Uint8Array(chunk.value));
                end = endOfHead(buffer);
                if (end < 0 && buffer.length > 32768) {
                  throw new Error('the proxy answer is too long');
                }
              }

              const answer = new TextDecoder().decode(buffer.slice(0, end));
              if (answer.indexOf(' 200') < 0) {
                server.send('ERR ' + answer.split('\r\n')[0]);
                server.close(1000, 'refused');
                try { socket.close(); } catch (e) {}
                return new Response(null, { status: 101, webSocket: client });
              }

              server.send('OK');
              const rest = buffer.slice(end);
              if (rest.length > 0) {
                server.send(rest);
              }

              let chain = Promise.resolve();
              server.addEventListener('message', function (event) {
                if (typeof event.data === 'string') return;
                const bytes = new Uint8Array(event.data);
                chain = chain.then(function () {
                  return writer.write(bytes);
                }).catch(function () {});
              });
              server.addEventListener('close', function () {
                try { socket.close(); } catch (e) {}
              });

              (async function () {
                try {
                  for (;;) {
                    const chunk = await reader.read();
                    if (chunk.done) break;
                    server.send(chunk.value);
                  }
                } catch (e) {}
                try { server.close(1000, 'done'); } catch (e) {}
              })();

              return new Response(null, { status: 101, webSocket: client });
            } catch (e) {
              const reason = e && e.message ? e.message : 'cannot connect';
              try { server.send('ERR ' + reason); } catch (err) {}
              try { server.close(1000, 'failed'); } catch (err) {}
              return new Response(null, { status: 101, webSocket: client });
            }
          }
        };
    """.trimIndent()
}
