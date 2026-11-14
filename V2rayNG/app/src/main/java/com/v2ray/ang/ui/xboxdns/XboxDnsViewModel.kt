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
)

class XboxDnsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(XboxDnsUiState())
    val state: StateFlow<XboxDnsUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var cancelled = AtomicBoolean(false)

    fun start() {
        if (job?.isActive == true) return
        val flag = AtomicBoolean(false)
        cancelled = flag
        _state.value = XboxDnsUiState(phase = ScanPhase.Sweeping)
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                scan(flag)
            } catch (e: Exception) {
                AppLog.error(AppLog.Section.Other, "Xbox DNS scan stopped by an error: ${e.javaClass.simpleName}: ${e.message}")
            }
            _state.update { it.copy(phase = ScanPhase.Done, stopped = flag.get()) }
        }
    }

    fun stop() {
        cancelled.set(true)
    }

    private suspend fun scan(flag: AtomicBoolean) {
        val context = getApplication<Application>()
        val named = XboxDns.loadNamed(context)
        val addresses = XboxDns.loadAll(context, named)
        _state.update { it.copy(total = addresses.size) }
        AppLog.info(AppLog.Section.Other, "Xbox DNS scan: asking ${addresses.size} servers the Xbox app's names")

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
        )
        val all = heard.toList()
        val asked = _state.value.asked
        val byAddress = all.associateBy { XboxDns.toText(it.address) }
        val common = XboxDns.commonAddresses(all, maxOf(COMMON_FLOOR, all.size / COMMON_SHARE))
        val suspects = all.filter { XboxDns.isSuspect(it, common) }.map { XboxDns.toText(it.address) }
        // Known anti-sanction services are examined even when the sweep missed their answer.
        val known = named.filter { it.kind == XboxDns.Kind.Sanction }.flatMap { listOf(it.primary, it.secondary) }.filter { it.isNotEmpty() }
        val candidates = (known + suspects).distinct()
        AppLog.info(
            AppLog.Section.Other,
            "Xbox DNS scan: ${all.size} of $asked answered, ${common.size} addresses are what everyone gives, " +
                "${suspects.size} servers give something else, examining ${candidates.size}",
        )
        if (asked > 2000 && all.size > asked * 9 / 10) {
            AppLog.info(AppLog.Section.Other, "Xbox DNS scan: nearly every address answered; this network seems to answer DNS itself, so relays cannot be told apart")
        }
        _state.update { it.copy(phase = ScanPhase.Proving, answered = all.size, toProve = candidates.size) }

        val relayAddresses = ConcurrentLinkedQueue<String>()
        if (!flag.get()) {
            val gate = Semaphore(EXAMINING_AT_ONCE)
            coroutineScope {
                candidates.map { address ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val found = if (flag.get()) null else XboxDns.examine(address, common, flag)
                            val relay = found?.relay
                            if (found != null && relay != null) {
                                relayAddresses.add(address)
                                val result = XboxDns.Proven(
                                    address = address,
                                    name = named.firstOrNull { it.primary == address || it.secondary == address }?.name.orEmpty(),
                                    partner = "",
                                    redirects = true,
                                    passed = found.carried,
                                    millis = byAddress[address]?.millis ?: 0L,
                                    country = found.country,
                                )
                                AppLog.info(
                                    AppLog.Section.Other,
                                    "Xbox DNS scan: $address relays ${found.carried} of ${XboxDns.APP_NAMES.size} names through " +
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
        AppLog.info(AppLog.Section.Other, "Xbox DNS scan finished: ${relays.size} relays for the Xbox app, ${plain.size} ordinary resolvers")
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
