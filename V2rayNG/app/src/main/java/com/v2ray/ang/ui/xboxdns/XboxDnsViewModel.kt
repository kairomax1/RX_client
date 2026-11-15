package com.v2ray.ang.ui.xboxdns

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.XboxDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class ScanPhase { Idle, Sweeping, Proving, Done }

data class XboxDnsUiState(
    val phase: ScanPhase = ScanPhase.Idle,
    /** Addresses the app carries. */
    val total: Int = 0,
    val asked: Int = 0,
    val answered: Int = 0,
    /** Servers being examined, and how many are through. */
    val toProve: Int = 0,
    val proven: Int = 0,
    /**
     * Servers that relay the Xbox app's names. While the scan runs a new one is added at the
     * end, so nothing on screen moves; they are put in order once, when the scan ends.
     */
    val relays: List<XboxDns.Proven> = emptyList(),
    /** Ordinary resolvers, quickest first; filled when the scan ends. */
    val ordinary: List<XboxDns.Proven> = emptyList(),
    val stopped: Boolean = false,
    val purpose: XboxDns.Purpose = XboxDns.Purpose.Xbox,
    /** Services that open on this connection with no special DNS at all. */
    val openAlready: List<String> = emptyList(),
)

class XboxDnsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(XboxDnsUiState())
    val state: StateFlow<XboxDnsUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var cancelled = AtomicBoolean(false)

    /** [games]: ids of the chosen [XboxDns.GAMES]; only read for [XboxDns.Purpose.Games]. */
    fun start(purpose: XboxDns.Purpose, games: Set<String>) {
        if (job?.isActive == true) return
        val flag = AtomicBoolean(false)
        cancelled = flag
        _state.value = XboxDnsUiState(phase = ScanPhase.Sweeping, purpose = purpose)
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                scan(flag, purpose, XboxDns.GAMES.filter { it.id in games })
            } catch (e: Exception) {
                AppLog.error(AppLog.Section.Other, "DNS scan stopped by an error: ${e.javaClass.simpleName}: ${e.message}")
            }
            _state.update { it.copy(phase = ScanPhase.Done, stopped = flag.get()) }
        }
    }

    fun stop() {
        cancelled.set(true)
    }

    /** Back to the question "what for?"; only when no scan runs. */
    fun reset() {
        if (job?.isActive != true) _state.value = XboxDnsUiState()
    }

    private suspend fun scan(flag: AtomicBoolean, purpose: XboxDns.Purpose, games: List<XboxDns.Service>) {
        val context = getApplication<Application>()
        runCatching { XboxDns.updateList(context) }
        val named = XboxDns.loadNamed(context)
        val addresses = XboxDns.loadAll(context, named)
        _state.update { it.copy(total = addresses.size) }
        val discord = purpose == XboxDns.Purpose.Discord
        // What is wanted besides Xbox, and which of it this connection opens with no help:
        // a DNS server gets no credit for a service that was never blocked.
        val wanted = if (discord) listOf(XboxDns.DISCORD) else games
        val openAlready = coroutineScope {
            wanted.map { service -> async(Dispatchers.IO) { service to XboxDns.opensWithout(service, flag) } }.awaitAll()
        }.filter { it.second }.map { it.first }
        val blocked = wanted - openAlready.toSet()
        _state.update { it.copy(openAlready = openAlready.map { service -> service.label }) }
        AppLog.info(
            AppLog.Section.Other,
            "DNS scan for ${purpose.name}: asking ${addresses.size} servers; open with no help: " +
                "${openAlready.joinToString { it.id }.ifEmpty { "none" }}; blocked: ${blocked.joinToString { it.id }.ifEmpty { "none" }}",
        )
        val own: (Int) -> Boolean = if (discord) XboxDns::isCloudflare else XboxDns::isMicrosoft

        val heard = ConcurrentLinkedQueue<XboxDns.Heard>()
        val answered = AtomicInteger(0)
        XboxDns.sweep(
            addresses = addresses,
            cancelled = flag,
            onSent = { asked -> _state.update { it.copy(asked = asked, answered = answered.get()) } },
            onHeard = { reply ->
                if (XboxDns.answered(reply)) {
                    heard.add(reply)
                    answered.incrementAndGet()
                }
            },
            names = if (discord) XboxDns.DISCORD.names.map { XboxDns.hostOf(it) } else XboxDns.APP_NAMES,
        )
        val all = heard.toList()
        val asked = _state.value.asked
        val byAddress = all.associateBy { XboxDns.toText(it.address) }
        val common = XboxDns.commonAddresses(all, maxOf(COMMON_FLOOR, all.size / COMMON_SHARE))
        val suspects = all.filter { XboxDns.isSuspect(it, common, own) }.map { XboxDns.toText(it.address) }
        // Known anti-sanction services are examined even when the sweep missed their answer.
        val known = named.filter { it.kind == XboxDns.Kind.Sanction }.flatMap { listOf(it.primary, it.secondary) }.filter { it.isNotEmpty() }
        val candidates = (known + suspects).distinct()
        AppLog.info(
            AppLog.Section.Other,
            "DNS scan: ${all.size} of $asked answered, ${common.size} addresses are what everyone gives, " +
                "${suspects.size} servers give something else, examining ${candidates.size}",
        )
        if (asked > 2000 && all.size > asked * 9 / 10) {
            AppLog.info(AppLog.Section.Other, "DNS scan: nearly every address answered; this network seems to answer DNS itself, so relays cannot be told apart")
        }
        _state.update { it.copy(phase = ScanPhase.Proving, answered = all.size, toProve = candidates.size) }

        val relayAddresses = ConcurrentLinkedQueue<String>()
        if (!flag.get()) {
            val gate = Semaphore(EXAMINING_AT_ONCE)
            coroutineScope {
                candidates.map { address ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            if (discord) {
                                // Discord: the server counts when Discord is blocked here and opens through it.
                                if (!flag.get() && blocked.isNotEmpty() && XboxDns.carries(address, XboxDns.DISCORD, flag)) {
                                    relayAddresses.add(address)
                                    val result = XboxDns.Proven(
                                        address = address,
                                        name = named.firstOrNull { it.primary == address || it.secondary == address }?.name.orEmpty(),
                                        partner = "",
                                        redirects = true,
                                        passed = XboxDns.DISCORD.names.size,
                                        millis = byAddress[address]?.millis ?: 0L,
                                        services = listOf(XboxDns.DISCORD.label),
                                    )
                                    AppLog.info(AppLog.Section.Other, "DNS scan: $address opens Discord")
                                    _state.update { it.copy(proven = it.proven + 1, relays = it.relays + result) }
                                } else {
                                    _state.update { it.copy(proven = it.proven + 1) }
                                }
                                return@withPermit
                            }
                            val found = if (flag.get()) null else XboxDns.examine(address, common, flag)
                            val relay = found?.relay
                            // Games: the server has to carry Xbox and, of the chosen games that are
                            // blocked here, at least one. With none blocked, Xbox alone decides.
                            val opened = if (relay != null && purpose == XboxDns.Purpose.Games) {
                                blocked.filter { game -> !flag.get() && XboxDns.carries(address, game, flag) }
                            } else {
                                emptyList()
                            }
                            if (relay != null && purpose == XboxDns.Purpose.Games && blocked.isNotEmpty() && opened.isEmpty()) {
                                AppLog.info(AppLog.Section.Other, "DNS scan: $address carries Xbox but none of the chosen games")
                                _state.update { it.copy(proven = it.proven + 1) }
                                return@withPermit
                            }
                            if (found != null && relay != null) {
                                relayAddresses.add(address)
                                if (opened.isNotEmpty()) AppLog.info(AppLog.Section.Other, "DNS scan: $address opens ${opened.joinToString { it.id }}")
                                val result = XboxDns.Proven(
                                    address = address,
                                    name = named.firstOrNull { it.primary == address || it.secondary == address }?.name.orEmpty(),
                                    partner = "",
                                    redirects = true,
                                    passed = found.carried,
                                    millis = byAddress[address]?.millis ?: 0L,
                                    country = found.country,
                                    services = opened.map { it.label },
                                )
                                AppLog.info(
                                    AppLog.Section.Other,
                                    "DNS scan: $address relays ${found.carried} of ${XboxDns.APP_NAMES.size} names through " +
                                        "${XboxDns.toText(relay)}, country seen: ${found.country.ifEmpty { "unknown" }}",
                                )
                                _state.update { it.copy(proven = it.proven + 1, relays = it.relays + result) }
                            } else {
                                _state.update { it.copy(proven = it.proven + 1) }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val isRelay = relayAddresses.toSet()
        val plain = all.filter { XboxDns.toText(it.address) !in isRelay }
        val namedAddresses = named.flatMap { listOf(it.primary, it.secondary) }.toSet()
        val shown = plain.filter { XboxDns.toText(it.address) in namedAddresses } +
            plain.filter { XboxDns.toText(it.address) !in namedAddresses }.sortedBy { it.millis }.take(ORDINARY_KEPT)
        val ordinary = shown.map { reply ->
            val address = XboxDns.toText(reply.address)
            XboxDns.Proven(
                address = address,
                name = named.firstOrNull { it.primary == address || it.secondary == address }?.name.orEmpty(),
                partner = "",
                redirects = false,
                passed = 0,
                millis = reply.millis,
            )
        }
        val relays = merge(_state.value.relays, named)
        AppLog.info(AppLog.Section.Other, "DNS scan finished: ${relays.size} servers that help, ${plain.size} ordinary resolvers")
        _state.update { it.copy(relays = relays, ordinary = merge(ordinary, named)) }
    }

    /**
     * One line per service: the two addresses of a named service that are both in the list
     * are shown together, primary first. Best first.
     */
    private fun merge(found: List<XboxDns.Proven>, named: List<XboxDns.Named>): List<XboxDns.Proven> {
        val byAddress = found.associateBy { it.address }
        val hidden = HashSet<String>()
        val merged = found.map { result ->
            val service = named.firstOrNull { it.primary == result.address && it.secondary in byAddress }
            if (service != null && service.secondary !in hidden) {
                hidden.add(service.secondary)
                result.copy(partner = service.secondary)
            } else {
                result
            }
        }
        return XboxDns.rank(merged.filter { it.address !in hidden })
    }

    override fun onCleared() {
        cancelled.set(true)
    }

    private companion object {
        const val ORDINARY_KEPT = 15
        const val EXAMINING_AT_ONCE = 24
        /** An address is "what everyone gives" when more servers than this give it... */
        const val COMMON_FLOOR = 10
        /** ...or more than one in this many of those that answered. */
        const val COMMON_SHARE = 250
    }
}
