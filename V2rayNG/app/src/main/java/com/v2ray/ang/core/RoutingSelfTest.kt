package com.v2ray.ang.core

import android.os.SystemClock
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.SettingsManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Asks the tunnel what it really does, and says the answer out loud.
 *
 * The rules the core is given can be counted, but counting them is not the same
 * as knowing they work: an advert can still be fetched and a site at home can
 * still be answered from abroad while every rule is written where it should be.
 * So the tunnel is tried the way a browser tries it — a name, not an address,
 * over a TLS handshake that carries the name in the clear — so the core has to
 * answer the name itself and then decide where the request goes.
 *
 * Three names tell the whole story: one that must be refused, one that must be
 * answered from the phone's own line, and one that answers for everyone.
 */
object RoutingSelfTest {

    private const val TIMEOUT_MS = 10_000

    /** An advert. Every rule in the book says this one is not fetched. */
    private const val ADVERT_HOST = "pagead2.googlesyndication.com"

    /** A site at home. It has to be answered from the phone's own line. */
    private const val HOME_HOST = "www.digikala.com"

    /** A site that answers for everyone, for comparison. */
    private const val CONTROL_HOST = "www.gstatic.com"

    /**
     * Tries the three names and writes what happened, in one line, into the log.
     * Meant to be called off the main thread, a moment after the core started.
     */
    fun run() {
        val port = SettingsManager.getSocksPort()
        val advert = ask(port, ADVERT_HOST, "/pagead/js/adsbygoogle.js")
        val home = ask(port, HOME_HOST, "/")
        val control = ask(port, CONTROL_HOST, "/generate_204")

        AppLog.info(
            AppLog.Section.Core,
            buildString {
                append("Routing test on the tunnel's own port $port:")
                append(" adverts: ${advert.said("refused", "ANSWERED")}")
                append("; a site at home: ${home.said("no answer", "answered")}")
                append("; a site abroad: ${control.said("no answer", "answered")}")
                append(". [$advert] [$home] [$control]")
            }
        )
    }

    private fun Answer.said(nothing: String, something: String): String =
        if (code == null) nothing else "$something ${code!!}"

    private data class Answer(val host: String, val code: String?, val note: String, val ms: Long) {
        override fun toString(): String = "$host ${code ?: note} in ${ms}ms"
    }

    /**
     * One HTTPS request for [host] over the tunnel, given as a name so that the
     * core answers the name itself. Returns what came back, or why nothing did.
     */
    private fun ask(port: Int, host: String, path: String): Answer {
        val started = SystemClock.elapsedRealtime()
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            Socket(proxy).use { plain ->
                plain.soTimeout = TIMEOUT_MS
                // A name, not an address: the core has to resolve it, which is the
                // only way it can see which rule the request belongs to.
                plain.connect(InetSocketAddress.createUnresolved(host, 443), TIMEOUT_MS)
                val tls = SSLSocketFactory.getDefault().createSocket(plain, host, 443, true) as SSLSocket
                tls.soTimeout = TIMEOUT_MS
                tls.startHandshake()
                tls.getOutputStream().write(
                    ("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n").toByteArray()
                )
                val first = BufferedReader(InputStreamReader(tls.inputStream)).readLine()
                Answer(host, first?.take(28)?.trim(), "answered", SystemClock.elapsedRealtime() - started)
            }
        } catch (e: SocketTimeoutException) {
            Answer(host, null, "no answer in ${TIMEOUT_MS}ms", SystemClock.elapsedRealtime() - started)
        } catch (e: Exception) {
            Answer(
                host,
                null,
                "${e.javaClass.simpleName}: ${e.message?.take(60) ?: ""}",
                SystemClock.elapsedRealtime() - started
            )
        }
    }
}
