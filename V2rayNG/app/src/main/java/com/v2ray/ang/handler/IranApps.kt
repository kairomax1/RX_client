package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import com.v2ray.ang.dto.AppInfo

/**
 * Iranian apps, as the phone can recognise them.
 *
 * A bank that sees a foreign address refuses the login, a shop that sees one
 * shows the wrong prices, and a messenger whose name is written in Persian is
 * not talking to anyone abroad. These are the apps that have to go out on the
 * phone's own line while the rest of the phone's traffic goes through the
 * tunnel, and they are found without the owner having to know which is which.
 *
 * Three things give an app away, and any one of them is enough:
 *  - its package, against the packages shipped in assets/rx_iran_apps.txt;
 *  - its name, whether the phone shows it in Persian or in the Latin alphabet,
 *    against the name pieces in the same file;
 *  - a name written in Persian at all, which is how an app made for this
 *    country is labelled even when nothing else about it is known here.
 *
 * The last rule is the wide one, so the packages of the best known foreign apps
 * are kept out of it: their names are translated like everyone else's.
 */
object IranApps {

    private const val ASSET = "rx_iran_apps.txt"

    /** Packages that are not Iranian whatever their name has been translated into. */
    private val NOT_IRANIAN = listOf(
        "com.google", "com.android", "com.facebook", "com.instagram", "com.whatsapp",
        "org.telegram", "com.twitter", "com.microsoft", "com.samsung", "com.xiaomi",
        "com.huawei", "com.amazon", "com.netflix", "com.spotify", "com.snapchat",
        "com.tiktok", "com.linkedin", "com.youtube", "org.mozilla", "com.opera",
        "com.brave", "com.discord", "com.skype", "com.viber", "com.pinterest",
        "com.reddit", "com.ebay", "com.aliexpress", "com.yandex", "com.booking",
        "com.airbnb", "com.ubercab", "com.vivaldi", "com.duckduckgo", "org.signal",
        "com.slack", "com.zoom", "com.dropbox", "com.adobe", "com.spotify.music",
    )

    /** Packages from the file, lower case. */
    private val packages: Set<String> by lazy {
        read().filter { it.contains('.') }.map { it.lowercase() }.toSet()
    }

    /** Name pieces from the file, lower case. */
    private val nameKeys: List<String> by lazy {
        read().filter { !it.contains('.') }.map { it.lowercase() }.filter { it.isNotEmpty() }
    }

    /** Whether this app is one that must not arrive from a foreign address. */
    fun isIranian(packageName: String, appName: String): Boolean {
        val pkg = packageName.lowercase()
        if (NOT_IRANIAN.any { pkg == it || pkg.startsWith("$it.") }) return false
        if (pkg in packages || packages.any { pkg.startsWith("$it.") }) return true
        val name = appName.lowercase()
        if (nameKeys.any { name.contains(it) }) return true
        return isWrittenInPersian(appName)
    }

    /** The packages among [apps] that were recognised as Iranian. */
    fun detect(apps: List<AppInfo>): Set<String> =
        apps.asSequence()
            .filter { isIranian(it.packageName, it.appName) }
            .map { it.packageName }
            .toSet()

    /** True when the name carries Persian letters, whichever way the phone writes them. */
    private fun isWrittenInPersian(text: String): Boolean =
        text.any { it in '\u0600'..'\u06FF' }

    private fun read(): List<String> = try {
        AngApplication.application.assets.open(ASSET).bufferedReader().use { reader ->
            reader.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
    } catch (e: Throwable) {
        emptyList()
    }
}
