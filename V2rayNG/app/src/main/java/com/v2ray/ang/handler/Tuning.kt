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
        val concurrency: String = "8",
    )

    enum class Mode(val fragment: Boolean, val mux: Boolean) {
        BOTH(true, true),
        FRAGMENT_ONLY(true, false),
        MUX_ONLY(false, true),
        NONE(false, false);

        companion object {
            fun of(fragment: Boolean, mux: Boolean): Mode = when {
                fragment && mux -> BOTH
                fragment -> FRAGMENT_ONLY
                mux -> MUX_ONLY
                else -> NONE
            }
        }
    }

    /** Nothing added: the config as its maker wrote it. */
    val PLAIN = Choice("plain", mux = false, fragment = false)

    /** Many requests over one connection. */
    val MUX = Choice("mux", mux = true, fragment = false, concurrency = "8")

    /** Lighter multiplexing — ideal for cellular/high-loss connections. */
    val MUX_LIGHT = Choice("mux-light", mux = true, fragment = false, concurrency = "4")

    /** Higher multiplexing — ideal for broadband/Wi-Fi with many small requests. */
    val MUX_HEAVY = Choice("mux-heavy", mux = true, fragment = false, concurrency = "16")

    /** The handshake split up, in the way that gets past the most filters. */
    val FRAGMENT = Choice("fragment", mux = false, fragment = true)

    /** Wider chunks for faster TLS setup on less aggressive DPI lines. */
    val FRAGMENT_WIDE = Choice(
        "fragment-wide",
        mux = false,
        fragment = true,
        packets = "tlshello",
        length = "100-200",
        interval = "10-20",
    )

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

    fun isFragmentAllowed(): Boolean = runCatching {
        MmkvManager.decodeSettingsBool(AppConfig.PREF_RX_FRAGMENT_ENABLED, true)
    }.getOrDefault(true)

    fun isMuxAllowed(): Boolean = runCatching {
        MmkvManager.decodeSettingsBool(AppConfig.PREF_RX_MUX_ENABLED, true)
    }.getOrDefault(true)

    fun currentMode(): Mode = Mode.of(isFragmentAllowed(), isMuxAllowed())

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

    /** True when there is anything to try for this config under the allowed flags. */
    fun worthTrying(
        profile: ProfileItem?,
        allowMux: Boolean = true,
        allowFragment: Boolean = true,
    ): Boolean =
        (allowMux && supportsMux(profile)) || (allowFragment && supportsFragment(profile))

    /**
     * The combinations worth measuring for this config, plainest first so that a
     * measurement cut short still leaves the config usable.
     *
     * [known] is what was found for this config last time. It is tried among the
     * first two, because a line rarely changes from one hour to the next and
     * the answer from last time is therefore the best guess at this time; the
     * rest of the list is still measured, so a line that did change is noticed.
     */
    fun plan(
        profile: ProfileItem?,
        known: Choice? = null,
        allowMux: Boolean = true,
        allowFragment: Boolean = true,
    ): List<Choice> {
        val mux = allowMux && supportsMux(profile)
        val fragment = allowFragment && supportsFragment(profile)
        if (!mux && !fragment) return listOf(PLAIN)
        val choices = mutableListOf(PLAIN)
        // The one that won on this config before goes second: if it still wins,
        // the search is over after two attempts instead of five.
        if (known != null && known != PLAIN &&
            (mux || !known.mux) &&
            (fragment || !known.fragment)
        ) {
            choices += known
        }
        when {
            fragment && mux -> {
                choices += FRAGMENT
                choices += MUX
                choices += FRAGMENT_FINE
                choices += BOTH
            }
            fragment -> {
                choices += FRAGMENT_WIDE
                choices += FRAGMENT
                choices += FRAGMENT_FINE
            }
            mux -> {
                choices += MUX_LIGHT
                choices += MUX
                choices += MUX_HEAVY
            }
        }
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
    fun choiceFor(guid: String?): Choice? {
        if (guid.isNullOrBlank()) return null
        val saved = bestForCurrentMode(guid) ?: return null
        return sanitizeForCurrentMode(saved)
    }

    /**
     * Writes the combination found for [guid] into the settings the core reads.
     * A config that has never been measured gets the plain one (or the user's
     * active custom settings when explicitly customized), clamped to what the
     * user allowed in Settings.
     */
    fun applyFor(guid: String?) {
        val chosen = choiceFor(guid)
        if (chosen != null) {
            apply(chosen)
        } else {
            val mode = currentMode()
            if (mode == Mode.NONE) {
                apply(PLAIN)
            } else {
                apply(sanitizeForCurrentMode(PLAIN))
            }
        }
    }

    /** Clamps [choice] so neither mux nor fragment is enabled if disabled in Settings. */
    fun sanitizeForCurrentMode(choice: Choice, mode: Mode = currentMode()): Choice =
        choice.copy(
            mux = choice.mux && mode.mux,
            fragment = choice.fragment && mode.fragment,
        )

    /** Builds a [Choice] representing the current user-edited Settings values. */
    fun userCustomChoice(
        allowMux: Boolean = isMuxAllowed(),
        allowFragment: Boolean = isFragmentAllowed(),
    ): Choice = Choice(
        key = "custom",
        mux = allowMux,
        fragment = allowFragment,
        packets = runCatching {
            MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_PACKETS)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "tlshello",
        length = runCatching {
            MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_LENGTH)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "50-100",
        interval = runCatching {
            MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_INTERVAL)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "10-20",
        maxSplit = runCatching {
            MmkvManager.decodeSettingsString(AppConfig.PREF_FRAGMENT_MAXSPLIT)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "10",
        concurrency = runCatching {
            MmkvManager.decodeSettingsString(AppConfig.PREF_MUX_CONCURRENCY)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "8",
    )

    /** Writes a combination into the settings the core reads when it builds an outbound. */
    fun apply(choice: Choice) {
        MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, choice.mux)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_ENABLED, choice.fragment)
        if (choice.mux) {
            MmkvManager.encodeSettings(AppConfig.PREF_MUX_CONCURRENCY, choice.concurrency)
        }
        if (choice.fragment || choice == PLAIN) {
            MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_PACKETS, choice.packets)
            MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_LENGTH, choice.length)
            MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_INTERVAL, choice.interval)
            MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_MAXSPLIT, choice.maxSplit)
        }
    }

    /** Applies [choice] into Settings and saves it as the active best preset for [guid]. */
    fun applyAndRememberPreset(guid: String?, choice: Choice, mode: Mode = currentMode()) {
        val sanitized = sanitizeForCurrentMode(choice, mode)
        MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, sanitized.mux)
        MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_ENABLED, sanitized.fragment)
        if (sanitized.fragment) {
            MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_PACKETS, sanitized.packets)
            MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_LENGTH, sanitized.length)
            MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_INTERVAL, sanitized.interval)
            MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_MAXSPLIT, sanitized.maxSplit)
        }
        if (sanitized.mux) {
            MmkvManager.encodeSettings(AppConfig.PREF_MUX_CONCURRENCY, sanitized.concurrency)
            val xudp = if (sanitized.concurrency == "4") "8" else AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY
            MmkvManager.encodeSettings(AppConfig.PREF_MUX_XUDP_CONCURRENCY, xudp)
            MmkvManager.encodeSettings(AppConfig.PREF_MUX_XUDP_QUIC, "reject")
        }
        if (!guid.isNullOrBlank()) {
            remember(guid, sanitized, mode)
        }
    }

    /**
     * Picks the best baseline preset for the current network type ([isCellular])
     * and direct handshake latency ([probeLatencyMs]) when live tunnel probing
     * cannot be completed (or to seed Mux concurrency).
     */
    fun networkPreset(
        isCellular: Boolean,
        probeLatencyMs: Long?,
        mode: Mode,
    ): Choice {
        if (mode == Mode.NONE) return PLAIN
        val highFriction = isCellular || probeLatencyMs == null || probeLatencyMs > 550L
        val fastBroadband = !isCellular && probeLatencyMs != null && probeLatencyMs < 180L
        val packets = if (probeLatencyMs == null || probeLatencyMs > 700L) "1-3" else "tlshello"
        val length = when {
            probeLatencyMs == null || probeLatencyMs > 700L -> "10-50"
            highFriction -> "50-100"
            fastBroadband -> "100-200"
            else -> "50-100"
        }
        val interval = if (highFriction) "5-10" else "10-20"
        val concurrency = when {
            isCellular -> "4"
            fastBroadband -> "12"
            else -> "8"
        }
        return Choice(
            key = when (mode) {
                Mode.FRAGMENT_ONLY -> if (highFriction) FRAGMENT_FINE.key else FRAGMENT.key
                Mode.MUX_ONLY -> if (isCellular) MUX_LIGHT.key else MUX.key
                Mode.BOTH -> BOTH.key
                Mode.NONE -> PLAIN.key
            },
            mux = mode.mux,
            fragment = mode.fragment,
            packets = packets,
            length = length,
            interval = interval,
            maxSplit = "10",
            concurrency = concurrency,
        )
    }

    /** The combination found for one config; null when it has not been looked for yet. */
    fun best(guid: String): Choice? =
        MmkvManager.decodeSettingsString(PREF_PREFIX + guid)?.let { key ->
            if (key == "custom") userCustomChoice()
            else allChoices.firstOrNull { it.key == key }
        }

    /**
     * Returns [best] only if it was measured under the current Fragment/Mux mode
     * and does not enable a feature currently turned off in Settings.
     */
    fun bestForCurrentMode(guid: String): Choice? {
        val choice = best(guid) ?: return null
        val mode = currentMode()
        val storedMode = MmkvManager.decodeSettingsString(PREF_PREFIX + "mode_" + guid)
        if (!storedMode.isNullOrEmpty() && storedMode != mode.name) return null
        if (choice.mux && !mode.mux) return null
        if (choice.fragment && !mode.fragment) return null
        return choice
    }

    /** Called when the user changes Fragment/Mux toggles or values in Settings. */
    fun onUserSettingsChanged() {
        val mode = currentMode()
        if (!mode.fragment) {
            MmkvManager.encodeSettings(AppConfig.PREF_FRAGMENT_ENABLED, false)
        }
        if (!mode.mux) {
            MmkvManager.encodeSettings(AppConfig.PREF_MUX_ENABLED, false)
        }
        forgetAll()
    }

    /**
     * How long an answer is trusted. A carrier changes its filtering between
     * the morning and the evening, and a hotel Wi-Fi is not the phone's own
     * line at all; an answer older than this is looked for again.
     */
    private const val TRUSTED_FOR_MILLIS = 12 * 60 * 60 * 1000L

    /** Combinations that actually apply mux and/or fragment, used to bypass a -1ms block. */
    fun bypassPlan(
        profile: ProfileItem?,
        allowMux: Boolean = true,
        allowFragment: Boolean = true,
    ): List<Choice> =
        plan(profile, allowMux = allowMux, allowFragment = allowFragment).filter { it != PLAIN }

    /** Marks [guid] as unbypassable after both plain and fragment/mux attempts failed. */
    fun markUnbypassable(guid: String) {
        if (guid.isBlank()) return
        MmkvManager.encodeSettings(PREF_PREFIX + "unbypassable_" + guid, true)
    }

    /** Clears the unbypassable marker (e.g. when a combination starts working). */
    fun clearUnbypassable(guid: String) {
        if (guid.isBlank()) return
        MmkvManager.encodeSettings(PREF_PREFIX + "unbypassable_" + guid, false)
    }

    /** True when fragment and mux were already tested on [guid] and could not bypass the block. */
    fun isUnbypassable(guid: String): Boolean {
        if (guid.isBlank()) return false
        return MmkvManager.decodeSettingsBool(PREF_PREFIX + "unbypassable_" + guid, false)
    }

    /** Remembers what won, so the same config is not measured again on the same line. */
    fun remember(guid: String, choice: Choice, mode: Mode = currentMode()) {
        clearUnbypassable(guid)
        MmkvManager.encodeSettings(PREF_PREFIX + guid, choice.key)
        MmkvManager.encodeSettings(PREF_PREFIX + "mode_" + guid, mode.name)
        MmkvManager.encodeSettings(PREF_PREFIX + "at_" + guid, System.currentTimeMillis())
    }

    /** Forgets it: the next connection looks again. */
    fun forget(guid: String) {
        MmkvManager.encodeSettings(PREF_PREFIX + guid, "")
        MmkvManager.encodeSettings(PREF_PREFIX + "mode_" + guid, "")
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

    private val allChoices = listOf(
        PLAIN,
        MUX,
        MUX_LIGHT,
        MUX_HEAVY,
        FRAGMENT,
        FRAGMENT_WIDE,
        FRAGMENT_FINE,
        BOTH,
    )

    // ── Measuring ─────────────────────────────────────────────────

    /**
     * Milliseconds one real request through the tunnel takes right now, averaged
     * over [repeats]; null when the tunnel carried nothing, which counts as a
     * failure rather than as a slow result.
     */
    fun measure(repeats: Int = 2, timeoutMs: Int = 4000): Long? {
        val port = SettingsManager.getSocksPort()
        if (port == 0) return null
        val fallbackUrl = SettingsManager.getDelayTestUrl()
        val urls = listOf(PROBE_URL, fallbackUrl).distinct()
        var total = 0L
        repeat(repeats) {
            val start = System.currentTimeMillis()
            // SOCKS, not HTTP: the core of this build opens no other door, and a
            // request aimed at the wrong kind is answered by nobody.
            val answered = urls.any { url ->
                HttpUtil.getUrlContent(
                    UrlContentRequest(url = url, timeout = timeoutMs, socksPort = port)
                ) != null
            }
            if (!answered) return null
            total += System.currentTimeMillis() - start
        }
        return total / repeats
    }
}
