package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import com.v2ray.ang.util.HttpUtil

/**
 * Finding the mux and fragment settings this line is fastest with.
 *
 * Which of the two helps, and whether either does at all, depends on the network
 * the phone is on: a mobile carrier that breaks a long TLS handshake is helped by
 * fragmenting it, while a clean line is only slowed by it. Mux carries many
 * requests over one connection, which wins on a slow, distant server and loses on
 * a fast one. Nobody can tell which from the config alone, so the app tries the
 * few combinations that make sense, measures each one through the tunnel, and
 * keeps the fastest.
 *
 * A config that cannot carry either setting is not tuned at all: it is simply
 * connected, which is what "it does not support it" has to mean.
 */
object Tuning {

    /** One combination, and the name it is remembered under. */
    data class Choice(
        val key: String,
        val mux: Boolean,
        val fragment: Boolean,
        val packets: String = "tlshello",
        val length: String = "50-100",
        val interval: String = "10-20",
        val maxSplit: String = "10",
    )

    /** Nothing added: the config as its maker wrote it. */
    val PLAIN = Choice("plain", mux = false, fragment = false)

    /** Many requests over one connection. */
    val MUX = Choice("mux", mux = true, fragment = false)

    /** The handshake split up, in the way that gets past the most filters. */
    val FRAGMENT = Choice("fragment", mux = false, fragment = true)

    /** Split finer and with less of a pause: slower to set up, harder to spot. */
    val FRAGMENT_FINE = Choice(
        "fragment-fine",
        mux = false,
        fragment = true,
        packets = "1-3",
        length = "10-50",
        interval = "5-10",
    )

    val BOTH = Choice("both", mux = true, fragment = true)

    private const val PREF_PREFIX = "pref_rx_tuning_"

    /** A 204: an answer with no body, so what is measured is the network, not the page. */
    private const val PROBE_URL = "https://cp.cloudflare.com/generate_204"

    /**
     * Mux only exists for the V2Ray protocols: the core turns it off for
     * Shadowsocks, socks, http, Trojan, WireGuard and Hysteria, and for XHTTP.
     */
    fun supportsMux(profile: ProfileItem?): Boolean {
        if (profile == null) return false
        val type = profile.configType
        if (type != EConfigType.VMESS && type != EConfigType.VLESS) return false
        return profile.network?.equals(NetworkType.XHTTP.type, ignoreCase = true) != true
    }

    /** Fragmenting is a change to the TLS handshake, so there has to be one. */
    fun supportsFragment(profile: ProfileItem?): Boolean {
        val security = profile?.security.orEmpty()
        return security.equals(AppConfig.TLS, ignoreCase = true) ||
            security.equals(AppConfig.REALITY, ignoreCase = true)
    }

    /** True when there is anything to try for this config at all. */
    fun worthTrying(profile: ProfileItem?): Boolean =
        supportsMux(profile) || supportsFragment(profile)

    /**
     * The combinations worth measuring for this config, plainest first so that a
     * measurement cut short still leaves the config usable.
     *
     * [known] is what was found for this config last time. It is tried among the
     * first two, because a line rarely changes from one hour to the next and
     * the answer from last time is therefore the best guess at this time; the
     * rest of the list is still measured, so a line that did change is noticed.
     */
    fun plan(profile: ProfileItem?, known: Choice? = null): List<Choice> {
        val mux = supportsMux(profile)
        val fragment = supportsFragment(profile)
        if (!mux && !fragment) return listOf(PLAIN)
        val choices = mutableListOf(PLAIN)
        // The one that won on this config before goes second: if it still wins,
        // the search is over after two attempts instead of five.
        if (known != null && known != PLAIN && (supportsMux(profile) || !known.mux) &&
            (supportsFragment(profile) || !known.fragment)
        ) {
            choices += known
        }
        if (fragment) choices += FRAGMENT
        if (mux) choices += MUX
        if (fragment) choices += FRAGMENT_FINE
        if (mux && fragment) choices += BOTH
        return choices.distinctBy { it.key }
    }

    // ── Applying and remembering ──────────────────────────────────

    /**
     * The settings a choice is written into are global: the core reads one set
     * when it builds an outbound. A choice found for one config was therefore
     * being applied to whatever came next, which is why a config nobody had
     * looked at was connected with the previous config's answer — the
     * "mux and fragment are not checked when the config changes" that was
     * reported. Every build now says which config it is for, and the settings are
     * rewritten from that config's own answer first.
     */

    /** The combination this config should be built with; null when it was never measured. */
    fun choiceFor(guid: String?): Choice? = if (guid.isNullOrBlank()) null else best(guid)

    /**
     * Writes the combination found for [guid] into the settings the core reads.
     * A config that has never been measured gets the plain one: it is the
     * config as its maker wrote it, which is what "not measured yet" has to
     * mean. Called just before a config is built, so the settings always
     * describe the config being built rather than whichever was built last.
     */
    fun applyFor(guid: String?) {
        apply(choiceFor(guid) ?: PLAIN)
    }

    /** Writes a combination into the settings the core reads when it builds an outbound. */
    fun apply(choice: Choice) {
        MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, choice.mux)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_ENABLED, choice.fragment)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_PACKETS, choice.packets)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_LENGTH, choice.length)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_INTERVAL, choice.interval)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_MAXSPLIT, choice.maxSplit)
    }

    /** The combination found for one config; null when it has not been looked for yet. */
    fun best(guid: String): Choice? =
        MmkvManager.decodeSettingsString(PREF_PREFIX + guid)?.let { key ->
            allChoices.firstOrNull { it.key == key }
        }

    /**
     * How long an answer is trusted. A carrier changes its filtering between
     * the morning and the evening, and a hotel Wi-Fi is not the phone's own
     * line at all; an answer older than this is looked for again.
     */
    private const val TRUSTED_FOR_MILLIS = 12 * 60 * 60 * 1000L

    /** Remembers what won, so the same config is not measured again on the same line. */
    fun remember(guid: String, choice: Choice) {
        MmkvManager.encodeSettings(PREF_PREFIX + guid, choice.key)
        MmkvManager.encodeSettings(PREF_PREFIX + "at_" + guid, System.currentTimeMillis())
    }

    /** Forgets it: the next connection looks again. */
    fun forget(guid: String) {
        MmkvManager.encodeSettings(PREF_PREFIX + guid, "")
        MmkvManager.encodeSettings(PREF_PREFIX + "at_" + guid, 0L)
    }

    /** True when the answer for [guid] was found too long ago to be trusted now. */
    fun stale(guid: String): Boolean {
        val at = MmkvManager.decodeSettingsLong(PREF_PREFIX + "at_" + guid, 0L)
        return at <= 0L || System.currentTimeMillis() - at > TRUSTED_FOR_MILLIS
    }

    /** Forgets every config: for when the phone moves to another network for good. */
    fun forgetAll() {
        MmkvManager.decodeAllServerList().forEach { forget(it) }
    }

    private val allChoices = listOf(PLAIN, MUX, FRAGMENT, FRAGMENT_FINE, BOTH)

    // ── Measuring ─────────────────────────────────────────────────

    /**
     * Milliseconds one real request through the tunnel takes right now, averaged
     * over [repeats]; null when the tunnel carried nothing, which counts as a
     * failure rather than as a slow result.
     */
    fun measure(repeats: Int = 2, timeoutMs: Int = 4000): Long? {
        val port = SettingsManager.getSocksPort()
        if (port == 0) return null
        var total = 0L
        repeat(repeats) {
            val start = System.currentTimeMillis()
            // SOCKS, not HTTP: the core of this build opens no other door, and a
            // request aimed at the wrong kind is answered by nobody.
            val answered = HttpUtil.getUrlContent(
                UrlContentRequest(url = PROBE_URL, timeout = timeoutMs, socksPort = port)
            ) != null
            if (!answered) return null
            total += System.currentTimeMillis() - start
        }
        return total / repeats
    }
}
