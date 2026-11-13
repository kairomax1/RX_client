package com.v2ray.ang.core

import android.content.Context
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * The Aether engine (https://github.com/QW-AI-Code/Aether, AGPL-3.0-only): a separate program
 * shipped as libaether.so that builds its own free route through Cloudflare's network and
 * offers it as a SOCKS proxy on a local port. RX VPN does not link it; it starts it as a
 * process and points an ordinary SOCKS profile at that port, so everything else in the app
 * (routing, DNS mode, the tunnel) treats it like any other config.
 *
 * It lets someone without a config of their own connect. How fast it is, and whether it gets
 * through on a given network, is up to that free route.
 */
object AetherEngine {

    const val HOST = "127.0.0.1"
    const val PORT = 1819
    const val PROFILE_NAME = "Aether"
    private const val BINARY = "libaether.so"

    /** The engine is built for Android 8.0 and newer. */
    private const val MIN_SDK = Build.VERSION_CODES.O

    private var process: Process? = null

    private fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, BINARY)

    /** True when this build carries an engine that can run on this phone. */
    fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= MIN_SDK && binary(context).exists()

    /** True for the profile that stands for the engine. */
    fun isAetherProfile(profile: ProfileItem?): Boolean = isAetherEndpoint(
        isSocks = profile?.configType == EConfigType.SOCKS,
        server = profile?.server,
        port = profile?.serverPort,
    )

    internal fun isAetherEndpoint(isSocks: Boolean, server: String?, port: String?): Boolean =
        isSocks && server == HOST && port == PORT.toString()

    /** Id of the Aether profile, creating it in the default group when it does not exist yet. */
    fun ensureProfile(): String {
        MmkvManager.decodeAllServerList().firstOrNull { isAetherProfile(MmkvManager.decodeServerConfig(it)) }
            ?.let { return it }
        val profile = ProfileItem.create(EConfigType.SOCKS).apply {
            remarks = PROFILE_NAME
            server = HOST
            serverPort = PORT.toString()
        }
        return MmkvManager.encodeServerConfig("", profile)
    }

    /**
     * Starts the engine if it is not running. Returns at once: the engine needs from a few
     * seconds to a minute to find a working route, and connections simply fail until then.
     */
    @Synchronized
    fun start(context: Context) {
        if (process?.isRunning() == true) return
        if (!isAvailable(context)) {
            LogUtil.w(AppConfig.TAG, "Aether: engine is not included in this build")
            return
        }
        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            // Persistent, so the engine keeps its identity instead of registering a new one each time.
            val workDir = File(context.filesDir, "aether").apply { mkdirs() }
            val builder = ProcessBuilder(binary(context).absolutePath, "--bind", "$HOST:$PORT")
                .directory(workDir)
                .redirectErrorStream(true)
            builder.environment().apply {
                put("HOME", workDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                // An executable does not get the app's library search path; its C++ runtime
                // sits next to it.
                put("LD_LIBRARY_PATH", listOfNotNull(nativeDir, get("LD_LIBRARY_PATH")?.takeIf { it.isNotBlank() }).joinToString(":"))
            }
            val started = builder.start()
            process = started
            // The output must be read, or the engine blocks once the pipe fills up.
            Thread({
                try {
                    started.inputStream.bufferedReader().forEachLine { LogUtil.i(AppConfig.TAG, "Aether: $it") }
                } catch (_: Exception) {
                }
                LogUtil.i(AppConfig.TAG, "Aether: engine exited")
            }, "aether-log").apply { isDaemon = true }.start()
            LogUtil.i(AppConfig.TAG, "Aether: engine started on $HOST:$PORT")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Aether: engine could not start", e)
            process = null
        }
    }

    @Synchronized
    fun stop() {
        val running = process ?: return
        process = null
        try {
            running.destroy()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Aether: engine could not be stopped", e)
        }
    }

    private fun Process.isRunning(): Boolean = try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}
