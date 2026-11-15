package com.v2ray.ang.ui.firefoxvpn

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Base64
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.v2ray.ang.handler.FirefoxVpnCloudflare
import com.v2ray.ang.handler.FirefoxVpnManager
import com.v2ray.ang.handler.FirefoxVpnRelay
import com.v2ray.ang.handler.FirefoxVpnServers
import com.v2ray.ang.handler.FirefoxVpnStore
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.RxFogBackground
import com.v2ray.ang.ui.compose.RxPromptFont
import com.v2ray.ang.ui.compose.rxScreenBrush
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        val relayCode: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** Kept in memory for the retry after a code or a picture check; never written down. */
    private var pendingPassword: String? = null

    init {
        loadState()
    }

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
        _state.update { it.copy(selectedCountryCode = code) }
    }

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
                    saveTokens(email, tokenResult.tokens)
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
                    saveTokens(email, tokenResult.tokens)
                }
                is FirefoxVpnAuth.TokenResult.Error ->
                    _state.update { it.copy(isLoading = false, error = tokenResult.message) }
            }
        }
    }

    private fun storeAccount(result: FirefoxVpnAuth.LoginResult.Success) {
        viewModelScope.launch {
            when (val tokenResult = FirefoxVpnAuth.exchangeForOAuth(result.sessionToken)) {
                is FirefoxVpnAuth.TokenResult.Success -> saveTokens(result.email, tokenResult.tokens)
                is FirefoxVpnAuth.TokenResult.Error ->
                    _state.update { it.copy(isLoading = false, error = tokenResult.message) }
            }
        }
    }

    /** The browser flow learns the account's address from its tokens; show it. */
    private suspend fun addressOfTokens(tokens: FirefoxVpnAuth.OAuthTokens): String =
        FirefoxVpnAuth.profileEmail(tokens.accessToken) ?: "Firefox Account"

    private fun saveTokens(email: String, tokens: FirefoxVpnAuth.OAuthTokens) {
        val account = FirefoxVpnStore.StoredAccount(
            id = FirefoxVpnManager.newAccountId(),
            email = email,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000,
            createdAt = System.currentTimeMillis()
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

    fun completeBrowserSignIn(code: String) {
        val verifier = browserVerifier
        _state.update { it.copy(browserUrl = null, browserState = null) }
        if (verifier == null || code.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = FirefoxVpnAuth.finishBrowserSignIn(code, verifier)) {
                is FirefoxVpnAuth.TokenResult.Success -> {
                    browserVerifier = null
                    saveTokens(addressOfTokens(result.tokens), result.tokens)
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

    fun connect() {
        val account = _state.value.accounts.firstOrNull { it.id == _state.value.activeAccountId }
        if (account == null) {
            _state.update { it.copy(error = "Add a Firefox Account first") }
            return
        }
        val countryCode = _state.value.selectedCountryCode
        viewModelScope.launch {
            _state.update { it.copy(isConnecting = true, error = null, success = null) }
            when (val result = FirefoxVpnManager.connect(getApplication(), account, countryCode)) {
                is FirefoxVpnManager.ConnectResult.Success -> {
                    val info = result.info
                    val profile = com.v2ray.ang.dto.entities.ProfileItem(
                        configType = com.v2ray.ang.enums.EConfigType.HTTP,
                        remarks = "Firefox VPN (${info.countryName})",
                        server = "127.0.0.1",
                        serverPort = info.localPort.toString()
                    )
                    val guid = "firefox-vpn"
                    com.v2ray.ang.handler.MmkvManager.encodeServerConfig(guid, profile)
                    com.v2ray.ang.handler.MmkvManager.setSelectServer(guid)
                    com.v2ray.ang.core.LauncherManager.startService(getApplication(), guid = guid)
                    _state.update { it.copy(isConnecting = false, connectionInfo = info) }
                    AppLog.info(
                        AppLog.Section.Connection,
                        "Firefox VPN connected via ${info.countryName} " +
                            "(proxy ${info.proxyHost}, relay ${info.localPort})"
                    )
                }
                is FirefoxVpnManager.ConnectResult.Error -> {
                    FirefoxVpnRelay.stop()
                    _state.update { it.copy(isConnecting = false, error = result.message) }
                }
            }
        }
    }

    fun disconnect() {
        com.v2ray.ang.core.LauncherManager.stopService(getApplication())
        FirefoxVpnRelay.stop()
        _state.update { it.copy(connectionInfo = null) }
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
                success = null,
                error = null
            )
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
    val browserLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.data?.getStringExtra(FirefoxVpnWebSignInActivity.EXTRA_CODE).orEmpty()
        if (result.resultCode == android.app.Activity.RESULT_OK && code.isNotEmpty()) {
            viewModel.completeBrowserSignIn(code)
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
                Text(
                    text = stringResource(R.string.firefox_vpn_title),
                    style = androidx.compose.ui.text.TextStyle(fontFamily = RxPromptFont, fontSize = 22.sp),
                    color = colors.onSurface
                )
                Spacer(Modifier.width(10.dp))
                if (state.isLoading || state.isConnecting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                }
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
                    // The recommended location first, then the rest by name.
                    val ordered = state.countries.sortedWith(
                        compareBy({ it.code != FirefoxVpnServers.RECOMMENDED_CODE }, { it.name })
                    )
                    items(ordered) { country ->
                        CountryRow(
                            country = country,
                            selected = country.code == state.selectedCountryCode,
                            onSelect = { viewModel.selectCountry(country.code) }
                        )
                    }
                }

                // The way out through Cloudflare
                item { SectionTitle(stringResource(R.string.firefox_vpn_relay_title)) }
                item {
                    RelayCard(
                        enabled = state.relayEnabled,
                        address = state.relayAddress,
                        onToggle = { viewModel.setRelayEnabled(it) },
                        onAddressChange = { viewModel.setRelayAddress(it) },
                        onSave = { viewModel.saveRelayAddress() },
                        onShowCode = { viewModel.showRelayCode() }
                    )
                }

                // Usage
                state.connectionInfo?.let { info ->
                    item { SectionTitle(stringResource(R.string.firefox_vpn_section_usage)) }
                    item { QuotaCard(info) }
                }
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

    state.relayCode?.let { code ->
        RelayCodeDialog(code = code, onDismiss = { viewModel.hideRelayCode() })
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

@Composable
private fun QuotaCard(info: FirefoxVpnManager.ConnectionInfo) {
    val colors = MaterialTheme.colorScheme
    val limit = info.quotaLimit.coerceAtLeast(0)
    val used = (limit - info.quotaRemaining).coerceAtLeast(0)
    Card {
        Text(
            text = info.countryName,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = RxPromptFont),
            color = colors.onSurface
        )
        Spacer(Modifier.height(6.dp))
        if (limit > 0) {
            val fraction = (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.outline.copy(alpha = 0.25f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.primary)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.firefox_vpn_quota,
                    used / 1_000_000_000.0,
                    limit / 1_000_000_000.0
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

// ── The way out through Cloudflare ────────────────────────────────

@Composable
private fun RelayCard(
    enabled: Boolean,
    address: String,
    onToggle: (Boolean) -> Unit,
    onAddressChange: (String) -> Unit,
    onSave: () -> Unit,
    onShowCode: () -> Unit
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
        OutlinedButton(
            onClick = onSave,
            enabled = address.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                stringResource(R.string.firefox_vpn_relay_save),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = RxPromptFont)
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
