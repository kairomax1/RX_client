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

enum class ScanPhase { Idle, OwnLine, Sweeping, Proving, Done }

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
    /** What this line does with the names by itself, asked before any server is touched. */
    val ownLine: XboxDns.OwnLine? = null,
    /**
     * Every server that answered, with what it did for the app, measured. This is
     * the list shown: it starts with what works on this line and ends with what
     * only answers.
     */
    val all: List<XboxDns.Proven> = emptyList(),
)

class XboxDnsViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(XboxDnsUiState())
    val state: StateFlow<XboxDnsUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var cancelled = AtomicBoolean(false)

    fun start(purpose: XboxDns.Purpose) {
        if (job?.isActive == true) return
        val flag = AtomicBoolean(false)
        cancelled = flag
        _state.value = XboxDnsUiState(phase = ScanPhase.Sweeping, purpose = purpose)
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                scan(flag, purpose)
            } catch (e: Exception) {
                AppLog.error(AppLog.Section.Other, "DNS scan stopped by an error: ${e.javaClass.simpleName}: ${e.message}")
            }
            _state.update { it.copy(phase = ScanPhase.Done, stopped = flag.get()) }
        }
    }

    fun stop() {
        cancelled.set(true)
    }

    private suspend fun scan(flag: AtomicBoolean, purpose: XboxDns.Purpose) {
        val context = getApplication<Application>()

        // First: what does this line do by itself? Everything after this is
        // judged against that answer, and it is what decides whether the relays
        // are the answer or only an option.
        _state.update { it.copy(phase = ScanPhase.OwnLine) }
        val own = runCatching { XboxDns.ownLine(flag) }.getOrNull()
        _state.update { it.copy(ownLine = own) }
        if (own != null) {
            AppLog.info(
                AppLog.Section.Other,
                "DNS scan: this line's own resolver answered ${own.resolved} of ${XboxDns.APP_NAMES.size} " +
                    "Xbox names" + (if (own.millis > 0) " in ${own.millis} ms" else "") +
                    if (own.answers) "; Xbox already works here without a relay" else "; Xbox does not answer here without help",
            )
        }

        runCatching { XboxDns.updateList(context) }
        val named = XboxDns.loadNamed(context)
        val addresses = XboxDns.loadAll(context, named)
        _state.update { it.copy(total = addresses.size, phase = ScanPhase.Sweeping) }
        AppLog.info(
            AppLog.Section.Other,
            "DNS scan for ${purpose.name}: asking ${addresses.size} servers",
        )
        val ownOfLine: (Int) -> Boolean = XboxDns::isServiceSpace

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
            names = XboxDns.APP_NAMES,
        )
        val all = heard.toList()
        val asked = _state.value.asked
        val byAddress = all.associateBy { XboxDns.toText(it.address) }
        val common = XboxDns.commonAddresses(all, maxOf(COMMON_FLOOR, all.size / COMMON_SHARE))

        // What answered on this line comes first, in the order it answered; what
        // looks like it might help comes after it. The order matters because the
        // examination is cut off when time runs out, and the servers that did
        // answer are the ones this line can actually use.
        val answeredFirst = all.sortedBy { it.millis }.map { XboxDns.toText(it.address) }
        val suspects = all.filter { XboxDns.isSuspect(it, common, ownOfLine) }.map { XboxDns.toText(it.address) }
        // Known anti-sanction services are examined even when the sweep missed their answer.
        val known = named.filter { it.kind == XboxDns.Kind.Sanction }.flatMap { listOf(it.primary, it.secondary) }.filter { it.isNotEmpty() }
        // Bounded: the examination raises a real TLS connection per name, so a
        // sweep where thousands answered cannot all be examined in the time a
        // person is willing to watch. What answered quickest comes first, so the
        // cut falls on the slowest — the ones least likely to be worth having.
        val candidates = (answeredFirst + suspects + known).distinct().take(EXAMINE_LIMIT)
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

        // What is shown: everything that answered, in the order the sweep heard
        // it — which is the order this line can reach them in — with the relays
        // named as such. Nothing that answered is thrown away for being slow:
        // a second server that answers is an alternative on a line where the
        // first one may be cut off an hour from now.
        val isRelay = relayAddresses.toSet()
        val namedAddresses = named.flatMap { listOf(it.primary, it.secondary) }.toSet()
        val everyAnswered = all.sortedWith(
            compareByDescending<XboxDns.Heard> { XboxDns.toText(it.address) in isRelay }
                .thenBy { XboxDns.toText(it.address) in namedAddresses == false }
                .thenBy { it.millis }
        ).map { reply ->
            val address = XboxDns.toText(reply.address)
            XboxDns.Proven(
                address = address,
                name = named.firstOrNull { it.primary == address || it.secondary == address }?.name.orEmpty(),
                partner = "",
                redirects = address in isRelay,
                passed = 0,
                millis = reply.millis,
            )
        }
        val relays = merge(_state.value.relays, named)
        val ordinary = merge(everyAnswered.filterNot { it.redirects }, named)
        AppLog.info(
            AppLog.Section.Other,
            "DNS scan finished: ${relays.size} servers that relay the app, ${ordinary.size} servers that answer here",
        )
        _state.update { it.copy(relays = relays, ordinary = ordinary, all = relays + ordinary) }
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
        const val EXAMINING_AT_ONCE = 24
        /** How many servers one scan examines for real; the quickest answerers first. */
        const val EXAMINE_LIMIT = 350
        /** An address is "what everyone gives" when more servers than this give it... */
        const val COMMON_FLOOR = 10
        /** ...or more than one in this many of those that answered. */
        const val COMMON_SHARE = 250
    }
}
