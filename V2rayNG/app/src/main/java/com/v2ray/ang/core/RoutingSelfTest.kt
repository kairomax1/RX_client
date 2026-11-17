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

/**
 * Asks the tunnel what it really does, and says the answer out loud.
 *
 * The rules the core is given can be counted, but counting them is not the same
 * as knowing they work: an advert can still be fetched and a site at home can
 * still be answered from abroad while every rule is written where it should be.
 * So the tunnel is tried, the way a browser tries it — a name, not an address,
 * so that the core has to answer the name itself and then decide where it goes.
 *
 * Three names are enough to tell the whole story: one that must be refused,
 * one that must stay in the country, and one that everyone knows answers.
 */
object RoutingSelfTest {

    private const val TIMEOUT_MS = 6000

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
                append(" adverts: ${advert.refusedOrAnswered("refused", "ANSWERED")}")
                append("; a site at home: ${home.refusedOrAnswered("no answer", "answered")}")
                append("; a site abroad: ${control.refusedOrAnswered("no answer", "answered")}")
                append(". [$advert] [$home] [$control]")
            }
        )
    }

    private fun Answer.refusedOrAnswered(refused: String, answered: String): String =
        if (code == null) refused else "$answered ${code!!}"

    private data class Answer(val host: String, val code: String?, val note: String, val ms: Long) {
        override fun toString(): String = "$host ${code ?: note} in ${ms}ms"
    }

    /**
     * One plain request for [host] over the tunnel, given as a name so that the core
     * answers the name itself. Returns what came back, or why nothing did.
     */
    private fun ask(port: Int, host: String, path: String): Answer {
        val started = SystemClock.elapsedRealtime()
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            Socket(proxy).use { socket ->
                socket.soTimeout = TIMEOUT_MS
                // A name, not an address: the core has to resolve it, which is the
                // only way it can see which rule the request belongs to.
                socket.connect(InetSocketAddress.createUnresolved(host, 80), TIMEOUT_MS)
                socket.getOutputStream().write(
                    ("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n").toByteArray()
                )
                val first = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                Answer(host, first?.take(24)?.trim(), "answered", SystemClock.elapsedRealtime() - started)
            }
        } catch (e: SocketTimeoutException) {
            Answer(host, null, "no answer in ${TIMEOUT_MS}ms", SystemClock.elapsedRealtime() - started)
        } catch (e: Exception) {
            Answer(host, null, "${e.javaClass.simpleName}: ${e.message?.take(60) ?: ""}", SystemClock.elapsedRealtime() - started)
        }
    }
}
