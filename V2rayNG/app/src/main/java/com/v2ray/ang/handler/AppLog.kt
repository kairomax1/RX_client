package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own log: what each part of the app did, what worked and what did not. Kept in a
 * small file inside the app instead of read from Android's log, because many phones hand an
 * app nothing back from there. Both the screen and the VPN service process append to it.
 *
 * Nothing secret goes in: callers log names and outcomes, and anything that looks like a
 * whole config is refused by [sanitize].
 */
object AppLog {

    /** The part of the app a line belongs to; the log screen has one tab per section. */
    enum class Section { Connection, Core, Aether, GamingMode, Test, Subscription, Update, Other }

    enum class Level { Debug, Info, Error }

    /** What the reader wants to see. */
    enum class Mode {
        /** The main steps and their outcome. */
        Normal,

        /** Only what went wrong. */
        Errors,

        /** Everything, for finding the cause of a problem. */
        Debug,
    }

    data class Entry(val timeMillis: Long, val section: Section, val level: Level, val message: String)

    private const val FILE_NAME = "rx-app.log"
    private const val OLD_FILE_NAME = "rx-app.log.1"
    private const val MAX_BYTES = 400 * 1024L
    private const val MAX_MESSAGE_CHARS = 600
    private val lock = Any()

    private fun dir(): File? = try {
        File(AngApplication.application.filesDir, "logs").apply { mkdirs() }
    } catch (e: Throwable) {
        // Not on a device (unit tests) or too early in start-up: logging is simply skipped.
        null
    }

    fun info(section: Section, message: String) = write(section, Level.Info, message)
    fun error(section: Section, message: String) = write(section, Level.Error, message)
    fun debug(section: Section, message: String) = write(section, Level.Debug, message)

    fun write(section: Section, level: Level, message: String) {
        val text = sanitize(message) ?: return
        val folder = dir() ?: return
        try {
            synchronized(lock) {
                val file = File(folder, FILE_NAME)
                if (file.length() > MAX_BYTES) {
                    val old = File(folder, OLD_FILE_NAME)
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(format(Entry(System.currentTimeMillis(), section, level, text)) + "\n")
            }
        } catch (e: Throwable) {
            // A log that cannot be written must never break what it was describing.
        }
    }

    /** Everything stored, oldest first. */
    fun read(): List<Entry> {
        val folder = dir() ?: return emptyList()
        return try {
            synchronized(lock) {
                listOf(File(folder, OLD_FILE_NAME), File(folder, FILE_NAME))
                    .filter { it.exists() }
                    .flatMap { it.readLines() }
                    .mapNotNull(::parse)
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun clear() {
        val folder = dir() ?: return
        synchronized(lock) {
            File(folder, FILE_NAME).delete()
            File(folder, OLD_FILE_NAME).delete()
        }
    }

    /**
     * One line, without line breaks and cut to a sane length. Null for text that must not be
     * stored: empty lines and whole configs (they carry server addresses and keys).
     */
    internal fun sanitize(message: String): String? {
        val oneLine = message.replace('\n', ' ').replace('\r', ' ').trim()
        if (oneLine.isEmpty()) return null
        if (oneLine.startsWith("{") || oneLine.startsWith("[{")) return null
        if (SHARE_LINK.containsMatchIn(oneLine)) return null
        val masked = maskLinks(oneLine)
        return if (masked.length > MAX_MESSAGE_CHARS) masked.take(MAX_MESSAGE_CHARS) + "…" else masked
    }

    /**
     * Web links are replaced by a placeholder: a subscription link is as good as a password.
     * Links to a few well-known test hosts stay, they explain what a test tried to reach.
     */
    internal fun maskLinks(text: String): String = WEB_LINK.replace(text) { match ->
        val host = match.groupValues[1].lowercase()
        if (HARMLESS_HOSTS.any { host == it || host.endsWith(".$it") }) match.value else "<link>"
    }

    private val WEB_LINK = Regex("https?://([^/\\s:\"']+)[^\\s\"']*", RegexOption.IGNORE_CASE)
    private val HARMLESS_HOSTS = listOf("gstatic.com", "google.com", "cloudflare.com", "github.com", "githubusercontent.com")

    private val SHARE_LINK = Regex("(vless|vmess|trojan|ss|ssr|hysteria2|hy2|tuic|wireguard|socks)://", RegexOption.IGNORE_CASE)

    internal fun format(entry: Entry): String =
        "${entry.timeMillis}|${entry.section.name}|${entry.level.name}|${entry.message}"

    internal fun parse(line: String): Entry? {
        val parts = line.split('|', limit = 4)
        if (parts.size < 4) return null
        val time = parts[0].toLongOrNull() ?: return null
        val section = Section.entries.firstOrNull { it.name == parts[1] } ?: return null
        val level = Level.entries.firstOrNull { it.name == parts[2] } ?: return null
        return Entry(time, section, level, parts[3])
    }

    /** The lines to show for a tab ([section] null = all sections) and a mode. */
    fun filter(entries: List<Entry>, section: Section?, mode: Mode): List<Entry> = entries.filter { entry ->
        (section == null || entry.section == section) && when (mode) {
            Mode.Normal -> entry.level != Level.Debug
            Mode.Errors -> entry.level == Level.Error
            Mode.Debug -> true
        }
    }

    /** Which section a line of the old tag-based logging belongs to, judged by how it starts. */
    internal fun sectionOf(message: String): Section {
        val text = message.trimStart()
        return when {
            text.startsWith("Aether", ignoreCase = true) -> Section.Aether
            text.startsWith("StartCore", ignoreCase = true) || text.startsWith("Core", ignoreCase = true) ||
                text.startsWith("Xray", ignoreCase = true) -> Section.Core
            text.startsWith("Launcher", ignoreCase = true) || text.contains("VpnService", ignoreCase = true) ||
                text.startsWith("Tun", ignoreCase = true) || text.startsWith("Hev", ignoreCase = true) -> Section.Connection
            text.startsWith("Update", ignoreCase = true) -> Section.Update
            text.contains("subscription", ignoreCase = true) -> Section.Subscription
            text.contains("test", ignoreCase = true) || text.contains("delay", ignoreCase = true) ||
                text.contains("ping", ignoreCase = true) -> Section.Test
            text.startsWith("Gaming mode", ignoreCase = true) || text.startsWith("DNS mode", ignoreCase = true) -> Section.GamingMode
            else -> Section.Other
        }
    }

    /** The whole log as text for a file the user can send: newest last, one line per entry. */
    fun export(entries: List<Entry>, header: String): String {
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine(header)
            appendLine()
            entries.forEach { entry ->
                append(clock.format(Date(entry.timeMillis)))
                append("  [").append(entry.section.name).append("] ")
                append(if (entry.level == Level.Error) "ERROR " else if (entry.level == Level.Debug) "debug " else "")
                appendLine(entry.message)
            }
        }
    }
}
