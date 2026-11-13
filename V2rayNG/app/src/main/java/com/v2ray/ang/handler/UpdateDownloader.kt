package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Gets the update APK onto the phone on a difficult network.
 *
 * - Several ways to the same file, tried one after another: through the app's own tunnel when it
 *   is up, straight to GitHub, then public GitHub mirrors.
 * - The file is fetched in a few pieces at once, which is markedly faster on throttled lines.
 * - Nothing already received is thrown away: every piece continues from where it stopped, also
 *   when the way changes or the app is closed in between.
 * - The finished file must have the published size and checksum, otherwise it is discarded. On
 *   top of that Android itself refuses an APK that is not signed with this app's key, so a
 *   mirror cannot slip in something else.
 */
object UpdateDownloader {

    data class Progress(val bytes: Long, val total: Long, val bytesPerSecond: Long) {
        val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private data class Route(val name: String, val url: String, val proxyPort: Int = 0)

    private class RangeNotSupported : Exception()

    private const val SEGMENTS = 4
    private const val MIN_SEGMENTED_BYTES = 2L * 1024 * 1024
    private const val SEGMENT_RETRIES = 3
    private const val ROUNDS = 2
    private const val BUFFER = 64 * 1024

    /** Public front doors to GitHub files. Only used when the checksum of the file is known. */
    private val MIRRORS = listOf("https://ghfast.top/", "https://gh-proxy.com/")

    fun targetFile(context: Context, version: String): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        return File(dir, "RX-VPN-$version.apk")
    }

    /**
     * Returns the finished APK, or null when no way worked. [size] and [sha256] come from the
     * release; either may be unknown (0 / null).
     */
    suspend fun download(
        context: Context,
        version: String,
        url: String,
        size: Long = 0L,
        sha256: String? = null,
        onProgress: (Progress) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val target = targetFile(context, version)
        if (target.length() > 0 && (size <= 0 || target.length() == size)) {
            onProgress(Progress(target.length(), target.length(), 0))
            return@withContext target
        }
        target.delete()
        // Pieces of other versions are of no use any more.
        target.parentFile?.listFiles()?.filter { !it.name.startsWith(target.name) }?.forEach { it.delete() }

        val routes = routes(url, mirrorsAllowed = sha256 != null)
        repeat(ROUNDS) { round ->
            for (route in routes) {
                coroutineContext.ensureActive()
                try {
                    val ok = if (size >= MIN_SEGMENTED_BYTES) {
                        try {
                            segmented(route, target, size, onProgress)
                        } catch (e: RangeNotSupported) {
                            single(route, target, size, onProgress)
                        }
                    } else {
                        single(route, target, size, onProgress)
                    }
                    if (ok && verify(target, size, sha256)) return@withContext target
                    if (ok) {
                        // Complete but not the published file: nothing of it can be trusted.
                        LogUtil.w(AppConfig.TAG, "Update download: ${route.name} delivered a wrong file")
                        cleanup(target)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.w(AppConfig.TAG, "Update download: ${route.name} failed in round $round: ${e.message}")
                }
            }
        }
        null
    }

    private fun routes(url: String, mirrorsAllowed: Boolean): List<Route> {
        val list = ArrayList<Route>()
        val port = runCatching { SettingsManager.getHttpPort() }.getOrDefault(0)
        // With the tunnel up, the local proxy is the way most likely to reach GitHub.
        if (port > 0 && isListening(port)) list += Route("tunnel", url, port)
        list += Route("direct", url)
        if (mirrorsAllowed) MIRRORS.forEachIndexed { i, mirror -> list += Route("mirror${i + 1}", mirror + url) }
        return list
    }

    private fun isListening(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (e: Exception) {
        false
    }

    private fun client(route: Route): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        if (route.proxyPort > 0) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", route.proxyPort)))
            val user = SettingsManager.getSocksUsername().orEmpty()
            if (user.isNotEmpty()) {
                val credential = Credentials.basic(user, SettingsManager.getSocksPassword().orEmpty())
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            }
        }
        return builder.build()
    }

    private fun partFile(target: File, index: Int) = File(target.parentFile, "${target.name}.s$index")

    /** Several ranges at once, each into its own resumable piece, joined at the end. */
    private suspend fun segmented(route: Route, target: File, size: Long, onProgress: (Progress) -> Unit): Boolean =
        coroutineScope {
            val ranges = UpdateRelease.segments(size, SEGMENTS)
            val parts = ranges.indices.map { partFile(target, it) }
            // A piece longer than its range can only be left over from something else.
            parts.forEachIndexed { i, file ->
                if (file.length() > ranges[i].last - ranges[i].first + 1) file.delete()
            }
            val done = AtomicLong(parts.sumOf { it.length() })
            val http = client(route)
            val meter = launch { meter(done, size, onProgress) }
            try {
                ranges.indices.map { i ->
                    async(Dispatchers.IO) {
                        var attempt = 0
                        while (true) {
                            try {
                                fetchRange(http, route.url, ranges[i], parts[i], done)
                                break
                            } catch (e: RangeNotSupported) {
                                throw e
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (++attempt >= SEGMENT_RETRIES) throw e
                                delay(600L * attempt)
                            }
                        }
                    }
                }.awaitAll()
            } finally {
                meter.cancel()
                http.connectionPool.evictAll()
            }
            // Join the pieces in order.
            FileOutputStream(target).use { out ->
                parts.forEach { part -> part.inputStream().use { it.copyTo(out, BUFFER) } }
            }
            parts.forEach { it.delete() }
            onProgress(Progress(size, size, 0))
            true
        }

    private suspend fun fetchRange(http: OkHttpClient, url: String, range: LongRange, part: File, done: AtomicLong) {
        val length = range.last - range.first + 1
        val have = part.length()
        if (have >= length) return
        val request = Request.Builder().url(url)
            .header("Range", "bytes=${range.first + have}-${range.last}")
            .header("Accept-Encoding", "identity")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 200) throw RangeNotSupported()
            if (response.code != 206) throw IllegalStateException("HTTP ${response.code}")
            val input = response.body?.byteStream() ?: throw IllegalStateException("empty body")
            FileOutputStream(part, true).use { out ->
                val buffer = ByteArray(BUFFER)
                var remaining = length - have
                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                    done.addAndGet(read.toLong())
                }
                if (remaining > 0) throw IllegalStateException("connection closed early")
            }
        }
    }

    /** One plain stream, continued from the bytes already on disk when the server allows it. */
    private suspend fun single(route: Route, target: File, size: Long, onProgress: (Progress) -> Unit): Boolean =
        coroutineScope {
            val part = File(target.parentFile, "${target.name}.part")
            if (size > 0 && part.length() > size) part.delete()
            val have = part.length()
            val http = client(route)
            val done = AtomicLong(have)
            val meter = launch { meter(done, size, onProgress) }
            try {
                val builder = Request.Builder().url(route.url).header("Accept-Encoding", "identity")
                if (have > 0) builder.header("Range", "bytes=$have-")
                http.newCall(builder.build()).execute().use { response ->
                    val append = response.code == 206
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    if (!append) done.set(0)
                    val input = response.body?.byteStream() ?: throw IllegalStateException("empty body")
                    FileOutputStream(part, append).use { out ->
                        val buffer = ByteArray(BUFFER)
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done.addAndGet(read.toLong())
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (size > 0 && part.length() != size) throw IllegalStateException("incomplete file")
                target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("could not finish the file")
                onProgress(Progress(target.length(), target.length(), 0))
                true
            } finally {
                meter.cancel()
                http.connectionPool.evictAll()
            }
        }

    /** Reports progress twice a second, with a speed that is smoothed so it does not jump. */
    private suspend fun meter(done: AtomicLong, total: Long, onProgress: (Progress) -> Unit) {
        var last = done.get()
        var speed = 0.0
        while (true) {
            delay(500)
            val now = done.get()
            val instant = (now - last).coerceAtLeast(0) * 2.0
            speed = if (speed == 0.0) instant else speed * 0.7 + instant * 0.3
            last = now
            onProgress(Progress(now, total, speed.toLong()))
        }
    }

    private fun verify(file: File, size: Long, sha256: String?): Boolean {
        if (file.length() <= 0) return false
        if (size > 0 && file.length() != size) return false
        if (sha256 == null) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    private fun cleanup(target: File) {
        target.parentFile?.listFiles()?.filter { it.name.startsWith(target.name) }?.forEach { it.delete() }
    }
}
