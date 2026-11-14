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
    /** Servers going through the proof, and how many are through it. */
    val toProve: Int = 0,
    val proven: Int = 0,
    /** Passed the proof, best first. */
    val results: List<XboxDns.Proven> = emptyList(),
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
        AppLog.info(AppLog.Section.Other, "Xbox DNS scan: asking ${addresses.size} servers")

        val heard = ConcurrentLinkedQueue<XboxDns.Heard>()
        val answered = AtomicInteger(0)
        XboxDns.sweep(
            addresses = addresses,
            cancelled = flag,
            onSent = { asked -> _state.update { it.copy(asked = asked, answered = answered.get()) } },
            onHeard = { reply ->
                if (XboxDns.answersBoth(reply)) {
                    heard.add(reply)
                    answered.incrementAndGet()
                }
            },
        )
        val all = heard.toList()
        val byAddress = all.associateBy { XboxDns.toText(it.address) }
        val relays = all.filter { XboxDns.redirects(it.first, it.second) }
        // Ordinary resolvers all give the same Microsoft addresses; the quickest few are enough.
        val ordinary = all.filter { !XboxDns.redirects(it.first, it.second) }.sortedBy { it.millis }.take(ORDINARY_KEPT)
        val namedAddresses = named.flatMap { listOf(it.primary, it.secondary) }.filter { it in byAddress }
        val candidates = (namedAddresses + (relays + ordinary).map { XboxDns.toText(it.address) }).distinct()
        AppLog.info(
            AppLog.Section.Other,
            "Xbox DNS scan: ${all.size} answered, ${relays.size} look like relays, proving ${candidates.size}",
        )
        _state.update { it.copy(phase = ScanPhase.Proving, asked = it.total, answered = all.size, toProve = candidates.size) }
        if (flag.get()) return

        val passed = ConcurrentLinkedQueue<XboxDns.Proven>()
        val gate = Semaphore(PROVING_AT_ONCE)
        coroutineScope {
            candidates.map { address ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val reply = byAddress.getValue(address)
                        val count = if (flag.get()) 0 else XboxDns.prove(address, flag)
                        if (count >= PASS_MARK) {
                            val service = named.firstOrNull { it.primary == address || it.secondary == address }
                            passed.add(
                                XboxDns.Proven(
                                    address = address,
                                    name = service?.name.orEmpty(),
                                    partner = "",
                                    redirects = XboxDns.redirects(reply.first, reply.second),
                                    passed = count,
                                    millis = reply.millis,
                                )
                            )
                        }
                        _state.update { it.copy(proven = it.proven + 1, results = merge(passed.toList(), named)) }
                    }
                }
            }.awaitAll()
        }
        val results = merge(passed.toList(), named)
        AppLog.info(
            AppLog.Section.Other,
            "Xbox DNS scan finished: ${results.size} passed, ${results.count { it.redirects }} of them relays",
        )
        _state.update { it.copy(results = results) }
    }

    /**
     * One line per service: the two addresses of a named service that both passed are shown
     * together, primary first.
     */
    private fun merge(passed: List<XboxDns.Proven>, named: List<XboxDns.Named>): List<XboxDns.Proven> {
        val byAddress = passed.associateBy { it.address }
        val hidden = HashSet<String>()
        val merged = passed.mapNotNull { result ->
            val service = named.firstOrNull { it.primary == result.address && it.secondary in byAddress }
            if (service != null) {
                hidden.add(service.secondary)
                result.copy(partner = service.secondary)
            } else {
                result
            }
        }
        return XboxDns.rank(merged.filter { it.address !in hidden || it.partner.isNotEmpty() })
    }

    override fun onCleared() {
        cancelled.set(true)
    }

    private companion object {
        const val ORDINARY_KEPT = 20
        const val PROVING_AT_ONCE = 24
        /** Of the four names; three allows one hiccup. */
        const val PASS_MARK = 3
    }
}
