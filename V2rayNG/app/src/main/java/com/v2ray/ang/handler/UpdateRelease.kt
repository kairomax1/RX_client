package com.v2ray.ang.handler

import java.util.Locale

/**
 * Reading a published release: which file fits this phone, and which words are meant for the app.
 *
 * A release description has two audiences. Everything inside a hidden block
 *
 *     <!--app
 *     short text shown inside the app
 *     -->
 *
 * is the in-app summary; GitHub does not display it. The rest is the GitHub text and is never
 * shown in the app. A release without the block shows its whole description in the app.
 */
object UpdateRelease {

    private val APP_BLOCK = Regex("<!--\\s*app\\b(.*?)-->", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ANY_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    fun appNotes(body: String?): String {
        val text = body.orEmpty()
        val block = APP_BLOCK.find(text)?.groupValues?.get(1)?.trim()
        if (!block.isNullOrEmpty()) return block
        return text.replace(ANY_COMMENT, "").trim()
    }

    /**
     * The APK for the first processor type of [abis] that the release has a file for; the
     * universal build when none matches. File names end in `_<abi>.apk`, so `x86` can never be
     * mistaken for `x86_64`.
     */
    fun <T> pickApk(assets: List<T>, abis: List<String>, nameOf: (T) -> String): T? {
        // Test builds carry "debug" in their name and are signed differently; never offer them.
        val apks = assets.filter {
            nameOf(it).endsWith(".apk", ignoreCase = true) && !nameOf(it).contains("debug", ignoreCase = true)
        }
        for (abi in abis) {
            apks.firstOrNull { nameOf(it).endsWith("_$abi.apk", ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { nameOf(it).contains("universal", ignoreCase = true) }
    }

    /** "24.6 MB" style size, always with Latin digits so it reads the same in every language. */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** The hex part of GitHub's `sha256:<hex>` asset digest, or null when it is something else. */
    fun sha256Of(digest: String?): String? =
        digest?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substring(7)?.lowercase(Locale.US)?.takeIf { it.length == 64 }

    /** Byte ranges (inclusive) that split [size] into [parts] nearly equal pieces. */
    fun segments(size: Long, parts: Int): List<LongRange> {
        if (size <= 0 || parts <= 1) return listOf(0L until size.coerceAtLeast(0))
        val step = size / parts
        return (0 until parts).map { i ->
            val start = i * step
            val end = if (i == parts - 1) size - 1 else start + step - 1
            start..end
        }
    }
}
