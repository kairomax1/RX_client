package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig

/**
 * Sharing: this phone's connection, given to another device.
 *
 * A console, a television or another phone reaches the tunnel by using the
 * address of this phone and a port as its proxy server. Everything another
 * device does goes through one door (the gateway), which is what makes it
 * possible to see who is asking, to ask the owner first, and to shut one
 * device out without touching the others.
 *
 * The gateway runs in the daemon process, next to the core, so sharing keeps
 * working after the app is closed. The screen reads what it writes here:
 * both sides use the same multi-process storage, the screen only reading what
 * the gateway writes and the other way around.
 */
object Sharing {

    /** What the owner has decided about a device. */
    const val ASK = "ask"
    const val ALLOW = "allow"
    const val BLOCK = "block"

    private const val PREF_MINUTES = "pref_rx_sharing_minutes"
    private const val PREF_UNTIL = "pref_rx_sharing_until"
    private const val PREF_DEVICES = "pref_rx_sharing_devices"
    private const val PREF_LIVE = "pref_rx_sharing_live"
    private const val PREF_STOPPED = "pref_rx_sharing_stopped"
    private const val PREF_PORT = "pref_rx_sharing_port"

    /** No limit: sharing stays on until it is switched off. */
    const val NO_LIMIT = 0

    /**
     * One device that reached the phone. [verdict] is what the owner decided;
     * [active] is how many of its connections are open now, and [bytes] what
     * they have carried in this run.
     */
    data class Device(
        val ip: String = "",
        val name: String = "",
        val verdict: String = ASK,
        val addedAt: Long = 0,
        val bytes: Long = 0,
        val active: Int = 0,
        val since: Long = 0,
        val lastSeen: Long = 0,
        val target: String = "",
        val reaching: Boolean = true,
        /** True while the device is waiting for the owner to allow it. */
        val waiting: Boolean = false,
    ) {
        /** The name the owner gave it, or its address when it has none. */
        val title: String get() = name.ifEmpty { ip }
    }

    /** What the gateway is doing right now, for the screen to show. */
    data class Live(
        val running: Boolean = false,
        val port: Int = 0,
        val until: Long = 0,
        val devices: List<Device> = emptyList(),
    )

    // ── The switch and its limits ─────────────────────────────────

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false)

    fun setEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, enabled)
        if (!enabled) setStoppedReason(null)
    }

    /** How long sharing is allowed to stay on; 0 is "until I switch it off". */
    fun minutes(): Int = MmkvManager.decodeSettingsString(PREF_MINUTES)?.toIntOrNull() ?: NO_LIMIT

    fun setMinutes(minutes: Int) {
        MmkvManager.encodeSettings(PREF_MINUTES, minutes.toString())
    }

    /** The moment sharing ends by itself; 0 when nothing ends it. */
    fun until(): Long = MmkvManager.decodeSettingsString(PREF_UNTIL)?.toLongOrNull() ?: 0L

    fun setUntil(millis: Long) {
        MmkvManager.encodeSettings(PREF_UNTIL, millis.toString())
    }

    /** Why sharing stopped on its own; empty while it did not. */
    fun stoppedReason(): String = MmkvManager.decodeSettingsString(PREF_STOPPED).orEmpty()

    fun setStoppedReason(reason: String?) {
        MmkvManager.encodeSettings(PREF_STOPPED, reason.orEmpty())
    }

    /**
     * The port other devices use. It is not the core's own port: the core
     * listens on this phone alone, and the gateway is the only door from
     * outside, which is what lets a device be asked about or shut out. It is
     * a port of its own so that the address given out stays true even when
     * the core's own port moves.
     */
    fun port(): Int = MmkvManager.decodeSettingsString(PREF_PORT)?.toIntOrNull() ?: DEFAULT_PORT

    fun setPort(port: Int) {
        MmkvManager.encodeSettings(PREF_PORT, port.toString())
    }

    /** Where the door is opened when nothing has been tried yet. */
    const val DEFAULT_PORT = 10812

    // ── The devices the owner has decided about ───────────────────

    fun devices(): List<Device> = read(PREF_DEVICES).mapNotNull(::deviceOf)

    private fun saveDevices(list: List<Device>) {
        MmkvManager.encodeSettings(PREF_DEVICES, list.joinToString("\n") { device ->
            listOf(
                clean(device.ip),
                clean(device.name),
                device.verdict,
                device.addedAt.toString(),
            ).joinToString("|")
        })
    }

    fun verdictOf(ip: String): String =
        devices().firstOrNull { it.ip == ip }?.verdict ?: ASK

    /** Lets a device through, under the name the owner gave it. */
    fun allow(ip: String, name: String) {
        val now = System.currentTimeMillis()
        val list = devices().toMutableList()
        val index = list.indexOfFirst { it.ip == ip }
        val device = Device(ip = ip, name = clean(name), verdict = ALLOW, addedAt = now)
        if (index >= 0) list[index] = device.copy(addedAt = list[index].addedAt) else list += device
        saveDevices(list)
    }

    fun block(ip: String) {
        val now = System.currentTimeMillis()
        val list = devices().toMutableList()
        val index = list.indexOfFirst { it.ip == ip }
        val device = Device(ip = ip, name = list.getOrNull(index)?.name.orEmpty(), verdict = BLOCK, addedAt = now)
        if (index >= 0) list[index] = device else list += device
        saveDevices(list)
    }

    /** Forgets a device, so it is asked about again the next time. */
    fun forget(ip: String) {
        saveDevices(devices().filter { it.ip != ip })
    }

    fun rename(ip: String, name: String) {
        val list = devices().toMutableList()
        val index = list.indexOfFirst { it.ip == ip }
        if (index >= 0) list[index] = list[index].copy(name = clean(name))
        saveDevices(list)
    }

    // ── What the gateway is doing ─────────────────────────────────

    fun live(): Live {
        val lines = read(PREF_LIVE)
        if (lines.isEmpty()) return Live()
        val head = lines.first().split('|')
        val running = head.getOrNull(0) == "1"
        val port = head.getOrNull(1)?.toIntOrNull() ?: 0
        val until = head.getOrNull(2)?.toLongOrNull() ?: 0L
        val devices = lines.drop(1).mapNotNull(::deviceOf)
        return Live(running = running, port = port, until = until, devices = devices)
    }

    fun publish(live: Live) {
        val head = listOf(
            if (live.running) "1" else "0",
            live.port.toString(),
            live.until.toString(),
        ).joinToString("|")
        val body = live.devices.joinToString("\n") { device ->
            listOf(
                clean(device.ip),
                clean(device.name),
                device.verdict,
                device.active.toString(),
                device.bytes.toString(),
                device.since.toString(),
                device.lastSeen.toString(),
                clean(device.target),
                if (device.reaching) "1" else "0",
                if (device.waiting) "1" else "0",
            ).joinToString("|")
        }
        MmkvManager.encodeSettings(PREF_LIVE, if (body.isEmpty()) head else "$head\n$body")
    }

    fun clearLive() {
        MmkvManager.encodeSettings(PREF_LIVE, "")
    }

    // ── Reading and writing the lines ─────────────────────────────

    private fun read(key: String): List<String> =
        MmkvManager.decodeSettingsString(key).orEmpty().split('\n').filter { it.isNotBlank() }

    /** One saved line: the short form carries what a decision needs. */
    private fun deviceOf(line: String): Device? {
        val parts = line.split('|')
        val ip = parts.getOrNull(0).orEmpty()
        if (ip.isEmpty()) return null
        return Device(
            ip = ip,
            name = parts.getOrNull(1).orEmpty(),
            verdict = parts.getOrNull(2).orEmpty().ifEmpty { ASK },
            addedAt = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
            active = parts.getOrNull(4)?.toIntOrNull() ?: 0,
            bytes = parts.getOrNull(5)?.toLongOrNull() ?: 0L,
            since = parts.getOrNull(6)?.toLongOrNull() ?: 0L,
            lastSeen = parts.getOrNull(7)?.toLongOrNull() ?: 0L,
            target = parts.getOrNull(8).orEmpty(),
            reaching = parts.getOrNull(9) != "0",
            waiting = parts.getOrNull(10) == "1",
        )
    }

    /** Keeps the separator out of what the owner types. */
    internal fun clean(text: String): String =
        text.replace('|', ' ').replace('\n', ' ').trim().take(40)
}
