package com.v2ray.ang.ui.firefoxvpn

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.FirefoxVpnAuth
import com.v2ray.ang.handler.FirefoxVpnCaptcha
import com.v2ray.ang.handler.FirefoxVpnCheck
import com.v2ray.ang.handler.FirefoxVpnCloudflare
import com.v2ray.ang.handler.FirefoxVpnGuardian
import com.v2ray.ang.handler.FirefoxVpnManager
import com.v2ray.ang.handler.FirefoxVpnRelay
import com.v2ray.ang.handler.FirefoxVpnServers
import com.v2ray.ang.handler.FirefoxVpnStore
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ServiceRuntimeState
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxPromptFont
import com.v2ray.ang.ui.compose.rxScreenBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale

class FirefoxVpnActivity : BaseComponentActivity() {

    private val viewModel: FirefoxVpnViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        Box(Modifier.fillMaxSize().background(rxScreenBrush())) {
            RxFogBackground(Modifier.fillMaxSize())
            FirefoxVpnScreen(viewModel, onBack = { finish() })
        }
    }

    companion object {
        fun start(context: android.content.Context) {
            context.startActivity(android.content.Intent(context, FirefoxVpnActivity::class.java))
        }
    }
}

// ── ViewModel ─────────────────────────────────────────────────────

class FirefoxVpnViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val accounts: List<FirefoxVpnStore.StoredAccount> = emptyList(),
        val activeAccountId: String? = null,
        val countries: List<FirefoxVpnServers.Country> = emptyList(),
        val selectedCountryCode: String? = null,
        val isLoading: Boolean = false,
        val isConnecting: Boolean = false,
        val error: String? = null,
        val success: String? = null,
        val showAddAccount: Boolean = false,
        val pendingSessionToken: String? = null,
        val pendingEmail: String? = null,
        val needCodeType: String? = null,
        val connectionInfo: FirefoxVpnManager.ConnectionInfo? = null,
        val browserUrl: String? = null,
        val browserState: String? = null,
        val relayEnabled: Boolean = false,
        val relayAddress: String = "",
        val relayCode: String? = null,
        val relayVerdict: String? = null,
        /** null while unknown, true when a real page came back, false when it did not. */
        val tested: Boolean? = null,
        val tryingCountry: String? = null,
        val pings: Map<String, Int?> = emptyMap(),
        val pinging: Boolean = false,
        /** How far the attempt and the measurement have come; both are bounded. */
        val connectStep: Int = 0,
        val connectTotal: Int = 0,
        val pingDone: Int = 0,
        val pingTotal: Int = 0,
        /** The phone's VPN permission is being asked for; the core cannot start without it. */
        val askVpnPermission: Boolean = false,
        /** What the tunnel has carried since it came up, read from the relay itself. */
        val usage: FirefoxVpnRelay.Usage? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** Kept in memory for the retry after a code or a picture check; never written down. */
    private var pendingPassword: String? = null

    init {
        loadState()
        adoptRunningTunnel()
        watchUsage()
    }

    /**
     * A tunnel that is up is the phone's, not this screen's. Coming back to the
     * screen used to draw an idle page with a live tunnel behind it, and the
     * user's next attempt to connect tore that working tunnel down first — which
     * is why leaving and returning looked like being cut off.
     *
     * The check is the service's own answer, and the port is the one that was
     * written down when the tunnel came up, so nothing is assumed from a screen
     * that may have been away for an hour.
     */
    private fun adoptRunningTunnel() {
        val context = getApplication<Application>()
        if (MmkvManager.getSelectServer() != TUNNEL_GUID) return
        if (!ServiceRuntimeState.isRunning(context)) return
        val port = FirefoxVpnStore.loadTunnelPort(context)
        if (port <= 0) return
        val country = FirefoxVpnStore.loadTunnelCountry(context) ?: return
        val name = FirefoxVpnStore.loadTunnelName(context).orEmpty().ifEmpty { country }
        val info = FirefoxVpnManager.ConnectionInfo(
            proxyHost = "",
            proxyPort = 0,
            proxyPassToken = "",
            countryName = name,
            quotaRemaining = 0L,
            quotaLimit = 0L,
            countryCode = country,
            localPort = port,
        )
        _state.update {
            it.copy(
                connectionInfo = info,
                selectedCountryCode = it.selectedCountryCode ?: country,
                tested = null,
            )
        }
        startMonitor(port)
        AppLog.info(AppLog.Section.Connection, "Firefox VPN: an existing tunnel to $name is still up; keeping it")
    }

    /**
     * Reads the relay's own byte counter periodically while the app process owns
     * the listener. Updates are display telemetry, not a health check.
     */
    private fun watchUsage() {
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            while (isActive) {
                val up = _state.value.connectionInfo != null && FirefoxVpnRelay.isRunning()
                val usage = if (up) FirefoxVpnRelay.usage() else null
                _state.update { if (it.usage == usage) it else it.copy(usage = usage) }
                // This is display-only telemetry. Polling once a second forced a
                // Compose update even on idle phones; slower, change-only updates
                // keep the screen lighter on low-memory devices.
                delay(if (up) 3_000 else 8_000)
            }
        }
    }

    private var usageJob: Job? = null

    private fun loadState() {
        val accounts = FirefoxVpnStore.loadAccounts(getApplication())
        val activeId = FirefoxVpnStore.loadActiveAccountId(getApplication())
        val countryCode = FirefoxVpnStore.loadSelectedCountry(getApplication())
        _state.update {
            it.copy(
                accounts = accounts,
                activeAccountId = activeId ?: accounts.firstOrNull()?.id,
                selectedCountryCode = countryCode,
                relayEnabled = FirefoxVpnCloudflare.isEnabled(getApplication()),
                relayAddress = FirefoxVpnCloudflare.address(getApplication())
            )
        }
    }

    fun loadCountries() {
        if (_state.value.countries.isNotEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val countries = FirefoxVpnServers.fetchServerList()
            _state.update {
                it.copy(
                    countries = countries,
                    isLoading = false,
                    selectedCountryCode = it.selectedCountryCode
                        ?: countries.firstOrNull { country -> country.code == FirefoxVpnServers.RECOMMENDED_CODE }?.code
                        ?: countries.firstOrNull()?.code
                )
            }
        }
    }

    fun selectCountry(code: String) {
        FirefoxVpnStore.saveSelectedCountry(getApplication(), code)
        pickedByHand = true
        _state.update { it.copy(selectedCountryCode = code) }
    }

    /** Set when the location was chosen by hand rather than left on the default. */
    @Volatile
    private var pickedByHand: Boolean = false

    fun selectAccount(id: String) {
        FirefoxVpnStore.saveActiveAccountId(getApplication(), id)
        _state.update { it.copy(activeAccountId = id) }
    }

    fun showAddAccount() {
        _state.update { it.copy(showAddAccount = true, error = null, success = null) }
    }

    fun hideAddAccount() {
        pendingPassword = null
        _state.update {
            it.copy(
                showAddAccount = false,
                pendingSessionToken = null,
                pendingEmail = null,
                needCodeType = null
            )
        }
    }

    fun addAccount(email: String, password: String) {
        pendingPassword = password
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = FirefoxVpnAuth.login(email, password)) {
                is FirefoxVpnAuth.LoginResult.Success -> storeAccount(result)
                is FirefoxVpnAuth.LoginResult.NeedEmailCode -> {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            pendingSessionToken = result.sessionToken,
                            pendingEmail = result.email,
                            needCodeType = "email"
                        )
                    }
                }
                is FirefoxVpnAuth.LoginResult.NeedTotp -> {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            pendingSessionToken = result.sessionToken,
                            pendingEmail = result.email,
                            needCodeType = "totp"
                        )
                    }
                }
                is FirefoxVpnAuth.LoginResult.Error -> {
                    _state.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    fun submitCode(code: String) {
        val sessionToken = _state.value.pendingSessionToken ?: return
        val codeType = _state.value.needCodeType ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val verified = when (codeType) {
                "email" -> FirefoxVpnAuth.verifyEmailCode(sessionToken, code)
                "totp" -> FirefoxVpnAuth.verifyTotp(sessionToken, code)
                else -> false
            }
            if (!verified) {
                _state.update { it.copy(isLoading = false, error = "The code was not accepted") }
                return@launch
            }
            when (val tokenResult = FirefoxVpnAuth.exchangeForOAuth(sessionToken)) {
                is FirefoxVpnAuth.TokenResult.Success -> {
                    val email = _state.value.pendingEmail.orEmpty()
                    saveTokens(email, tokenResult.tokens, FirefoxVpnAuth.FIREFOX_CLIENT_ID)
                }
                is FirefoxVpnAuth.TokenResult.Error ->
                    _state.update { it.copy(isLoading = false, error = tokenResult.message) }
            }
        }
    }

    /** Asks Mozilla to mail the confirmation again, with the address already typed. */
    fun resendCode() {
        val email = _state.value.pendingEmail
        val password = pendingPassword
        if (email.isNullOrEmpty() || password.isNullOrEmpty()) {
            _state.update { it.copy(error = "Type the password again") }
            return
        }
        addAccount(email, password)
    }

    /** Looks again at a session the user may have confirmed by opening the mail's link. */
    fun checkSession() {
        val sessionToken = _state.value.pendingSessionToken ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            if (!FirefoxVpnAuth.sessionVerified(sessionToken)) {
                _state.update { it.copy(isLoading = false, error = "The sign-in is not confirmed yet") }
                return@launch
            }
            when (val tokenResult = FirefoxVpnAuth.exchangeForOAuth(sessionToken)) {
                is FirefoxVpnAuth.TokenResult.Success -> {
                    val email = _state.value.pendingEmail.orEmpty()
                    saveTokens(email, tokenResult.tokens, FirefoxVpnAuth.FIREFOX_CLIENT_ID)
                }
                is FirefoxVpnAuth.TokenResult.Error ->
                    _state.update { it.copy(isLoading = false, error = tokenResult.message) }
            }
        }
    }

    private fun storeAccount(result: FirefoxVpnAuth.LoginResult.Success) {
        viewModelScope.launch {
            when (val tokenResult = FirefoxVpnAuth.exchangeForOAuth(result.sessionToken)) {
                is FirefoxVpnAuth.TokenResult.Success ->
                    saveTokens(result.email, tokenResult.tokens, FirefoxVpnAuth.FIREFOX_CLIENT_ID)
                is FirefoxVpnAuth.TokenResult.Error ->
                    _state.update { it.copy(isLoading = false, error = tokenResult.message) }
            }
        }
    }

    /**
     * The address the user picked in the browser window is already known, so a
     * profile lookup that the network refuses no longer leaves a nameless row.
     */
    private suspend fun addressOfTokens(
        tokens: FirefoxVpnAuth.OAuthTokens,
        known: String
    ): String = FirefoxVpnAuth.profileEmail(tokens.accessToken)
        ?: known.ifEmpty { "Firefox Account" }

    private fun saveTokens(
        email: String,
        tokens: FirefoxVpnAuth.OAuthTokens,
        clientId: String = FirefoxVpnAuth.BROWSER_CLIENT_ID
    ) {
        val account = FirefoxVpnStore.StoredAccount(
            id = FirefoxVpnManager.newAccountId(),
            email = email,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000,
            createdAt = System.currentTimeMillis(),
            clientId = clientId
        )
        FirefoxVpnStore.saveAccount(getApplication(), account)
        FirefoxVpnStore.saveActiveAccountId(getApplication(), account.id)
        pendingPassword = null
        loadState()
        _state.update {
            it.copy(
                isLoading = false,
                showAddAccount = false,
                pendingSessionToken = null,
                pendingEmail = null,
                needCodeType = null,
                success = "Account added: $email"
            )
        }
        viewModelScope.launch { FirefoxVpnAuth.registerDevice(tokens.refreshToken) }
    }

    /** Kept only while the browser window is open; it redeems the code. */
    private var browserVerifier: String? = null

    fun startBrowserSignIn() {
        val signIn = FirefoxVpnAuth.startBrowserSignIn()
        browserVerifier = signIn.verifier
        _state.update {
            it.copy(error = null, success = null, browserUrl = signIn.url, browserState = signIn.state)
        }
    }

    fun clearBrowserRequest() {
        _state.update { it.copy(browserUrl = null, browserState = null) }
    }

    fun completeBrowserSignIn(code: String, address: String = "") {
        val verifier = browserVerifier
        _state.update { it.copy(browserUrl = null, browserState = null) }
        if (verifier == null || code.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = FirefoxVpnAuth.finishBrowserSignIn(code, verifier)) {
                is FirefoxVpnAuth.TokenResult.Success -> {
                    browserVerifier = null
                    saveTokens(addressOfTokens(result.tokens, address), result.tokens)
                }
                is FirefoxVpnAuth.TokenResult.Error ->
                    _state.update { it.copy(isLoading = false, error = result.message) }
            }
        }
    }

    fun removeAccount(id: String) {
        FirefoxVpnStore.removeAccount(getApplication(), id)
        loadState()
    }

    private companion object {
        /** The profile the core is pointed at; one is enough. */
        const val TUNNEL_GUID = "firefox-vpn"
    }

    private var connectJob: Job? = null
    private var monitor: Job? = null

    /**
     * The phone's VPN permission, asked for before the first tunnel.
     *
     * Nothing the app does counts as connected without it: the service starts,
     * finds it has no permission and stops again, and the screen would go on
     * saying connected while no page opens. So the question is asked here and
     * the answer is waited for.
     */
    private var permissionAnswer: CompletableDeferred<Boolean>? = null

    private suspend fun ensureVpnPermission(): Boolean {
        if (!SettingsManager.isVpnMode()) return true
        val answer = CompletableDeferred<Boolean>()
        permissionAnswer = answer
        _state.update { it.copy(askVpnPermission = true) }
        return try {
            withTimeout(120_000) { answer.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            permissionAnswer = null
            _state.update { it.copy(askVpnPermission = false) }
        }
    }

    /** Called by the screen with what the phone's permission dialog answered. */
    fun onVpnPermissionResult(granted: Boolean) {
        permissionAnswer?.complete(granted)
    }

    /** Set when the core was asked to start and did not, and what that means. */
    @Volatile
    private var coreRefused = false

    /**
     * Waits for the core to really be up. The relay answers on loopback
     * whether the core runs or not, so the only honest witness is the flag the
     * daemon itself writes.
     */
    private suspend fun waitForCore(timeoutMillis: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (ServiceRuntimeState.isRunning(getApplication())) return true
            delay(400)
        }
        return false
    }

    /**
     * Opens a tunnel and refuses to call it connected until a real page has
     * come back through it. Countries this phone cannot reach are skipped, and
     * the ones it can reach are tried from the fastest.
     */
    fun connect() {
        val account = activeAccount()
        if (account == null) {
            _state.update { it.copy(error = contextString(R.string.firefox_vpn_need_account)) }
            return
        }
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            // Asking to connect while a tunnel is already up is not a reason to tear
            // it down: the running one is re-used when it is the location asked for,
            // and only replaced when it is not.
            val current = _state.value.connectionInfo
            val wanted = _state.value.selectedCountryCode
            if (current != null && ServiceRuntimeState.isRunning(getApplication()) && current.localPort > 0 &&
                (wanted == null || FirefoxVpnStore.loadTunnelCountry(getApplication()) == wanted)
            ) {
                AppLog.info(AppLog.Section.Connection, "Firefox VPN: the tunnel that is up is the one asked for; keeping it")
                _state.update { it.copy(tested = null, error = null) }
                startMonitor(current.localPort)
                return@launch
            }
            _state.update {
                it.copy(
                    isConnecting = true,
                    error = null,
                    success = null,
                    tested = null,
                    connectStep = 0,
                    connectTotal = 0
                )
            }
            stopTunnel()
            FirefoxVpnStore.clearTunnel(getApplication())

            val pass = passFor(account)
            if (pass == null) {
                _state.update {
                    it.copy(
                        isConnecting = false,
                        error = it.error ?: contextString(R.string.firefox_vpn_err_no_pass)
                    )
                }
                return@launch
            }

            if (!ensureVpnPermission()) {
                AppLog.error(
                    AppLog.Section.Connection,
                    "Firefox VPN: the phone's VPN permission was not given"
                )
                _state.update {
                    it.copy(
                        isConnecting = false,
                        tryingCountry = null,
                        error = contextString(R.string.firefox_vpn_err_no_permission)
                    )
                }
                return@launch
            }

            val codes = candidates(pass)
            _state.update { it.copy(connectTotal = codes.size) }
            var connected: FirefoxVpnManager.ConnectionInfo? = null
            for ((index, code) in codes.withIndex()) {
                if (!isActive) return@launch
                val name = _state.value.countries.firstOrNull { it.code == code }?.name ?: code
                _state.update { it.copy(tryingCountry = code, connectStep = index) }
                AppLog.info(AppLog.Section.Connection, "Firefox VPN: trying $name")
                val info = runTunnel(account, pass, code)
                if (info != null) {
                    connected = info
                    break
                }
            }
            _state.update {
                it.copy(
                    isConnecting = false,
                    tryingCountry = null,
                    connectStep = it.connectTotal,
                    connectionInfo = connected,
                    tested = if (connected != null) true else null,
                    error = if (connected == null) explain() else null
                )
            }
            if (connected != null) {
                startMonitor(connected.localPort)
            }
        }
    }

    /** The account the tunnels are opened with. */
    private fun activeAccount(): FirefoxVpnStore.StoredAccount? =
        _state.value.accounts.firstOrNull { it.id == _state.value.activeAccountId }

    /** Reads a string with the app's own resources. */
    private fun contextString(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    /**
     * Asks Mozilla for the pass this account tunnels with. One pass serves
     * every location, so it is fetched once per attempt.
     */
    private suspend fun passFor(account: FirefoxVpnStore.StoredAccount): FirefoxVpnGuardian.ProxyPass? {
        val pass = FirefoxVpnManager.fetchPass(getApplication(), account)
        if (pass == null) {
            AppLog.error(AppLog.Section.Connection, "Firefox VPN: Mozilla issued no pass for this account")
        }
        return pass
    }

    /**
     * The locations worth trying, quickest first.
     *
     * The order is measured, not guessed: whatever the last test left is
     * reused, and the rest is timed once. A location that has been chosen by
     * hand is tried first, because it was asked for; without a choice the
     * quickest one is used, which is what keeps the delay low.
     */
    private suspend fun candidates(pass: FirefoxVpnGuardian.ProxyPass): List<String> {
        val countries = _state.value.countries
        if (countries.isEmpty()) {
            return listOf(_state.value.selectedCountryCode?.takeIf { it.isNotBlank() }
                ?: FirefoxVpnServers.RECOMMENDED_CODE)
        }

        val stored = _state.value.pings
        val known = countries.count { stored.containsKey(it.code) }
        val chosen = _state.value.selectedCountryCode

        // Nothing to go on, or only a little: time enough of them to have an
        // order. A test that was already run is not repeated on every connect.
        if (known < 3) {
            val sample = (
                listOfNotNull(chosen) +
                    listOf(FirefoxVpnServers.RECOMMENDED_CODE) +
                    countries.map { it.code }
                ).distinct().take(8)
            val gate = Semaphore(4)
            coroutineScope {
                sample.map { code ->
                    val country = countries.firstOrNull { it.code == code }
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val value = country?.let { FirefoxVpnServers.ping(it, pass.token) }
                            if (country != null) {
                                _state.update { state ->
                                    state.copy(pings = state.pings + (country.code to value))
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val measured = _state.value.pings
        val ordered = countries.sortedWith(
            compareBy<FirefoxVpnServers.Country> { measured[it.code] == null }
                .thenBy { measured[it.code] ?: Int.MAX_VALUE }
                .thenBy { it.name }
        ).map { it.code }
        // A choice made by hand is honoured: only try the chosen country, so the
        // user never ends up in a different country than they selected.
        val first = if (pickedByHand && chosen != null) {
            listOf(chosen)
        } else {
            ordered
        }
        return first.take(4)
    }

    /** Builds one tunnel and asks it for a real page; null when it stays silent. */
    private suspend fun runTunnel(
        account: FirefoxVpnStore.StoredAccount,
        pass: FirefoxVpnGuardian.ProxyPass,
        countryCode: String
    ): FirefoxVpnManager.ConnectionInfo? {
        val result = withContext(Dispatchers.IO) {
            FirefoxVpnManager.connectWithPass(getApplication(), account, pass, countryCode)
        }
        if (result is FirefoxVpnManager.ConnectResult.Error) {
            AppLog.error(AppLog.Section.Connection, "Firefox VPN: ${result.message}")
            return null
        }
        val info = (result as FirefoxVpnManager.ConnectResult.Success).info

        // When no address took a tunnel there is nothing to wait for: waiting
        // a quarter of a minute per location was the whole of the old wait.
        if (FirefoxVpnRelay.problem != null &&
            FirefoxVpnCloudflare.relayAddressOrNull(getApplication()) == null
        ) {
            AppLog.error(
                AppLog.Section.Connection,
                "Firefox VPN: ${info.countryName} has no address that takes a tunnel; moving on"
            )
            stopTunnel()
            return null
        }

        val profile = com.v2ray.ang.dto.entities.ProfileItem(
            configType = com.v2ray.ang.enums.EConfigType.HTTP,
            remarks = "Mozilla (${info.countryName})",
            server = "127.0.0.1",
            serverPort = info.localPort.toString()
        )
        com.v2ray.ang.handler.MmkvManager.encodeServerConfig(TUNNEL_GUID, profile)
        com.v2ray.ang.handler.MmkvManager.setSelectServer(TUNNEL_GUID)
        com.v2ray.ang.core.LauncherManager.startService(getApplication(), guid = TUNNEL_GUID)

        // The relay would answer on loopback even with the core down, so the
        // core itself is waited for: without it the phone's own traffic never
        // reaches the tunnel and nothing opens in any app.
        if (!waitForCore(20_000)) {
            AppLog.error(
                AppLog.Section.Connection,
                "Firefox VPN: the core never started, so no traffic reaches the tunnel"
            )
            coreRefused = true
            stopTunnel()
            return null
        }

        if (!FirefoxVpnCheck.waitUntilCarries(info.localPort, 25_000)) {
            AppLog.error(
                AppLog.Section.Connection,
                "Firefox VPN: ${info.countryName} was announced but carried nothing; moving on"
            )
            stopTunnel()
            return null
        }
        coreRefused = false
        AppLog.info(
            AppLog.Section.Connection,
            "Firefox VPN: verified through ${info.countryName} (relay ${info.localPort})"
        )
        val actualCountryCode = info.countryCode.ifBlank { countryCode }
        FirefoxVpnStore.saveSelectedCountry(getApplication(), actualCountryCode)
        FirefoxVpnStore.saveTunnel(getApplication(), info.localPort, actualCountryCode, info.countryName)
        return info
    }

    /**
     * The daemon owns recovery so it keeps working after this screen is closed.
     * This screen only reports the result and adopts a loopback port if the
     * background guardian replaces a dead relay. It must not stop the core or
     * start a second reconnect loop of its own.
     */
    private fun startMonitor(localPort: Int) {
        monitor?.cancel()
        monitor = viewModelScope.launch {
            val context = getApplication<Application>()
            var activePort = localPort
            var lastFailureLogAt = 0L
            while (isActive) {
                delay(30_000)

                val currentPort = FirefoxVpnStore.loadTunnelPort(context)
                val selectedGuid = MmkvManager.getSelectServer()
                val serviceUp = ServiceRuntimeState.isRunning(context)
                if (currentPort <= 0 || (!serviceUp && selectedGuid != TUNNEL_GUID)) {
                    // A stop from the main screen clears the port; a stop after
                    // selecting another profile is identified by the stopped
                    // service. Those are intentional disconnects, not failures
                    // for the daemon to recover. Release this process's relay.
                    FirefoxVpnRelay.stop()
                    _state.update { it.copy(connectionInfo = null, tested = false, usage = null) }
                    return@launch
                }
                if (currentPort != activePort) {
                    // The daemon replaced the relay while this screen was open.
                    // Stop this process's obsolete listener and follow the new port.
                    FirefoxVpnRelay.stop()
                    activePort = currentPort
                    val country = FirefoxVpnStore.loadTunnelCountry(context).orEmpty()
                    val name = FirefoxVpnStore.loadTunnelName(context).orEmpty().ifEmpty { country }
                    _state.update { state ->
                        state.copy(
                            connectionInfo = state.connectionInfo?.copy(
                                localPort = activePort,
                                countryCode = country,
                                countryName = name,
                            ),
                            selectedCountryCode = state.selectedCountryCode ?: country,
                            tested = null,
                        )
                    }
                    AppLog.info(AppLog.Section.Connection, "Firefox VPN: adopted the daemon's replacement relay on port $activePort")
                }

                val ok = activePort > 0 && serviceUp && withContext(Dispatchers.IO) {
                    FirefoxVpnCheck.through(activePort)
                }
                _state.update { it.copy(tested = ok) }
                if (!ok && System.currentTimeMillis() - lastFailureLogAt >= 60_000L) {
                    lastFailureLogAt = System.currentTimeMillis()
                    AppLog.info(
                        AppLog.Section.Connection,
                        "Firefox VPN: screen check is waiting for the daemon's background recovery"
                    )
                }
            }
        }
    }

    private fun stopTunnel() {
        runCatching { com.v2ray.ang.core.LauncherManager.stopService(getApplication()) }
        FirefoxVpnRelay.stop()
    }

    /**
     * Times every location: how long a real page takes through it on this
     * phone's own connection. The numbers are measured, one at a time in the
     * order they come, and a location that carries nothing is left blank.
     */
    fun measureAll() {
        val account = activeAccount() ?: return
        if (_state.value.countries.isEmpty() || _state.value.pinging) return
        viewModelScope.launch {
            _state.update {
                it.copy(pinging = true, pingDone = 0, pingTotal = it.countries.size, error = null)
            }
            val pass = passFor(account)
            if (pass == null) {
                _state.update {
                    it.copy(pinging = false, error = contextString(R.string.firefox_vpn_err_no_pass))
                }
                return@launch
            }
            val countries = _state.value.countries
            val gate = Semaphore(16)
            coroutineScope {
                countries.map { country ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val value = FirefoxVpnServers.ping(country, pass.token)
                            _state.update { state ->
                                state.copy(
                                    pings = state.pings + (country.code to value),
                                    pingDone = state.pingDone + 1
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            val reachable = _state.value.pings.count { it.value != null }
            AppLog.info(
                AppLog.Section.Connection,
                "Firefox VPN: $reachable of ${countries.size} locations carried a real page"
            )
            _state.update { it.copy(pinging = false) }
        }
    }

    fun disconnect() {
        monitor?.cancel()
        connectJob?.cancel()
        stopTunnel()
        FirefoxVpnStore.clearTunnel(getApplication())
        _state.update { it.copy(connectionInfo = null, tested = null) }
        AppLog.info(AppLog.Section.Connection, "Firefox VPN disconnected")
    }

    // ── The way out through Cloudflare ───────────────────────────

    fun setRelayEnabled(enabled: Boolean) {
        FirefoxVpnCloudflare.setEnabled(getApplication(), enabled)
        _state.update { it.copy(relayEnabled = enabled) }
        AppLog.info(AppLog.Section.Connection, "Cloudflare relay " + if (enabled) "on" else "off")
    }

    fun setRelayAddress(address: String) {
        _state.update { it.copy(relayAddress = address) }
    }

    fun saveRelayAddress() {
        FirefoxVpnCloudflare.setAddress(getApplication(), _state.value.relayAddress)
        _state.update {
            it.copy(
                relayAddress = FirefoxVpnCloudflare.address(getApplication()),
                relayVerdict = null
            )
        }
    }

    /**
     * Asks the deployed Worker whether it knows this phone's key, and — when
     * Mozilla has issued a pass — whether a real tunnel goes through it. Only
     * that second question is worth answering with "ready".
     */
    fun checkRelay() {
        val address = _state.value.relayAddress
        if (address.isBlank()) {
            _state.update { it.copy(relayVerdict = "missing") }
            return
        }
        val account = activeAccount()
        viewModelScope.launch {
            val verdict = withContext(Dispatchers.IO) {
                val pass = account?.let { FirefoxVpnManager.fetchPass(getApplication(), it) }
                val country = _state.value.countries.firstOrNull { it.code == _state.value.selectedCountryCode }
                    ?: FirefoxVpnServers.pickRecommended(_state.value.countries)
                val endpoint = FirefoxVpnServers.connectEndpoint(country)
                if (pass != null && endpoint != null) {
                    FirefoxVpnCloudflare.check(
                        address,
                        getApplication(),
                        endpoint.connectHost,
                        endpoint.tunnelPort,
                        pass.token
                    )
                } else {
                    FirefoxVpnCloudflare.check(address, getApplication())
                }
            }
            AppLog.info(AppLog.Section.Connection, "Cloudflare relay check: $verdict")
            _state.update { it.copy(relayVerdict = verdict) }
        }
    }

    /**
     * One honest sentence about why nothing connected. The relay knows the
     * last thing the proxy said; the number is all the screen needs, so the
     * sentence can be read without knowing what a proxy is.
     */
    private fun explain(): String {
        val problem = FirefoxVpnRelay.problem.orEmpty()
        val code = Regex("HTTP/1\\.[01] (\\d{3})").find(problem)?.groupValues?.getOrNull(1)
        val rejection = when (code) {
            "407" -> contextString(R.string.firefox_vpn_err_407)
            "421" -> contextString(R.string.firefox_vpn_err_421)
            "403" -> contextString(R.string.firefox_vpn_err_403)
            "429" -> contextString(R.string.firefox_vpn_err_429)
            null -> ""
            else -> code
        }
        return when {
            coreRefused -> contextString(R.string.firefox_vpn_err_no_permission)
            rejection.isNotEmpty() -> contextString(R.string.firefox_vpn_err_refused, rejection)
            problem.contains("cannot be reached") ||
                problem.contains("no answer") ||
                problem.contains("closed") ->
                contextString(R.string.firefox_vpn_err_unreachable)
            else -> contextString(R.string.firefox_vpn_err_no_traffic)
        }
    }

    /** The whole Worker text, with this phone's own key already in it. */
    fun showRelayCode() {
        _state.update { it.copy(relayCode = FirefoxVpnCloudflare.workerCode(getApplication())) }
    }

    fun hideRelayCode() {
        _state.update { it.copy(relayCode = null) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, success = null) }
    }
}

// ── Screen ────────────────────────────────────────────────────────

@Composable
private fun FirefoxVpnScreen(viewModel: FirefoxVpnViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val captchaImage by FirefoxVpnCaptcha.pendingImage.collectAsStateWithLifecycle()
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(state.askVpnPermission) {
        if (state.askVpnPermission) {
            val context = viewModel.getApplication<Application>()
            val intent = android.net.VpnService.prepare(context)
            if (intent == null) {
                viewModel.onVpnPermissionResult(true)
            } else {
                vpnPermissionLauncher.launch(intent)
            }
        }
    }

    val browserLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.data?.getStringExtra(FirefoxVpnWebSignInActivity.EXTRA_CODE).orEmpty()
        val address = result.data?.getStringExtra(FirefoxVpnWebSignInActivity.EXTRA_EMAIL).orEmpty()
        if (result.resultCode == android.app.Activity.RESULT_OK && code.isNotEmpty()) {
            viewModel.completeBrowserSignIn(code, address)
        } else {
            viewModel.clearBrowserRequest()
        }
    }

    LaunchedEffect(Unit) { viewModel.loadCountries() }

    LaunchedEffect(state.browserUrl) {
        val url = state.browserUrl
        if (url != null) {
            browserLauncher.launch(
                FirefoxVpnWebSignInActivity.intent(
                    context = viewModel.getApplication(),
                    url = url,
                    state = state.browserState.orEmpty()
                )
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {

            // Header
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back_24dp),
                        contentDescription = stringResource(R.string.log_back),
                        tint = colors.onSurface
                    )
                }
                Image(
                    painter = painterResource(R.drawable.rx_fox_badge),
                    contentDescription = null,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.firefox_vpn_title),
                    style = androidx.compose.ui.text.TextStyle(fontFamily = RxPromptFont, fontSize = 22.sp),
                    color = colors.onSurface
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.firefox_vpn_description),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = RxPromptFont,
                            lineHeight = 20.sp
                        ),
                        color = colors.onSurfaceVariant
                    )
                }

                state.connectionInfo?.let { info ->
                    item { TunnelState(tested = state.tested, country = info.countryName) }
                }
                // What this tunnel has carried, read from the relay itself. A plan with
                // a limit is spent without warning otherwise.
                state.usage?.let { usage ->
                    item { UsageCard(usage) }
                }

                if (state.isConnecting) {
                    item {
                        val name = state.countries.firstOrNull { it.code == state.tryingCountry }?.name
                            ?: stringResource(R.string.firefox_vpn_recommended)
                        val total = state.connectTotal.coerceAtLeast(1)
                        ProgressCard(
                            text = stringResource(
                                R.string.firefox_vpn_trying,
                                name,
                                (state.connectStep + 1).coerceAtMost(total),
                                total
                            ),
                            progress = (state.connectStep.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        )
                    }
                }

                state.error?.let { message ->
                    item { Notice(message, isError = true) }
                }
                state.success?.let { message ->
                    item { Notice(message, isError = false) }
                }

                // Account
                item { SectionTitle(stringResource(R.string.firefox_vpn_section_account)) }
                if (state.accounts.isEmpty()) {
                    item {
                        Card {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.firefox_vpn_step_one),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
                                    color = colors.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.firefox_vpn_step_two),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
                                    color = colors.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.firefox_vpn_step_three),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.startBrowserSignIn() },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                            ) {
                                Text(
                                    stringResource(R.string.firefox_vpn_web_sign_in),
                                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont)
                                )
                            }
                            TextButton(
                                onClick = { viewModel.showAddAccount() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(R.string.firefox_vpn_manual_sign_in),
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = RxPromptFont),
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(state.accounts) { account ->
                        AccountRow(
                            email = account.email,
                            isActive = account.id == state.activeAccountId,
                            onSelect = { viewModel.selectAccount(account.id) },
                            onRemove = { viewModel.removeAccount(account.id) }
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { viewModel.startBrowserSignIn() }) {
                                Text(
                                    stringResource(R.string.firefox_vpn_add_account),
                                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont),
                                    color = colors.primary
                                )
                            }
                        }
                    }
                }

                // Location
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.firefox_vpn_section_country))
                        Spacer(Modifier.width(6.dp))
                        if (state.countries.isNotEmpty()) {
                            Text(
                                text = state.countries.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (state.countries.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.measureAll() },
                                enabled = !state.pinging
                            ) {
                                Text(
                                    text = if (state.pinging) {
                                        stringResource(
                                            R.string.firefox_vpn_measuring_progress,
                                            state.pingDone,
                                            state.pingTotal
                                        )
                                    } else {
                                        stringResource(R.string.firefox_vpn_measure_all)
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = RxPromptFont),
                                    color = colors.primary
                                )
                            }
                        }
                    }
                }
                if (state.accounts.isEmpty()) {
                    item {
                        Card {
                            Text(
                                text = stringResource(R.string.firefox_vpn_need_account),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                } else if (state.countries.isEmpty()) {
                    item {
                        Card {
                            Text(
                                text = if (state.isLoading) stringResource(R.string.firefox_vpn_connecting)
                                else stringResource(R.string.public_ip_unknown),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            text = stringResource(R.string.firefox_vpn_ping_note),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = RxPromptFont),
                            color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )
                    }
                    // The recommended location first, then the rest by name.
                    val ordered = state.countries.sortedWith(
                        compareBy({ it.code != FirefoxVpnServers.RECOMMENDED_CODE }, { it.name })
                    )
                    items(ordered) { country ->
                        CountryRow(
                            country = country,
                            selected = country.code == state.selectedCountryCode,
                            ping = state.pings[country.code],
                            measuring = state.pinging,
                            onSelect = { viewModel.selectCountry(country.code) }
                        )
                    }
                }

                // The way out through Cloudflare is left out of the screen for
                // now; the code behind it stays, ready to be shown again.

            }

            // Bottom action
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                val connected = state.connectionInfo != null
                Button(
                    onClick = {
                        viewModel.clearMessages()
                        if (connected) viewModel.disconnect() else viewModel.connect()
                    },
                    enabled = !state.isConnecting && (connected || state.accounts.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connected) colors.error else colors.primary,
                        disabledContainerColor = colors.surfaceVariant
                    )
                ) {
                    Text(
                        text = when {
                            state.isConnecting -> stringResource(R.string.firefox_vpn_connecting)
                            connected -> stringResource(R.string.firefox_vpn_disconnect)
                            else -> stringResource(R.string.firefox_vpn_connect)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = RxPromptFont,
                            fontSize = 18.sp
                        )
                    )
                }
            }
        }
    }

    if (state.showAddAccount) {
        AddAccountSheet(
            isLoading = state.isLoading,
            needCodeType = state.needCodeType,
            pendingEmail = state.pendingEmail,
            onDismiss = { viewModel.hideAddAccount() },
            onSubmit = { email, password -> viewModel.addAccount(email, password) },
            onSubmitCode = { code -> viewModel.submitCode(code) },
            onResend = { viewModel.resendCode() },
            onCheckAgain = { viewModel.checkSession() }
        )
    }

    val image = captchaImage
    if (image != null) {
        CaptchaDialog(
            imageDataUrl = image,
            onDismiss = { FirefoxVpnCaptcha.cancel() },
            onSubmit = { text -> FirefoxVpnCaptcha.submit(text) }
        )
    }
}

// ── Pieces ────────────────────────────────────────────────────────

/**
 * Live reading of what the tunnel has carried. Below it, the plan's own remaining
 * quota when Mozilla's pass reported one — the app cannot ask for it again while a
 * tunnel is up, so the number is what was known when it came up plus what the relay
 * has moved since.
 */
@Composable
private fun UsageCard(usage: FirefoxVpnRelay.Usage) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface.copy(alpha = 0.5f))
            .border(1.dp, colors.onSurface.copy(alpha = 0.12f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.firefox_vpn_usage_used),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.firefox_vpn_usage_values,
                rxBytes(usage.down),
                rxBytes(usage.up),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        val minutes = ((System.currentTimeMillis() - usage.sinceMillis) / 60_000L).coerceAtLeast(0L)
        Text(
            text = stringResource(R.string.firefox_vpn_usage_open, minutes.toString(), usage.open.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/** A byte count a person reads: 1.4 MB, 812 kB, 12 B. */
private fun rxBytes(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> String.format(java.util.Locale.US, "%.0f kB", bytes / 1_000.0)
    bytes < 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont, fontSize = 16.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) { content() }
}

@Composable
private fun ProgressCard(text: String, progress: Float) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.55f))
            .border(1.dp, colors.outline.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
            color = colors.onSurface
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = colors.primary,
            trackColor = colors.outline.copy(alpha = 0.25f)
        )
    }
}

@Composable
private fun Notice(message: String, isError: Boolean) {
    val colors = MaterialTheme.colorScheme
    val background = if (isError) colors.error.copy(alpha = 0.16f) else colors.primary.copy(alpha = 0.16f)
    val ink = if (isError) colors.error else colors.primary
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
            color = ink
        )
    }
}

@Composable
private fun AccountRow(
    email: String,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface.copy(alpha = if (isActive) 0.75f else 0.45f))
            .border(
                1.dp,
                if (isActive) colors.primary.copy(alpha = 0.6f) else colors.outline.copy(alpha = 0.2f),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = email.take(1).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = RxPromptFont),
                color = colors.primary
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = email,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            maxLines = 1
        )
        if (isActive) {
            Text(
                text = stringResource(R.string.firefox_vpn_active),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = RxPromptFont),
                color = colors.primary,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                painterResource(R.drawable.ic_delete_24dp),
                contentDescription = stringResource(R.string.firefox_vpn_remove_account),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CountryRow(
    country: FirefoxVpnServers.Country,
    selected: Boolean,
    ping: Int?,
    measuring: Boolean,
    onSelect: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = if (selected) 0.7f else 0.35f))
            .border(
                1.dp,
                if (selected) colors.primary.copy(alpha = 0.55f) else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = flagOf(country.code), fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (country.code == FirefoxVpnServers.RECOMMENDED_CODE) {
                    stringResource(R.string.firefox_vpn_recommended)
                } else {
                    country.name
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 1
            )
            if (country.code != FirefoxVpnServers.RECOMMENDED_CODE) {
                Text(
                    text = country.code,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
        Text(
            text = when {
                measuring -> "…"
                ping != null -> "$ping ms"
                else -> stringResource(R.string.firefox_vpn_unreachable)
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                ping == null && !measuring -> colors.onSurfaceVariant.copy(alpha = 0.6f)
                ping != null && ping < 150 -> colors.primary
                else -> colors.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 6.dp)
        )
        Spacer(Modifier.width(8.dp))
        if (selected) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.primary)
            )
        }
    }
}

/** Says plainly whether the tunnel really carries traffic. */
@Composable
private fun TunnelState(tested: Boolean?, country: String) {
    val colors = MaterialTheme.colorScheme
    val (text, ink) = when (tested) {
        true -> stringResource(R.string.firefox_vpn_verified, country) to colors.primary
        false -> stringResource(R.string.firefox_vpn_silent, country) to colors.error
        null -> stringResource(R.string.firefox_vpn_testing, country) to colors.onSurfaceVariant
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ink.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
            color = ink
        )
    }
}

// ── The way out through Cloudflare ────────────────────────────────
//
// Kept although the screen no longer shows it: the card comes back when the
// relay is wanted again. Nothing below is compiled away, and the handler and
// the view model behind it are untouched.

@Composable
private fun RelayCard(
    enabled: Boolean,
    address: String,
    onToggle: (Boolean) -> Unit,
    onAddressChange: (String) -> Unit,
    onSave: () -> Unit,
    onShowCode: () -> Unit,
    onCheck: () -> Unit,
    verdict: String?
) {
    val colors = MaterialTheme.colorScheme
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = onToggle)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.firefox_vpn_relay_intro),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 18.sp),
                color = colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        for (step in listOf(
            R.string.firefox_vpn_relay_step1,
            R.string.firefox_vpn_relay_step2,
            R.string.firefox_vpn_relay_step3
        )) {
            Text(
                text = stringResource(step),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
                color = colors.onSurface,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        TextButton(onClick = onShowCode, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.firefox_vpn_relay_show_code),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont),
                color = colors.primary
            )
        }
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            label = { Text(stringResource(R.string.firefox_vpn_relay_address)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onSave,
                enabled = address.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    stringResource(R.string.firefox_vpn_relay_save),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = RxPromptFont)
                )
            }
            OutlinedButton(
                onClick = onCheck,
                enabled = address.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    stringResource(R.string.firefox_vpn_check_relay),
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = RxPromptFont)
                )
            }
        }
        verdict?.let { value ->
            Spacer(Modifier.height(8.dp))
            val text = when {
                value == "ready" -> stringResource(R.string.firefox_vpn_relay_ready)
                value == "wrong-key" -> stringResource(R.string.firefox_vpn_relay_wrong_key)
                value == "missing" -> stringResource(R.string.firefox_vpn_relay_missing)
                value.startsWith("refused") -> stringResource(
                    R.string.firefox_vpn_relay_refused,
                    value.removePrefix("refused: ").trim()
                )
                else -> value
            }
            val good = value == "ready"
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = RxPromptFont, lineHeight = 18.sp),
                color = if (good) colors.primary else colors.error
            )
        }
    }
}

@Composable
private fun RelayCodeDialog(code: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .clickable(enabled = false, onClick = {})
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.firefox_vpn_relay_code_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = RxPromptFont, fontSize = 18.sp),
                color = colors.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.firefox_vpn_relay_code_note),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = RxPromptFont, lineHeight = 17.sp),
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.4f))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text = code,
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = colors.onSurface
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("relay", code))
                        copied = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (copied) R.string.firefox_vpn_relay_copied else R.string.firefox_vpn_relay_copy
                        ),
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont)
                    )
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.firefox_vpn_web_close),
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont)
                    )
                }
            }
        }
    }
}

// ── Adding an account ─────────────────────────────────────────────

@Composable
private fun AddAccountSheet(
    isLoading: Boolean,
    needCodeType: String?,
    pendingEmail: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
    onSubmitCode: (String) -> Unit,
    onResend: () -> Unit,
    onCheckAgain: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .clickable(enabled = false, onClick = {})
                .padding(18.dp)
        ) {
            Text(
                text = stringResource(R.string.firefox_vpn_add_account),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = RxPromptFont, fontSize = 19.sp),
                color = colors.onSurface
            )
            Spacer(Modifier.height(14.dp))

            if (needCodeType == null) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.firefox_vpn_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.firefox_vpn_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onSubmit(email.trim(), password) },
                    enabled = !isLoading && email.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        stringResource(R.string.firefox_vpn_sign_in),
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont)
                    )
                }
            } else {
                if (needCodeType == "totp") {
                    Text(
                        text = stringResource(R.string.firefox_vpn_enter_totp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
                        color = colors.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.firefox_vpn_code_sent, pendingEmail.orEmpty()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 19.sp),
                        color = colors.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.firefox_vpn_where_mail),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = RxPromptFont, lineHeight = 17.sp),
                        color = colors.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { openMail(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.firefox_vpn_open_mail),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = RxPromptFont)
                            )
                        }
                        OutlinedButton(
                            onClick = onResend,
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.firefox_vpn_resend),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = RxPromptFont)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.firefox_vpn_code_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onSubmitCode(code.trim()) },
                    enabled = !isLoading && code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        stringResource(R.string.firefox_vpn_verify),
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont)
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onCheckAgain, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.firefox_vpn_check_again),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = RxPromptFont),
                        color = colors.primary
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont),
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

// ── The picture check ─────────────────────────────────────────────

@Composable
private fun CaptchaDialog(
    imageDataUrl: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var answer by remember { mutableStateOf("") }
    val bitmap = remember(imageDataUrl) { decodeDataUrl(imageDataUrl) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.firefox_vpn_captcha_title),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = RxPromptFont, fontSize = 18.sp),
                color = colors.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.firefox_vpn_captcha_hint),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = RxPromptFont, lineHeight = 18.sp),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))

            if (bitmap != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(6.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(90.dp)
                    )
                }
            } else {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text(stringResource(R.string.firefox_vpn_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onSubmit(answer) },
                enabled = answer.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    stringResource(R.string.firefox_vpn_captcha_send),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont)
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = RxPromptFont),
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/** Turns the `data:image/...;base64,...` the edge sends into something drawable. */
private fun decodeDataUrl(dataUrl: String): android.graphics.Bitmap? = try {
    val payload = dataUrl.substringAfter(",", "")
    if (payload.isEmpty()) {
        null
    } else {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
} catch (_: Exception) {
    null
}

/** The flag of a two-letter country code; a globe for the recommended location. */
internal fun flagOf(code: String): String {
    val upper = code.uppercase(Locale.US)
    if (upper.length != 2 || upper[0] !in 'A'..'Z' || upper[1] !in 'A'..'Z') return "🌐"
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + upper[0].code)) +
        String(Character.toChars(base + upper[1].code))
}

/** Opens whatever the phone has for mail, so the code can be read. */
private fun openMail(context: android.content.Context) {
    val candidates = listOf(
        android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_APP_EMAIL)
        },
        android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://mail.google.com")
        )
    )
    for (intent in candidates) {
        try {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Try the next one.
        }
    }
}
