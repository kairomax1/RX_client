package com.v2ray.ang.ui.main

import com.v2ray.ang.handler.PublicConfigs
import com.v2ray.ang.handler.Tuning
import com.v2ray.ang.handler.TuningRunner
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.Failover
import com.v2ray.ang.core.AetherEngine
import com.v2ray.ang.handler.ConfigBadge
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.UpdateNotifier
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.matchesPattern
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.handler.SubscriptionInfoParser
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.PatternSyntaxException

/** A failed measurement is "not measured", never a number that can be read. */
internal fun cleanTestDelay(millis: Long): Long = if (millis < 0L) 0L else millis

/** The built-in Mozilla tunnel's place in the config list; not a config to fall back on. */
private const val FIREFOX_TUNNEL_GUID = "firefox-vpn"

private fun applyTestDelayResults(
    servers: List<ServersCache>,
    updates: Map<String, Long>,
): List<ServersCache> = servers.map { server ->
    val delayMillis = updates[server.guid]?.let(::cleanTestDelay)
    if (delayMillis == null || delayMillis == server.testDelayMillis) {
        server
    } else {
        server.copy(testDelayMillis = delayMillis)
    }
}

private fun applyTestDelayResultsToRows(
    rows: List<ServerRowUiModel>,
    updates: Map<String, Long>,
): List<ServerRowUiModel> = rows.map { row ->
    val delayMillis = updates[row.guid]?.let(::cleanTestDelay)
    if (delayMillis == null || delayMillis == row.testDelayMillis) {
        row
    } else {
        row.copy(testDelayMillis = delayMillis)
    }
}

class MainViewModel(
    application: Application,
    private val dataSource: MainDataSource
) : BaseViewModel(application) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val preloadDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ---------- UI state ----------
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedGroupId = dataSource.getSelectedSubscriptionId(),
            selectedGuid = dataSource.getSelectServer(),
            confirmRemove = dataSource.getConfirmRemove(),
            doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // ---------- Keyword filtering ----------
    @Volatile
    private var keywordFilter: String = ""
    private var filterJob: Job? = null

    // ---------- Groups & cache ----------
    private val cacheMutex = Mutex()
    private val groupDataCache = mutableMapOf<String, List<ServersCache>>()
    private val groupUiFlows = ConcurrentHashMap<String, MutableStateFlow<ServerGroupUiState>>()
    private val groupServerFlows = ConcurrentHashMap<String, StateFlow<List<ServersCache>>>()
    private val groupLoadMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverOrderPersistenceJobs = mutableMapOf<String, Job>()

    private var setupGroupJob: Job? = null
    private var preloadJob: Job? = null
    private var selectedGroupLoadJob: Job? = null
    private var reloadJob: Job? = null
    private var testResultFlushJob: Job? = null
    private val pendingTestResults = linkedMapOf<String, Long>()

    private val testRequests = MainTestRequests()
    private var removeInvalidAfterRequestId: String? = null

    /** True while a newer official release is known; drives the red dot on the menu button. */
    val updatePending: StateFlow<Boolean> = UpdateNotifier.pending
    private var bulkTestJob: Job? = null

    // ---------- Automatic test of new configs ----------
    private var autoTestJob: Job? = null
    private var autoTestRequestId: String? = null
    private var autoTestGroups: Map<String, List<String>> = emptyMap()

    /** Set while a test is running whose winner is to be selected and connected. */
    private var connectBestAfterTest = false
    private val _connectBestTick = MutableStateFlow(0)

    /** Goes up each time a tested winner was selected; the screen then (re)starts the tunnel. */
    val connectBestTick: StateFlow<Int> = _connectBestTick.asStateFlow()

    /**
     * Pings every config and connects to the one that answers fastest. Used when DNS mode is
     * switched on, so the user does not have to find a working config first.
     */
    fun testAllAndConnectBest() {
        connectBestAfterTest = true
        viewModelScope.launch {
            // Make sure the built-in Aether profile is there as the fallback for a phone with no
            // working config, and that the list shows it before the test starts.
            val added = withContext(ioDispatcher) {
                val before = dataSource.getSubscriptions().sumOf { dataSource.getServerGuidList(it.guid).size }
                dataSource.ensureAetherProfile()
                dataSource.getSubscriptions().sumOf { dataSource.getServerGuidList(it.guid).size } != before
            }
            if (added) setupGroupTab(forceRefresh = true).join()
            if (withContext(ioDispatcher) { allConfigGuids() }.isEmpty()) {
                connectBestAfterTest = false
                return@launch
            }
            startAutoTest(emptySet())
        }
    }

    /** How many public configs stay after the running test; 0 when the test is not theirs. */
    private var publicKeep = 0

    /** True when the public test in progress ends with a connection to its winner. */
    private var publicConnect = false

    /**
     * Runs the real test (a request through each config) on the freshly collected public
     * configs and keeps the [keep] quickest that answered. The winner (ping, then a short real
     * download on the best few) is selected, and connected to when [connect] is set.
     */
    /**
     * Opens the public group on what the collection already measured.
     *
     * The collection measures every config by making it carry a download before
     * it is kept, so there is nothing left to decide — it is already ordered
     * best-first and each one that is in it answers. This used to start a second
     * test over the whole group, which is where a link that had just been proven
     * was thrown away for a slow ping and a group of twenty was left holding a
     * dozen.
     */
    fun testPublicConfigs(keep: Int, connect: Boolean, openTab: Boolean = connect) {
        viewModelScope.launch {
            setupGroupTab(forceRefresh = true).join()
            if (connect || openTab) subscriptionIdChanged(PublicConfigs.GROUP_ID)
            publicConnect = false
            applyAutoTestOrder(listOf(PublicConfigs.GROUP_ID))
            if (connect) {
                val head = dataSource.getServerGuidList(PublicConfigs.GROUP_ID).firstOrNull()
                if (head != null) {
                    updateSelectedGuid(head)
                    _connectBestTick.update { it + 1 }
                }
            }
            AppLog.info(
                AppLog.Section.Other,
                "Public configs: ${dataSource.getServerGuidList(PublicConfigs.GROUP_ID).size} already measured, opened on the best of them",
            )
        }
    }

    /** What the screen shows while the best mux and fragment settings are looked for. */
    fun setTuning(phase: TuningRunner.Phase, mode: Tuning.Mode = Tuning.currentMode()) {
        // The attempt is already under way while the settings are looked for, so
        // the button offers to cancel it and nothing announces a connection that
        // is not there yet.
        _uiState.update {
            it.copy(
                tuning = phase,
                tuningMode = mode,
                connectPhase = when (phase) {
                    TuningRunner.Phase.Searching -> ConnectPhase.Connecting
                    TuningRunner.Phase.Failed -> ConnectPhase.Failed
                    else -> it.connectPhase
                },
            )
        }
        if (phase == TuningRunner.Phase.Done ||
            phase == TuningRunner.Phase.Unsupported ||
            phase == TuningRunner.Phase.Failed
        ) {
            tuningNoticeJob?.cancel()
            tuningNoticeJob = viewModelScope.launch {
                delay(3_000)
                _uiState.update { it.copy(tuning = TuningRunner.Phase.Idle) }
            }
        }
    }

    private var tuningNoticeJob: Job? = null

    /**
     * True when this config's mux and fragment settings have never been measured
     * here (under the active Settings mode), were measured so long ago that the
     * answer is no longer about this line, or the config previously failed
     * (`-1ms`) and must verify whether allowed tuning can bypass the block.
     */
    fun needsTuning(guid: String?): Boolean {
        if (guid == null) return false
        if (com.v2ray.ang.handler.FragmentVpn.isFragmentProfile(guid)) return false
        val failedBefore = (dataSource.decodeAffiliationInfo(guid)?.testDelayMillis ?: 0L) < 0L
        if (Tuning.currentMode() == Tuning.Mode.NONE) {
            return failedBefore
        }
        return Tuning.bestForCurrentMode(guid) == null ||
            Tuning.stale(guid) ||
            failedBefore
    }

    /** Selects the built-in Aether profile and connects through it. */
    fun connectAether() {
        viewModelScope.launch {
            val guid = withContext(ioDispatcher) { dataSource.ensureAetherProfile() } ?: return@launch
            setupGroupTab(forceRefresh = true).join()
            updateSelectedGuid(guid)
            _connectBestTick.update { it + 1 }
        }
    }

    /** Selects the config with the lowest ping among [guids]; false when none of them answered. */
    private fun selectFastest(guids: List<String>): Boolean {
        val best = guids
            .mapNotNull { guid -> dataSource.getTestDelayMillis(guid).takeIf { it > 0 }?.let { guid to it } }
            .minByOrNull { it.second }
        // No config answered: fall back to the built-in Aether route, when this build has it.
        val chosen = best?.first ?: dataSource.ensureAetherProfile() ?: return false
        updateSelectedGuid(chosen)
        return true
    }

    @Volatile
    private var badges: Map<String, ConfigBadge> = emptyMap()

    private val initialPageReady = CompletableDeferred<Unit>()

    // ---------- Service events ----------
    init {
        collectServiceEvents()
        collectPublicConfigUpdates()
        setupGroupTab()
        refreshUiSettings()
    }

    private fun collectPublicConfigUpdates() {
        viewModelScope.launch {
            com.v2ray.ang.handler.PublicConfigs.groupUpdated
                .drop(1)
                .collectLatest {
                    delay(120L)
                    setupGroupTab(forceRefresh = true).join()
                    val hasPublic = withContext(ioDispatcher) {
                        dataSource.getServerGuidList(com.v2ray.ang.handler.PublicConfigs.GROUP_ID).isNotEmpty()
                    }
                    val currentId = _uiState.value.selectedGroupId
                    val currentEmpty = withContext(ioDispatcher) {
                        dataSource.getServerGuidList(currentId).isEmpty()
                    }
                    if (hasPublic && (currentEmpty || currentId == com.v2ray.ang.handler.PublicConfigs.GROUP_ID)) {
                        subscriptionIdChanged(com.v2ray.ang.handler.PublicConfigs.GROUP_ID)
                    }
                }
        }
    }

    private fun collectServiceEvents() {
        viewModelScope.launch {
            dataSource.mainServiceEvent.collect { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.StateRunning -> {
                updateRunningState(true, clearTestingText = false)
                // The service was already up (app reopened): nothing to verify again.
                if (uiState.value.connectPhase == ConnectPhase.Idle) {
                    _uiState.update { it.copy(connectPhase = ConnectPhase.Connected) }
                }
            }

            MainServiceEvent.StateNotRunning -> {
                updateRunningState(false, clearTestingText = false)
                onServiceStopped()
            }

            // No toast on connect or disconnect: the connect button itself carries the state.
            MainServiceEvent.StateStartSuccess -> {
                updateRunningState(true)
                // The service may have moved to another config on its own; the
                // list follows it, so what is shown is what is carrying the traffic.
                if (dataSource.getSelectServer() != uiState.value.selectedGuid) refreshSelectedGuid()
                beginConnectionVerification()
                // With the tunnel up GitHub is reachable: the best moment to look for an update.
                viewModelScope.launch {
                    delay(4000)
                    UpdateNotifier.checkNow(getApplication<Application>())
                }
            }

            is MainServiceEvent.StateStartFailure -> {
                LogUtil.w(AppConfig.TAG, "Main: service start failed: ${event.message.orEmpty()}")
                updateRunningState(false)
                onConnectFailed()
            }

            MainServiceEvent.StateStopSuccess -> {
                updateRunningState(false)
                onServiceStopped()
            }

            is MainServiceEvent.MeasureDelayResult -> {
                if (!uiState.value.isRunning || !testRequests.completeCurrent(event.requestId)) return
                _uiState.update { it.copy(isTesting = testRequests.isTesting, status = MainStatus.ConnectionTest(event.result)) }
                if (event.result.delayMillis >= 0) {
                    // Every test re-reads the exit address, so a rotating one is shown as it changes.
                    _uiState.update {
                        it.copy(
                            exitIp = event.result.ipAddress?.takeIf { ip -> ip.isNotBlank() } ?: it.exitIp,
                            exitCountry = event.result.country?.takeIf { c -> c.isNotBlank() } ?: it.exitCountry,
                        )
                    }
                }
                onCurrentDelayMeasured(event.result.delayMillis)
            }

            is MainServiceEvent.MeasureConfigSuccess -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                queueTestResult(event.result, request)
            }

            is MainServiceEvent.MeasureConfigNotify -> {
                if (event.requestId == testRequests.bulk?.id) {
                    _uiState.update { it.copy(status = MainStatus.TestProgress(event.progress)) }
                }
            }

            is MainServiceEvent.MeasureConfigFinish -> {
                val request = testRequests.bulk?.takeIf { it.id == event.requestId } ?: return
                val scheduledFlush = testResultFlushJob
                testResultFlushJob = viewModelScope.launch {
                    scheduledFlush?.cancelAndJoin()
                    if (testRequests.bulk?.id != request.id) return@launch
                    flushPendingTestResults(request)
                    onTestsFinished(request.id)
                }
            }

            is MainServiceEvent.MeasureDelayCancelled -> {
                if (testRequests.completeCurrent(event.requestId)) resetTestStatus()
            }

            is MainServiceEvent.MeasureConfigCancelled -> {
                if (testRequests.completeBulk(event.requestId) != null) {
                    cancelPendingTestResults()
                    resetTestStatus()
                }
            }
        }
    }

    private fun queueTestResult(result: RealPingResult, request: MainTestRequests.Bulk) {
        pendingTestResults[result.guid] = result.delayMillis
        if (testResultFlushJob?.isActive == true) return

        testResultFlushJob = viewModelScope.launch {
            while (pendingTestResults.isNotEmpty()) {
                delay(TEST_RESULT_FLUSH_INTERVAL_MS)
                flushPendingTestResults(request)
            }
        }
    }

    private suspend fun flushPendingTestResults(request: MainTestRequests.Bulk) {
        if (pendingTestResults.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return

        val updates = cacheMutex.withLock {
            val drained = pendingTestResults.toMap()
            pendingTestResults.clear()
            groupDataCache[request.groupId]?.let { cached ->
                groupDataCache[request.groupId] = applyTestDelayResults(cached, drained)
            }
            drained
        }
        if (updates.isEmpty()) return
        if (testRequests.bulk?.id != request.id) return
        mutableServerGroupState(request.groupId).update { current ->
            current.copy(
                servers = applyTestDelayResults(current.servers, updates),
                rows = applyTestDelayResultsToRows(current.rows, updates),
            )
        }
    }

    // ---------- Connection phase ----------
    private var verifyAttempts = 0
    private var connectWatchdog: Job? = null
    private var failureResetJob: Job? = null

    /** Set while the owner is the one who asked for the tunnel to come down. */
    private var stopRequested = false

    /** Ends the round of automatic moves once the connection has carried traffic long enough. */
    private var settleJob: Job? = null

    /**
     * The owner asked to connect: a fresh round starts, so a config that failed
     * before is offered again. The button shows "connecting" until the tunnel
     * is proven.
     */
    fun onConnectRequested() {
        Failover.reset()
        settleJob?.cancel()
        beginConnectAttempt()
    }

    /**
     * The tunnel went down and another config is worth trying: the next one of
     * the group is taken, quietly, and the tunnel is raised on it. True when it
     * moved, so the caller knows the failure is already being dealt with and
     * must not be shown as one.
     */
    private fun tryNextConfig(): Boolean {
        if (!dataSource.isAutoFailoverEnabled()) return false
        if (!Failover.canMove()) return false
        // The app's own ways of reaching the internet are not configs to fall
        // back on: moving to one of them would change what the phone is doing,
        // not only where it does it. Reading every config is only worth it when
        // the engine that has to be told apart from the rest is even there.
        val aetherPossible = AetherEngine.isAvailable(getApplication())
        val guids = dataSource.getServerGuidList(uiState.value.selectedGroupId).filter { guid ->
            guid != FIREFOX_TUNNEL_GUID &&
                !com.v2ray.ang.handler.FragmentVpn.isFragmentProfile(guid) &&
                !(aetherPossible && AetherEngine.isAetherProfile(dataSource.decodeServerConfig(guid)))
        }
        val next = Failover.next(
            current = uiState.value.selectedGuid,
            guids = guids,
            pingOf = { guid -> dataSource.getTestDelayMillis(guid) },
        ) ?: return false
        AppLog.info(AppLog.Section.Connection, "Failover: another config of this group is taken instead")
        Failover.moved(next)
        dataSource.setSelectServer(next)
        _uiState.update { it.copy(selectedGuid = next) }
        beginConnectAttempt()
        // A tunnel that is standing is restarted so that the new config is read;
        // one that is not is simply started.
        dataSource.startService()
        return true
    }

    /** The owner asked for the tunnel to come down; nothing is taken in its place. */
    fun onStopRequested() {
        stopRequested = true
        connectWatchdog?.cancel()
        settleJob?.cancel()
        Failover.reset()
    }

    private fun beginConnectAttempt() {
        stopRequested = false
        failureResetJob?.cancel()
        verifyAttempts = 0
        AppLog.info(AppLog.Section.Connection, "Connect requested")
        // A little slack: file times are coarse on some storage.
        connectRequestedAt = System.currentTimeMillis() - 2000
        // The address stays on the line while the new one is being found, and is
        // replaced when it arrives. Wiping it here would leave an empty line for
        // as long as the connection takes, only to type the same digits again.
        _uiState.update { it.copy(connectPhase = ConnectPhase.Connecting, pingMillis = null) }
        connectWatchdog?.cancel()
        connectWatchdog = viewModelScope.launch {
            // Aether first has to find a route that works on this network; that takes far
            // longer than a ready-made config needs.
            val aether = withContext(ioDispatcher) { dataSource.isAetherSelected() }
            delay(if (aether) AetherEngine.MAX_SEARCH_MILLIS + CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS)
            if (uiState.value.connectPhase == ConnectPhase.Connecting) {
                AppLog.error(AppLog.Section.Connection, "No working connection within the time limit")
                val wasRunning = uiState.value.isRunning
                // Nothing came within the time limit, which means it was slow,
                // not that the config is dead. Another config is left to the
                // watcher, which measures the traffic itself; a tunnel that was
                // only slow is not torn down for being slow.
                onConnectFailed(canMove = false)
                if (wasRunning) dataSource.sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
            }
        }
    }

    /** The core is up; a real request through it decides whether the connection counts. */
    private fun beginConnectionVerification() {
        if (uiState.value.connectPhase != ConnectPhase.Connecting) {
            // Started from outside the screen (tile, widget, restart): still measure once.
            _uiState.update { it.copy(connectPhase = ConnectPhase.Connected) }
        }
        viewModelScope.launch {
            if (withContext(ioDispatcher) { dataSource.isAetherSelected() } && !awaitAetherRoute()) return@launch
            // The tunnel needs a moment after the core reports success.
            delay(VERIFY_SETTLE_MS)
            if (uiState.value.isRunning) testCurrentServerRealPing()
        }
    }

    /**
     * Waits while the Aether engine walks through its routes, showing which one it is on.
     * True once a route works; false when none did (the attempt is then failed and stopped)
     * or the user gave up meanwhile.
     */
    /** When the current attempt began; an Aether status written before it belongs to an older one. */
    private var connectRequestedAt = 0L

    private suspend fun awaitAetherRoute(): Boolean {
        try {
            val deadline = System.currentTimeMillis() + AetherEngine.MAX_SEARCH_MILLIS
            while (System.currentTimeMillis() < deadline) {
                if (!uiState.value.isRunning) return false
                val status = withContext(ioDispatcher) { dataSource.aetherStatus(connectRequestedAt) }
                when (status?.phase) {
                    AetherEngine.Phase.Ready -> return true
                    AetherEngine.Phase.Failed -> break
                    else -> _uiState.update { it.copy(aetherSearch = status) }
                }
                delay(AETHER_POLL_MS)
            }
            if (uiState.value.connectPhase == ConnectPhase.Connecting) {
                if (!onConnectFailed()) dataSource.sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
            }
            return false
        } finally {
            _uiState.update { it.copy(aetherSearch = null) }
        }
    }

    private fun onCurrentDelayMeasured(delayMillis: Long) {
        val phase = uiState.value.connectPhase
        if (phase != ConnectPhase.Connecting) {
            _uiState.update { it.copy(pingMillis = delayMillis) }
            return
        }
        if (delayMillis >= 0) {
            AppLog.info(AppLog.Section.Connection, "Connected; traffic flows ($delayMillis ms)")
            connectWatchdog?.cancel()
            _uiState.update { it.copy(connectPhase = ConnectPhase.Connected, pingMillis = delayMillis) }
            rememberIfItLasts()
            return
        }
        // A probe that answers nothing right after the tunnel came up says more
        // about the tunnel than about the config: the interface has only just
        // been handed the routes, and the first name being looked up can time out
        // on its own. Giving it one second and then giving up was what turned a
        // working config into "two connection tests in a row failed" — the log
        // shows exactly that, a failure pair and a good answer a moment later.
        // Each retry now waits longer than the one before it, and three are
        // allowed, so a line that needs four seconds to settle still connects.
        verifyAttempts++
        if (verifyAttempts < VERIFY_MAX_ATTEMPTS) {
            val wait = VERIFY_RETRY_MS * verifyAttempts
            viewModelScope.launch {
                delay(wait)
                if (uiState.value.isRunning && uiState.value.connectPhase == ConnectPhase.Connecting) {
                    testCurrentServerRealPing()
                }
            }
        } else {
            AppLog.error(
                AppLog.Section.Connection,
                "$VERIFY_MAX_ATTEMPTS connection tests in a row failed; the config is not carrying traffic",
            )
            if (!onConnectFailed()) dataSource.sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
        }
    }

    fun onConnectCancelled() {
        AppLog.info(AppLog.Section.Connection, "Connecting was cancelled by the user")
        connectWatchdog?.cancel()
        _uiState.update { it.copy(connectPhase = ConnectPhase.Idle, pingMillis = null, exitIp = null, exitCountry = null) }
    }

    /**
     * Forgets the round of automatic moves once this connection has carried
     * traffic for a while. A config that only came up and fell over again does
     * not earn that: the next move then continues where the round left off
     * instead of starting from the same config again.
     */
    private fun rememberIfItLasts() {
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            delay(Failover.SETTLE_MILLIS)
            if (uiState.value.isRunning) Failover.reset()
        }
    }

    /**
     * Red button, shake, vibration and the floating message; then back to the
     * idle button. Returns true when another config was taken instead, in which
     * case nothing of that is shown — the move is not a failure until there is
     * nothing left to move to.
     */
    fun onConnectFailed(canMove: Boolean = true): Boolean {
        connectWatchdog?.cancel()
        if (canMove && tryNextConfig()) return true
        AppLog.error(AppLog.Section.Connection, "Connection attempt failed; the service is being stopped")
        _uiState.update {
            it.copy(connectPhase = ConnectPhase.Failed, pingMillis = null, exitIp = null, exitCountry = null, failureTick = it.failureTick + 1)
        }
        failureResetJob?.cancel()
        failureResetJob = viewModelScope.launch {
            delay(FAILURE_FEEDBACK_MS)
            _uiState.update {
                if (it.connectPhase == ConnectPhase.Failed) it.copy(connectPhase = ConnectPhase.Idle) else it
            }
        }
        return false
    }

    private fun onServiceStopped() {
        val stoppedByHand = stopRequested
        stopRequested = false
        when (uiState.value.connectPhase) {
            // A stop that follows a failed attempt must not wipe the failure feedback early.
            ConnectPhase.Failed -> Unit
            // The service died before the connection was ever proven. A stop the
            // owner asked for is not answered by another config.
            ConnectPhase.Connecting -> if (!stoppedByHand && verifyAttempts > 0) onConnectFailed()
            // A stop while it was carrying traffic is taken as asked for: whether
            // it came from the button or from the notification cannot be told
            // apart here, and taking another config would fight the owner.
            else -> markStopped()
        }
    }

    private fun markStopped() {
        connectWatchdog?.cancel()
        _uiState.update { it.copy(connectPhase = ConnectPhase.Idle, pingMillis = null, exitIp = null, exitCountry = null) }
    }

    internal fun formatStatus(status: MainStatus): String = when (status) {
        MainStatus.Disconnected -> dataSource.getString(R.string.connection_not_connected)
        MainStatus.Connected -> dataSource.getString(R.string.connection_connected)
        MainStatus.Testing -> dataSource.getString(R.string.connection_test_testing)
        is MainStatus.TestProgress -> dataSource.getString(
            R.string.connection_running_task_left,
            status.progress
        )

        is MainStatus.ConnectionTest -> formatConnectionTestResult(status.result)
    }

    private fun formatConnectionTestResult(result: ConnectionTestResult): String {
        val status = if (result.delayMillis >= 0) {
            val delay = dataSource.getString(R.string.server_test_delay_value, result.delayMillis)
            dataSource.getString(R.string.connection_test_available, delay)
        } else {
            val detail = result.errorMessage.ifBlank {
                dataSource.getString(R.string.connection_test_empty_message)
            }
            dataSource.getString(R.string.connection_test_error, detail)
        }

        if (result.delayMillis < 0 || (result.country == null && result.ipAddress == null)) {
            return status
        }

        val unknown = dataSource.getString(R.string.value_unknown)
        return "$status\n(${result.country ?: unknown}) ${result.ipAddress ?: unknown}"
    }

    // ---------- Public state accessors ----------
    fun serversForGroup(groupId: String): StateFlow<List<ServersCache>> =
        groupServerFlows.computeIfAbsent(groupId) {
            val groupState = mutableServerGroupState(groupId)
            groupState
                .map { it.servers }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                    initialValue = groupState.value.servers,
                )
        }

    internal fun serverGroupState(groupId: String): StateFlow<ServerGroupUiState> =
        mutableServerGroupState(groupId).asStateFlow()

    private fun mutableServerGroupState(groupId: String): MutableStateFlow<ServerGroupUiState> =
        groupUiFlows.computeIfAbsent(groupId) { MutableStateFlow(ServerGroupUiState()) }

    private fun currentServers(): List<ServersCache> =
        mutableServerGroupState(uiState.value.selectedGroupId).value.servers

    // ---------- Action handler ----------
    fun onAction(action: MainAction) {
        when (action) {
            MainAction.Initialize -> initialize()
            MainAction.RefreshGroups -> setupGroupTab(forceRefresh = true)
            MainAction.TestAllServers -> testAllRealPing(true)
            // The thunder pings the tab the user is looking at, not every list they own.
            MainAction.PingAndSortAll -> startAutoTest(emptySet(), uiState.value.selectedGroupId)
            MainAction.TestRealAllServers -> testAllRealPing()
            is MainAction.TestGroupRealPing -> testAllRealPing(groupId = action.groupId)
            MainAction.PingThenRemoveInvalid -> pingThenRemoveInvalid()
            is MainAction.CopySelected -> copySelected(action.guids)
            MainAction.CancelTesting -> cancelAllPing()
            MainAction.RemoveAllServers -> removeAllServerAsync()
            MainAction.RemoveDuplicateServers -> removeDuplicateServerAsync()
            MainAction.RemoveInvalidServers -> removeInvalidServerAsync()
            MainAction.SortByTestResults -> sortByTestResultsAsync()
            MainAction.UpdateSubscriptions -> importConfigViaSub(uiState.value.selectedGroupId)
            is MainAction.UpdateGroupSubscription -> importConfigViaSub(action.groupId)
            MainAction.ExportAll -> exportAllAsync()
            is MainAction.SelectGroup -> subscriptionIdChanged(action.groupId)
            is MainAction.SelectServer -> updateSelectedGuid(action.guid)
            is MainAction.RemoveServer -> removeServerAndRefresh(action.guid)
            is MainAction.Search -> filterConfig(action.query)
            is MainAction.ImportBatchConfig -> onImportRequested(action.configText, action.askSubName)
            is MainAction.AnswerSubName -> onSubNameAnswered(action.name)
            MainAction.LocateHandled -> consumeLocateTarget()
            is MainAction.ShareQRCode -> {
                val bitmap = dataSource.share2QRCode(action.guid)
                _uiState.update { it.copy(shareQRCodeBitmap = bitmap) }
            }

            MainAction.DismissQRCodeDialog -> {
                _uiState.update { it.copy(shareQRCodeBitmap = null) }
            }

            MainAction.ToggleService,
            MainAction.TestCurrentServer,
            MainAction.ImportQRcode,
            MainAction.ImportClipboard,
            MainAction.ImportConfigLocal,
            is MainAction.ImportManually,
            MainAction.RestartService,
            MainAction.LocateSelectedServer,
            is MainAction.EditServer,
            is MainAction.ShareClipboard,
            is MainAction.ShareFullContent,
            is MainAction.OpenSubscriptionLink -> {
                // Handled by Activity via its onAction lambda
            }
        }
    }

    // ---------- Initialization ----------
    fun initialize() {
        viewModelScope.launch(preloadDispatcher) {
            try {
                initialPageReady.await()
                delay(32)
                dataSource.initAssets()
                dataSource.syncSubscriptions()
                val keep = dataSource.renewPublicConfigs(_uiState.value.isRunning)
                if (keep > 0) {
                    val currentEmpty = dataSource.getServerGuidList(_uiState.value.selectedGroupId).isEmpty()
                    val onPublic = _uiState.value.selectedGroupId == com.v2ray.ang.handler.PublicConfigs.GROUP_ID
                    testPublicConfigs(keep, connect = false, openTab = currentEmpty || onPublic)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Main background initialization failed", error)
            }
        }
    }

    fun refreshUiSettings() {
        viewModelScope.launch(ioDispatcher) {
            val confirmRemove = dataSource.getConfirmRemove()
            val doubleColumnDisplay = dataSource.getDoubleColumnDisplay()
            val routingMode = dataSource.getPerAppRoutingMode()
            val routedAppCount = dataSource.getRoutedAppCount()
            _uiState.update {
                it.copy(
                    confirmRemove = confirmRemove,
                    doubleColumnDisplay = doubleColumnDisplay,
                    routingMode = routingMode,
                    routedAppCount = routedAppCount,
                )
            }
        }
    }

    // ---------- Group & server loading ----------
    private suspend fun buildServersCache(guids: List<String>): List<ServersCache> =
        guids.mapNotNull { guid ->
            currentCoroutineContext().ensureActive()
            val profile = dataSource.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = dataSource.decodeAffiliationInfo(guid)
            ServersCache(
                guid = guid,
                profile = profile.copy(),
                testDelayMillis = affiliation?.testDelayMillis ?: 0L
            )
        }

    private suspend fun loadGroup(
        groupId: String,
        forceRefresh: Boolean = false
    ): List<ServersCache> {
        val loadMutex = groupLoadMutexes.computeIfAbsent(groupId) { Mutex() }
        return loadMutex.withLock {
            if (!forceRefresh) {
                cacheMutex.withLock { groupDataCache[groupId]?.let { return@withLock it } }
            }
            val servers = buildServersCache(dataSource.getServerGuidList(groupId))
            currentCoroutineContext().ensureActive()
            cacheMutex.withLock { groupDataCache[groupId] = servers }
            servers
        }
    }

    private fun applyKeywordFilter(servers: List<ServersCache>): List<ServersCache> {
        val keyword = keywordFilter.trim()
        if (keyword.isEmpty()) return servers
        val regex = try {
            Regex(keyword, RegexOption.IGNORE_CASE)
        } catch (_: PatternSyntaxException) {
            return servers
        }
        return servers.filter { cache ->
            val profile = cache.profile
            profile.remarks.matchesPattern(regex, keyword) ||
                    profile.description.orEmpty().matchesPattern(regex, keyword) ||
                    profile.server.orEmpty().matchesPattern(regex, keyword) ||
                    profile.configType.name.matchesPattern(regex, keyword)
        }
    }

    private fun updateGroupUi(groupId: String, servers: List<ServersCache>) {
        val filteredServers = applyKeywordFilter(servers)
        mutableServerGroupState(groupId).value = ServerGroupUiState(
            servers = filteredServers,
            rows = buildServerRows(groupId, filteredServers)
        )
    }

    private fun buildServerRows(groupId: String, servers: List<ServersCache>): List<ServerRowUiModel> {
        val subscriptionRemarks = if (groupId.isEmpty()) {
            servers.asSequence()
                .map { it.profile.subscriptionId }
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { subscriptionId ->
                    dataSource.getSubscriptionItem(subscriptionId)?.remarks.orEmpty()
                }
        } else {
            emptyMap()
        }
        return servers.map { server ->
            buildServerRowUiModel(
                server = server,
                subscriptionRemarks = subscriptionRemarks[server.profile.subscriptionId].orEmpty(),
                badge = badges[server.guid],
            )
        }
    }

    fun getSubscriptions(): List<SubscriptionCache> = dataSource.getSubscriptions()

    private fun resolveSelectedGroup(groups: List<GroupMapItem>): String {
        val current = uiState.value.selectedGroupId
        val resolved = when {
            groups.isEmpty() -> ""
            groups.any { it.id == current } -> current
            else -> groups.first().id
        }
        if (resolved != current) {
            dataSource.setSelectedSubscriptionId(resolved)
        }
        return resolved
    }

    private fun radialPreloadOrder(groups: List<GroupMapItem>, selectedIndex: Int): List<String> {
        if (groups.isEmpty()) return emptyList()
        val result = ArrayList<String>((groups.size - 1).coerceAtLeast(0))
        for (distance in 1 until groups.size) {
            val right = selectedIndex + distance
            val left = selectedIndex - distance
            if (right in groups.indices) result += groups[right].id
            if (left in groups.indices) result += groups[left].id
        }
        return result
    }

    fun setupGroupTab(forceRefresh: Boolean = false): Job {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()

        return viewModelScope.launch(ioDispatcher) {
            try {
                if (forceRefresh) {
                    cacheMutex.withLock { groupDataCache.clear() }
                }
                badges = computeBadges()
                val groups = dataSource.getSubscriptions().map {
                    GroupMapItem(
                        id = it.guid,
                        remarks = if (it.guid == AppConfig.DEFAULT_SUBSCRIPTION_ID && it.subscription.remarks == "Default") {
                            dataSource.getString(R.string.group_default_name)
                        } else {
                            it.subscription.remarks
                        },
                        usageInfo = it.subscription.usageInfo?.let { info ->
                            SubscriptionInfoParser.withWebPageFallback(info, it.subscription.url)
                        },
                    )
                }
                val selectedGroup = resolveSelectedGroup(groups)
                val validIds = groups.mapTo(HashSet()) { it.id }
                groupUiFlows.keys.removeAll { it !in validIds }
                groupServerFlows.keys.removeAll { it !in validIds }
                groupLoadMutexes.keys.removeAll { it !in validIds }

                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedGroupId = selectedGroup,
                        selectedGuid = dataSource.getSelectServer(),
                    )
                }
                groups.forEach { mutableServerGroupState(it.id) }

                if (groups.isEmpty()) {
                    cacheMutex.withLock { groupDataCache.clear() }
                    return@launch
                }

                val selectedServers = loadGroup(selectedGroup, forceRefresh)
                updateGroupUi(selectedGroup, selectedServers)

                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }

                val selectedIndex =
                    groups.indexOfFirst { it.id == selectedGroup }.coerceAtLeast(0)
                val preloadOrder = radialPreloadOrder(groups, selectedIndex)
                preloadJob = viewModelScope.launch(preloadDispatcher) {
                    preloadOrder.forEach { groupId ->
                        ensureActive()
                        delay(32)
                        val servers = loadGroup(groupId, forceRefresh)
                        updateGroupUi(groupId, servers)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to set up group tabs", error)
            } finally {
                if (!initialPageReady.isCompleted) {
                    initialPageReady.complete(Unit)
                }
            }
        }.also { setupGroupJob = it }
    }

    // ---------- Business actions (coroutine-based) ----------
    /**
     * A single subscription link from the clipboard is first offered a name; everything else is
     * imported straight away.
     */
    private fun onImportRequested(configText: String, askSubName: Boolean) {
        if (!askSubName) {
            importBatchConfig(configText)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val link = singleSubscriptionLink(configText, dataSource::isSubscriptionUrl)
                ?.takeIf { url -> dataSource.getSubscriptions().none { it.subscription.url == url } }
            if (link == null) {
                withContext(Dispatchers.Main) { importBatchConfig(configText) }
            } else {
                _uiState.update { it.copy(subNameRequest = link) }
            }
        }
    }

    private fun onSubNameAnswered(typed: String?) {
        val link = uiState.value.subNameRequest ?: return
        _uiState.update { it.copy(subNameRequest = null) }
        viewModelScope.launch(ioDispatcher) {
            val name = resolveSubscriptionName(
                typed = typed,
                base = dataSource.getString(R.string.sub_name_default),
                existing = dataSource.getSubscriptions().map { it.subscription.remarks },
            )
            withContext(Dispatchers.Main) { importBatchConfig(link, name) }
        }
    }

    private fun importBatchConfig(configText: String, subscriptionName: String? = null) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val before = allConfigGuids()
                    val (count, countSub) = dataSource.importBatchConfig(
                        configText, uiState.value.selectedGroupId, true, subscriptionName
                    )
                    when {
                        count > 0 -> {
                            toast(dataSource.getString(R.string.title_import_config_count, count))
                            setupGroupTab(forceRefresh = true)
                        }

                        countSub > 0 -> setupGroupTab(forceRefresh = true)
                        else -> toastError(R.string.toast_failure)
                    }
                    if (count > 0 || countSub > 0) startAutoTest(before)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to import batch config", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun allConfigGuids(): Set<String> =
        dataSource.getSubscriptions().flatMapTo(HashSet()) { dataSource.getServerGuidList(it.guid) }

    private fun computeBadges(): Map<String, ConfigBadge> {
        val result = HashMap<String, ConfigBadge>()
        dataSource.getSubscriptions().forEach { result.putAll(dataSource.badgesFor(dataSource.getServerGuidList(it.guid))) }
        return result
    }

    /**
     * Tests every config that was not there [before], in [groupId] when one is given and in
     * every list otherwise. The ping runs through the same test service as "test all", so the
     * list fills in live; when it finishes, [finishAutoTest] measures speed, hands out the
     * badges and sorts the list.
     */
    private fun startAutoTest(before: Set<String>, groupId: String? = null) {
        viewModelScope.launch {
            try {
                val freshByGroup = withContext(ioDispatcher) {
                    dataSource.getSubscriptions()
                        .filter { groupId.isNullOrBlank() || it.guid == groupId }
                        .associate { sub ->
                            sub.guid to dataSource.getServerGuidList(sub.guid).filter { it !in before }
                        }.filterValues { it.isNotEmpty() }
                }
                if (freshByGroup.isEmpty()) {
                    connectBestAfterTest = false
                    return@launch
                }
                // Let the reload that follows an import settle, so results land in a loaded list.
                setupGroupJob?.join()
                autoTestJob?.cancel()
                cancelAllPing()
                removeInvalidAfterRequestId = null

                val fresh = freshByGroup.values.flatten()
                // Live results are shown in the group that received the most new configs.
                val liveGroup = freshByGroup.maxByOrNull { it.value.size }?.key.orEmpty()
                val request = testRequests.beginBulk(liveGroup)
                autoTestRequestId = request.id
                autoTestGroups = freshByGroup
                _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
                // No subscription id on purpose: the service's "remove failed" and "sort" options
                // belong to a test the user started, not to this one.
                dataSource.sendMsg2TestService(
                    TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_START, serverGuids = fresh),
                    request.id,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Automatic config test could not start", e)
            }
        }
    }

    private fun finishAutoTest(groups: Map<String, List<String>>) {
        autoTestJob?.cancel()
        autoTestJob = viewModelScope.launch(ioDispatcher) {
            try {
                val keep = publicKeep
                publicKeep = 0
                if (keep > 0 && PublicConfigs.GROUP_ID in groups) PublicConfigs.prune(keep)
                val tested = groups.values.flatten().filter { keep == 0 || dataSource.getTestDelayMillis(it) > 0 }
                // First pass right away: pings are known, so the list is sorted without waiting
                // for the speed measurements.
                applyAutoTestOrder(groups.keys)
                if (connectBestAfterTest) {
                    connectBestAfterTest = false
                    // Connect on ping alone; the speed measurements below can take a while.
                    if (selectFastest(tested)) _connectBestTick.update { it + 1 }
                }
                dataSource.finishAutoTest(tested)
                applyAutoTestOrder(groups.keys)
                val runningGuid = uiState.value.selectedGuid
                if (uiState.value.isRunning &&
                    runningGuid != null &&
                    runningGuid in groups.values.flatten() &&
                    (dataSource.decodeAffiliationInfo(runningGuid)?.testDelayMillis ?: 0L) < 0L
                ) {
                    // Running config tested -1ms and could not be bypassed with fragment/mux: stop it.
                    onConnectFailed(canMove = false)
                    dataSource.sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
                }
                if (keep > 0 && tested.isNotEmpty()) {
                    // Public configs: the winner is the head of the list after the download
                    // measurements, not the lowest ping alone.
                    val winner = dataSource.getServerGuidList(PublicConfigs.GROUP_ID).firstOrNull { it in tested } ?: tested.first()
                    updateSelectedGuid(winner)
                    if (publicConnect) _connectBestTick.update { it + 1 }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                LogUtil.e(AppConfig.TAG, "Automatic config test failed", e)
            }
        }
    }

    private suspend fun applyAutoTestOrder(groupIds: Collection<String>) {
        groupIds.forEach { dataSource.sortGroupByBadges(it) }
        badges = computeBadges()
        cacheMutex.withLock { groupDataCache.clear() }
        reloadAllGroups(_uiState.value.groups.map { it.id })
    }

    private fun importConfigViaSub(subId: String) {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val before = allConfigGuids()
                    val result = if (subId.isEmpty()) {
                        dataSource.updateConfigViaSubAll()
                    } else {
                        val item = dataSource.getSubscriptionItem(subId) ?: return@withContext
                        dataSource.updateConfigViaSub(SubscriptionCache(subId, item))
                    }
                    when {
                        result.successCount + result.failureCount + result.skipCount == 0 ->
                            toast(R.string.title_update_subscription_no_subscription)

                        result.successCount > 0 && result.failureCount + result.skipCount == 0 ->
                            toast(
                                getQuantityString(
                                    R.plurals.title_update_config_count,
                                    result.configCount,
                                    result.configCount,
                                )
                            )

                        else ->
                            toast(dataSource.getString(R.string.title_update_subscription_result, result.configCount, result.successCount, result.failureCount, result.skipCount))
                    }
                    if (result.configCount > 0) {
                        setupGroupTab(forceRefresh = true)
                        refreshSelectedGuid()
                        startAutoTest(before)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Subscription update failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun exportAllAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val groupId = uiState.value.selectedGroupId
                    val list = if (groupId.isEmpty() && keywordFilter.isEmpty()) {
                        dataSource.getServerGuidList("")
                    } else {
                        currentServers().map { it.guid }
                    }
                    val ret = dataSource.shareNonCustomConfigsToClipboard(list)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Export failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeAllServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count =
                        if (uiState.value.selectedGroupId.isEmpty() && keywordFilter.isEmpty()) {
                            dataSource.removeAllServer()
                        } else {
                            val guids = currentServers().map { it.guid }
                            guids.forEach { dataSource.removeServer(it) }
                            guids.size
                        }
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                    }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete all failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeDuplicateServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val seen = HashSet<ProfileItem>()
                    val duplicates = ArrayList<String>()
                    currentServers().forEach { server ->
                        val profile = server.profile
                        if (!profile.configType.isComplexType()) {
                            val identity = profile.duplicateIdentity()
                            if (!seen.add(identity)) duplicates += server.guid
                        }
                    }
                    duplicates.forEach { dataSource.removeServer(it) }
                    setupGroupTab(forceRefresh = true)
                    toast(dataSource.getString(R.string.title_del_duplicate_config_count, duplicates.size))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete duplicate failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val count = removeInvalidServerInternal()
                    viewModelScope.launch(ioDispatcher) {
                        cacheMutex.withLock { groupDataCache.clear() }
                        setupGroupTab(forceRefresh = true)
                    }
                    toast(dataSource.getString(R.string.title_del_config_count, count))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Delete invalid failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun removeInvalidServerInternal(): Int {
        val visibleServersOnly =
            uiState.value.selectedGroupId.isNotEmpty() || keywordFilter.isNotBlank()
        return if (visibleServersOnly) {
            currentServers().sumOf { server ->
                dataSource.removeInvalidServerByGuid(server.guid)
            }
        } else {
            dataSource.removeInvalidServersInGroup("")
        }
    }

    private fun sortByTestResultsAsync() {
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    sortByTestResultsInternal()
                    cacheMutex.withLock { groupDataCache.clear() }
                    setupGroupTab(forceRefresh = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Sort by test results failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun sortByTestResultsInternal() {
        val subs = if (uiState.value.selectedGroupId.isEmpty()) {
            dataSource.getSubsList()
        } else {
            listOf(uiState.value.selectedGroupId)
        }
        subs.forEach { dataSource.sortByTestResultsForSub(it) }
    }

    fun subscriptionIdChanged(id: String) {
        if (_uiState.value.groups.none { it.id == id }) return
        mutableServerGroupState(id)
        if (uiState.value.selectedGroupId != id) {
            dataSource.setSelectedSubscriptionId(id)
            _uiState.update { it.copy(selectedGroupId = id) }
        }
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            try {
                updateGroupUi(id, loadGroup(id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to load selected group: $id", error)
            }
        }
    }

    fun reloadServerList() {
        val groupId = uiState.value.selectedGroupId
        selectedGroupLoadJob?.cancel()
        selectedGroupLoadJob = viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
        }
    }

    fun reloadAllGroups(groupIds: List<String>) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(preloadDispatcher) {
            val selected = uiState.value.selectedGroupId
            val order = buildList {
                if (selected in groupIds) add(selected)
                addAll(groupIds.filter { it != selected })
            }
            order.forEachIndexed { index, groupId ->
                ensureActive()
                if (index > 0) delay(32)
                updateGroupUi(groupId, loadGroup(groupId, forceRefresh = true))
            }
        }
    }

    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) return
        keywordFilter = keyword
        filterJob?.cancel()
        filterJob = viewModelScope.launch(defaultDispatcher) {
            delay(300)
            val snapshot = cacheMutex.withLock { groupDataCache.toMap() }
            ensureActive()
            snapshot.forEach { (groupId, servers) ->
                ensureActive()
                updateGroupUi(groupId, servers)
            }
        }
    }

    fun updateSelectedGuid(guid: String) {
        dataSource.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun refreshSelectedGuid() {
        _uiState.update { it.copy(selectedGuid = dataSource.getSelectServer()) }
    }

    fun removeServerAndRefresh(guid: String) {
        if (guid == uiState.value.selectedGuid) {
            toast(R.string.toast_action_not_allowed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            dataSource.removeServer(guid)
            cacheMutex.withLock { groupDataCache.clear() }
            setupGroupTab(forceRefresh = true).join()
        }
    }

    fun moveServer(groupId: String, fromPosition: Int, toPosition: Int) {
        val groupState = mutableServerGroupState(groupId).value
        val servers = groupState.servers.toMutableList()
        if (!servers.moveItem(fromPosition, toPosition)) return
        val rows = groupState.rows.toMutableList()
        rows.moveItem(fromPosition, toPosition)
        val guids = servers.map { it.guid }
        mutableServerGroupState(groupId).value = ServerGroupUiState(servers, rows)
        // A drag emits several moves; serialize writes so an older order cannot overwrite a newer one.
        val previousPersistenceJob = serverOrderPersistenceJobs[groupId]
        serverOrderPersistenceJobs[groupId] = viewModelScope.launch(ioDispatcher) {
            previousPersistenceJob?.join()
            dataSource.encodeServerList(guids, groupId)
            cacheMutex.withLock { groupDataCache[groupId] = servers }
        }
    }

    // ---------- Testing ----------
    fun cancelAllPing() {
        bulkTestJob?.cancel()
        bulkTestJob = null
        testRequests.cancelBulk()
        testRequests.invalidateCurrent()
        cancelPendingTestResults()
        resetTestStatus()
        dataSource.cancelAllPing()
    }

    private fun resetTestStatus() {
        _uiState.update {
            it.copy(
                isTesting = testRequests.isTesting,
                status = if (testRequests.isTesting) MainStatus.Testing
                else if (it.isRunning) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    /** Pings the whole current group, then deletes every config that failed the test. */
    private fun pingThenRemoveInvalid() {
        testAllRealPing()
        removeInvalidAfterRequestId = testRequests.bulk?.id
    }

    private fun copySelected(guids: List<String>) {
        if (guids.isEmpty()) return
        launchLoading {
            withContext(ioDispatcher) {
                try {
                    val ret = dataSource.shareNonCustomConfigsToClipboard(guids)
                    if (ret > 0) {
                        toast(dataSource.getString(R.string.title_export_config_count, ret))
                    } else {
                        toastError(R.string.toast_failure)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Copy selected failed", e)
                    toastError(R.string.toast_failure)
                }
            }
        }
    }

    fun testAllRealPing(onlyTcp: Boolean = false, groupId: String = uiState.value.selectedGroupId) {
        cancelAllPing()
        removeInvalidAfterRequestId = null
        val servers = mutableServerGroupState(groupId).value.servers
        if (servers.isEmpty()) {
            return
        }
        val serverGuids = servers.map { it.guid }
        mutableServerGroupState(groupId).update { current ->
            current.copy(
                servers = current.servers.map { server ->
                    if (server.testDelayMillis == 0L) server
                    else server.copy(testDelayMillis = 0L)
                },
                rows = current.rows.map { row ->
                    if (row.testDelayMillis == 0L) row
                    else row.copy(testDelayMillis = 0L)
                }
            )
        }
        val request = testRequests.beginBulk(groupId)
        val message = TestServiceMessage(
            key = AppConfig.MSG_MEASURE_CONFIG_START,
            subscriptionId = groupId,
            serverGuids = if (keywordFilter.isNotEmpty()) serverGuids else emptyList(),
            onlyTcp = onlyTcp
        )
        _uiState.update {
            it.copy(
                isTesting = true,
                status = MainStatus.Testing
            )
        }
        bulkTestJob = viewModelScope.launch {
            withContext(ioDispatcher) {
                dataSource.clearAllTestDelayResults(serverGuids)
                val resetGuids = serverGuids.toHashSet()
                cacheMutex.withLock {
                    groupDataCache[groupId]?.let { cached ->
                        groupDataCache[groupId] = cached.map { server ->
                            if (server.guid !in resetGuids || server.testDelayMillis == 0L) server
                            else server.copy(testDelayMillis = 0L)
                        }
                    }
                }
            }
            dataSource.sendMsg2TestService(message, request.id)
        }
    }

    private fun cancelPendingTestResults() {
        testResultFlushJob?.cancel()
        testResultFlushJob = null
        pendingTestResults.clear()
    }

    fun testCurrentServerRealPing() {
        if (!uiState.value.isRunning) return
        val requestId = testRequests.beginCurrent()
        _uiState.update { it.copy(isTesting = true, status = MainStatus.Testing) }
        dataSource.testCurrentServerRealPing(requestId)
    }

    private fun onTestsFinished(requestId: String) {
        if (testRequests.completeBulk(requestId) == null) return
        resetTestStatus()
        if (autoTestRequestId == requestId) {
            autoTestRequestId = null
            finishAutoTest(autoTestGroups)
            return
        }
        if (removeInvalidAfterRequestId == requestId) {
            // The removal reloads the groups itself.
            removeInvalidAfterRequestId = null
            removeInvalidServerAsync()
            return
        }
        viewModelScope.launch(ioDispatcher) {
            cacheMutex.withLock { groupDataCache.clear() }
            reloadAllGroups(_uiState.value.groups.map { it.id })
            val runningGuid = uiState.value.selectedGuid
            if (uiState.value.isRunning &&
                runningGuid != null &&
                (dataSource.decodeAffiliationInfo(runningGuid)?.testDelayMillis ?: 0L) < 0L &&
                Tuning.isUnbypassable(runningGuid)
            ) {
                onConnectFailed(canMove = false)
                dataSource.sendMsg2Service(AppConfig.MSG_STATE_STOP, "")
            }
        }
    }

    fun triggerLocateSelectedServer() {
        val selected = dataSource.getSelectServer() ?: return
        val profile = dataSource.decodeServerConfig(selected) ?: return
        val groupId = profile.subscriptionId
        if (_uiState.value.groups.none { it.id == groupId }) return
        viewModelScope.launch(ioDispatcher) {
            updateGroupUi(groupId, loadGroup(groupId))
            if (_uiState.value.selectedGroupId != groupId) {
                dataSource.setSelectedSubscriptionId(groupId)
            }
            val target = LocateTarget(groupId, selected)
            _uiState.update {
                it.copy(selectedGroupId = groupId, locateTarget = target)
            }
        }
    }

    private fun consumeLocateTarget() {
        _uiState.update { it.copy(locateTarget = null) }
    }

    // ---------- Running state ----------
    private fun updateRunningState(running: Boolean, clearTestingText: Boolean = true) {
        if (!running || clearTestingText) testRequests.invalidateCurrent()
        _uiState.update { state ->
            state.copy(
                isRunning = running,
                isTesting = testRequests.isTesting,
                status = if (!clearTestingText && state.isRunning == running) state.status
                else if (running) MainStatus.Connected else MainStatus.Disconnected
            )
        }
    }

    override fun onCleared() {
        setupGroupJob?.cancel()
        preloadJob?.cancel()
        selectedGroupLoadJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        autoTestJob?.cancel()
        cancelAllPing()
        dataSource.close()
        super.onCleared()
    }

    // ---------- Factory ----------
    class Factory(private val application: Application, private val dataSource: MainDataSource) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(application, dataSource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private companion object {
        const val TEST_RESULT_FLUSH_INTERVAL_MS = 500L
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val AETHER_POLL_MS = 700L
        const val VERIFY_SETTLE_MS = 400L
        /** How long the first retry waits; each further retry waits this much longer again. */
        const val VERIFY_RETRY_MS = 1_500L
        const val VERIFY_MAX_ATTEMPTS = 3
        const val FAILURE_FEEDBACK_MS = 3_800L
    }
}
