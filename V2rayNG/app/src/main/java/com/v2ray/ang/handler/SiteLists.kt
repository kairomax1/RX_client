package com.v2ray.ang.handler

import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The advert names, kept fresh.
 *
 * Sites carry adverts of their own, and the names those adverts come from
 * change every week. The list that travels inside the app is
 * therefore only the beginning: once a day the app fetches the list that is
 * kept for exactly this purpose and adds it, so an advert that appears
 * tomorrow is not answered from a name nobody knew yesterday.
 *
 * It is fetched, never fetched through the tunnel — a list of names is worth
 * nothing with somebody else's address attached to it — and a short or broken
 * answer is thrown away rather than replacing a list that was good.
 */
object SiteLists {

    /**
     * Where advert names are published: one name per line, renewed by the people
     * who keep it. A few hundred lines, which is why it can be fetched daily.
     */
    private const val ADS_URL =
        "https://raw.githubusercontent.com/bootmortis/iran-hosted-domains/release/clash_rules_ads.txt"

    private const val PREF_ADS = "pref_rx_site_ads"
    private const val PREF_ADS_AT = "pref_rx_site_ads_at"

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    private const val FETCH_TIMEOUT_MS = 20_000

    /** Fewer names than this in an answer means the answer is no good. */
    private const val MIN_ADS = 100

    /** The widest a list may grow, however large the answer is. */
    private const val MAX_ADS = 5_000

    /** The names held from the last good answer; empty when there has been none. */
    fun adDomains(): List<String> = try {
        MmkvManager.decodeSettingsString(PREF_ADS).orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
    } catch (e: Throwable) {
        // Asked while the routing is being assembled; with no store to read there
        // are simply no advert names, which is the same as having never fetched
        // them. It must not take the tunnel down with it.
        emptyList()
    }

    /** When the list now held was fetched; 0 when it never was. */
    fun fetchedAt(): Long =
        MmkvManager.decodeSettingsString(PREF_ADS_AT)?.toLongOrNull() ?: 0L

    /** True when what is held is more than a day old. */
    fun isStale(): Boolean = System.currentTimeMillis() - fetchedAt() > DAY_MILLIS

    /** Fetches again only when the list is a day old or more. */
    suspend fun refreshIfDue(): Int = if (isStale()) refresh() else adDomains().size

    /**
     * Fetches the list and keeps it. Returns how many names are held afterwards,
     * which is the old number when the answer was not good enough to trust.
     */
    suspend fun refresh(): Int = withContext(Dispatchers.IO) {
        val names = parseNames(
            HttpUtil.getUrlContent(UrlContentRequest(url = ADS_URL, timeout = FETCH_TIMEOUT_MS)).orEmpty()
        )
        if (names.size < MIN_ADS) {
            AppLog.error(
                AppLog.Section.Other,
                "Advert list: the answer carried ${names.size} names, so the list already held is kept",
            )
            return@withContext adDomains().size
        }
        val kept = names.take(MAX_ADS)
        MmkvManager.encodeSettings(PREF_ADS, kept.joinToString("\n"))
        MmkvManager.encodeSettings(PREF_ADS_AT, System.currentTimeMillis().toString())
        AppLog.info(AppLog.Section.Other, "Advert list: ${kept.size} names, renewed")
        kept.size
    }

    internal fun parseNames(text: String): List<String> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .map { it.removePrefix("+.").removePrefix(".") }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .filter { name -> name.all { c -> (c.isLetterOrDigit() && c.code < 128) || c == '-' || c == '.' } }
        .map { "domain:$it" }
        .distinct()
        .toList()
}
