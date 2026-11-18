package com.v2ray.ang.handler

import com.v2ray.ang.AngApplication
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The advert names, kept fresh.
 *
 * Thousands of Iranian sites carry adverts of their own, and the names those
 * adverts come from change every week. The list that travels inside the app is
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
     * Where the advert names of Iranian sites are published. The file is a few
     * hundred lines, which is why it can be fetched every day.
     */
    private const val ADS_URL =
        "https://raw.githubusercontent.com/bootmortis/iran-hosted-domains/release/clash_rules_ads.txt"

    private const val PREF_ADS = "pref_rx_site_ads"
    private const val PREF_ADS_AT = "pref_rx_site_ads_at"

    /**
     * Where the names of the sites that are served from inside the country are
     * published: the country's own domains were resolved one by one and the ones
     * that answered from an address at home were kept.
     */
    private const val HOME_URL =
        "https://raw.githubusercontent.com/bootmortis/iran-hosted-domains/main/src/data/adsl_tci.txt"

    /**
     * Where the addresses announced from inside the country are published, one
     * range per line, kept fresh every six hours by the people who publish it.
     */
    private const val IRAN_IPV4_URL =
        "https://raw.githubusercontent.com/ravenscourt/xylem-ip-db/main/lists/ir.ipv4.txt"
    private const val IRAN_IPV6_URL =
        "https://raw.githubusercontent.com/ravenscourt/xylem-ip-db/main/lists/ir.ipv6.txt"

    private const val PREF_HOME = "pref_rx_site_home"
    private const val PREF_HOME_AT = "pref_rx_site_home_at"
    private const val PREF_IPS = "pref_rx_site_iran_ips"
    private const val PREF_IPS_AT = "pref_rx_site_iran_ips_at"

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    private const val FETCH_TIMEOUT_MS = 20_000

    /** Every second day: the lists move slowly, and a list is fetched for nothing otherwise. */
    private const val TWO_DAYS_MILLIS = 2 * DAY_MILLIS

    /** Fewer names than this in an answer means the answer is no good. */
    private const val MIN_ADS = 100

    /** The widest a list may grow, however large the answer is. */
    private const val MAX_ADS = 5_000

    /** Fewer names than this and the answer is not the list it said it was. */
    private const val MIN_HOME = 500

    private const val MAX_HOME = 30_000

    /** Fewer ranges than this and it is not the country's address book. */
    private const val MIN_IPS = 200

    private const val MAX_IPS = 20_000

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

    /**
     * The names of the sites served from inside the country. What was fetched is
     * used when there has been a fetch; the list that travels with the app is
     * used until there has been one, so a phone that has never been online with
     * this app still knows where home is.
     */
    fun homeDomains(): List<String> = keptOrBundled(PREF_HOME, MIN_HOME, bundledHomeDomains)

    /** The ranges announced from inside the country, IPv4 and IPv6 alike. */
    fun iranIpRanges(): List<String> = keptOrBundled(PREF_IPS, MIN_IPS, bundledIranIps)

    /**
     * What was fetched when there is enough of it, and what travels with the app
     * otherwise.
     *
     * Storage is read defensively because this is asked while a tunnel is being
     * built, and the answer changes nothing about whether the tunnel is correct:
     * the list that ships with the app is a good list. A store that cannot be
     * read must not take the routing with it — and in a unit test there is no
     * store at all, which is how this was found.
     */
    private fun keptOrBundled(pref: String, minimum: Int, bundled: List<String>): List<String> {
        val kept = try {
            MmkvManager.decodeSettingsString(pref).orEmpty()
                .lineSequence().filter { it.isNotBlank() }.toList()
        } catch (e: Throwable) {
            emptyList()
        }
        return if (kept.size >= minimum) kept else bundled
    }

    /** What travels with the app, for a phone that has not fetched the list yet. */
    private val bundledHomeDomains: List<String> by lazy { parseHostNames(readAsset("rx_iran_hosted_domains.txt")) }

    private val bundledIranIps: List<String> by lazy { parseCidrs(readAsset("rx_iran_ips.txt")) }

    private fun readAsset(name: String): String = try {
        AngApplication.application.assets.open(name).bufferedReader().readText()
    } catch (e: Throwable) {
        ""
    }

    /** When the list now held was fetched; 0 when it never was. */
    fun fetchedAt(): Long =
        MmkvManager.decodeSettingsString(PREF_ADS_AT)?.toLongOrNull() ?: 0L

    /** True when what is held is more than a day old. */
    fun isStale(): Boolean = System.currentTimeMillis() - fetchedAt() > DAY_MILLIS

    /** Fetches again only when the list is a day old or more. */
    suspend fun refreshIfDue(): Int = if (isStale()) refresh() else adDomains().size

    /**
     * Fetches the two lists that say where home is — the sites served from inside
     * the country and the addresses announced from it — when either is two days
     * old or more. Returns how many entries are held afterwards, for the log.
     */
    suspend fun refreshHomeIfDue(): Pair<Int, Int> {
        val names = if (System.currentTimeMillis() - fetchedAt(PREF_HOME_AT) > TWO_DAYS_MILLIS) {
            refreshHome()
        } else {
            homeDomains().size
        }
        val ranges = if (System.currentTimeMillis() - fetchedAt(PREF_IPS_AT) > TWO_DAYS_MILLIS) {
            refreshIranIps()
        } else {
            iranIpRanges().size
        }
        return names to ranges
    }

    private fun fetchedAt(key: String): Long =
        MmkvManager.decodeSettingsString(key)?.toLongOrNull() ?: 0L

    /** Fetches the names of the sites served from inside the country. */
    suspend fun refreshHome(): Int = withContext(Dispatchers.IO) {
        val names = parseHostNames(fetch(HOME_URL))
        if (names.size < MIN_HOME) {
            AppLog.error(
                AppLog.Section.Other,
                "The list of sites at home carried ${names.size} names, so the one already held is kept",
            )
            return@withContext homeDomains().size
        }
        val kept = names.take(MAX_HOME)
        MmkvManager.encodeSettings(PREF_HOME, kept.joinToString("\n"))
        MmkvManager.encodeSettings(PREF_HOME_AT, System.currentTimeMillis().toString())
        AppLog.info(AppLog.Section.Other, "Sites at home: ${kept.size} names, renewed")
        kept.size
    }

    /** Fetches the addresses announced from inside the country, both families of them. */
    suspend fun refreshIranIps(): Int = withContext(Dispatchers.IO) {
        val ranges = (parseCidrs(fetch(IRAN_IPV4_URL)) + parseCidrs(fetch(IRAN_IPV6_URL))).distinct()
        if (ranges.size < MIN_IPS) {
            AppLog.error(
                AppLog.Section.Other,
                "The list of addresses at home carried ${ranges.size} ranges, so the one already held is kept",
            )
            return@withContext iranIpRanges().size
        }
        val kept = ranges.take(MAX_IPS)
        MmkvManager.encodeSettings(PREF_IPS, kept.joinToString("\n"))
        MmkvManager.encodeSettings(PREF_IPS_AT, System.currentTimeMillis().toString())
        AppLog.info(AppLog.Section.Other, "Addresses at home: ${kept.size} ranges, renewed")
        kept.size
    }

    private fun fetch(url: String): String =
        HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = FETCH_TIMEOUT_MS)).orEmpty()

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

    /**
     * Reads the names out of a rule file of the kind these lists are published
     * in: one name per line, either as "+.example.com" (that name and everything
     * under it) or written bare. Anything that is not a host name is dropped,
     * because a single bad line in a routing rule is enough to keep the core
     * from starting at all.
     */
    /**
     * Reads host names out of a list that publishes them as addresses: "http://site/path"
     * and "site" alike are both one name here. Anything that is not a host name is
     * dropped, because one bad line in a routing rule is enough to stop the core.
     */
    internal fun parseHostNames(text: String): List<String> = text.lineSequence()
        .map { it.substringBefore('#').trim().lowercase() }
        .filter { it.isNotBlank() }
        .map { it.substringAfter("://", it).substringBefore('/').substringBefore(':').trim() }
        .map { it.removePrefix("www.") }
        .filter { name ->
            name.length in 4..253 && name.contains('.') &&
                name.all { c -> (c.isLetterOrDigit() && c.code < 128) || c == '-' || c == '.' } &&
                !name.startsWith('.') && !name.endsWith('.') && !name.contains("..")
        }
        .map { "domain:$it" }
        .distinct()
        .toList()

    /**
     * Reads ranges out of a list of addresses: one CIDR per line, a bare address
     * counted as one of its own, notes and blank lines passed over. Both families
     * of address are welcome in the same list.
     */
    internal fun parseCidrs(text: String): List<String> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val range = if ('/' in line) line else line + if (':' in line) "/128" else "/32"
            range.takeIf { isCidr(it) && !isPrivateRange(it) }
        }
        .distinct()
        .toList()

    /**
     * True for an address that belongs to the phone's own network rather than to
     * the internet, and so to neither this country's sites nor any other.
     *
     * These are left out wherever a list of countries is parsed, because a
     * published list can carry them and the consequence is not cosmetic.
     * `10.0.0.0/8` is where the filtering page answers (10.10.34.x): treating it
     * as an Iranian address sent every filtered site out on the home line, where
     * it is blocked — which is why WhatsApp, Telegram and Proton stopped opening
     * in gaming mode, even though the address itself never leaked.
     * `100.64.0.0/10` is the carrier's own translation range and means only that
     * the phone is behind one.
     */
    internal fun isPrivateRange(range: String): Boolean {
        val address = range.substringBefore('/')
        if (':' in address) return false
        val first = address.substringBefore('.').toIntOrNull() ?: return false
        val second = address.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return first == 10 ||
            first == 127 ||
            (first == 100 && second in 64..127) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            first == 0 ||
            first >= 224
    }

    private fun isCidr(value: String): Boolean {
        val parts = value.split('/', limit = 2)
        if (parts.size != 2) return false
        val address = parts[0]
        val bits = parts[1].toIntOrNull() ?: return false
        return if (':' in address) {
            bits in 0..128 && address.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' || c == ':' }
        } else {
            bits in 0..32 && address.split('.').size == 4 && address.split('.').all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                    (part.toIntOrNull() ?: -1) in 0..255
            }
        }
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
