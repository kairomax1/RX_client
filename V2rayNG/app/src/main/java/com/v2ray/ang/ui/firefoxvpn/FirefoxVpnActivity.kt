package com.v2ray.ang.ui.firefoxvpn

import android.app.Application
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppLog
import com.v2ray.ang.handler.FirefoxVpnAuth
import com.v2ray.ang.handler.FirefoxVpnManager
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

class FirefoxVpnActivity : BaseComponentActivity() {

    private val viewModel: FirefoxVpnViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        Box(Modifier.fillMaxSize().background(rxScreenBrush())) {
            RxFogBackground(Modifier.fillMaxSize())
            FirefoxVpnScreen(viewModel)
        }
    }

    companion object {
        fun start(context: android.content.Context) {
            context.startActivity(Intent(context, FirefoxVpnActivity::class.java))
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
        val needCodeType: String? = null, // "email" or "totp"
        val connectionInfo: FirefoxVpnManager.ConnectionInfo? = null,
        val quotaInfo: Pair<Long, Long>? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

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
                selectedCountryCode = countryCode
            )
        }
    }

    fun loadCountries() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val countries = FirefoxVpnServers.fetchServerList()
            _state.update {
                it.copy(
                    countries = countries,
                    isLoading = false,
                    selectedCountryCode = it.selectedCountryCode
                        ?: countries.firstOrNull { c -> c.code == "REC" }?.code
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
        _state.update { it.copy(showAddAccount = true, error = null) }
    }

    fun hideAddAccount() {
        _state.update {
            it.copy(
                showAddAccount = false,
                pendingSessionToken = null,
                pendingEmail = null,
                needCodeType = null,
                error = null
            )
        }
    }

    fun addAccount(email: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val result = FirefoxVpnAuth.login(email, password)) {
                is FirefoxVpnAuth.LoginResult.Success -> {
                    // Exchange for OAuth tokens
                    when (val tokenResult = FirefoxVpnAuth.exchangeForOAuth(result.sessionToken)) {
                        is FirefoxVpnAuth.TokenResult.Success -> {
                            val tokens = tokenResult.tokens
                            val account = FirefoxVpnStore.StoredAccount(
                                id = FirefoxVpnManager.newAccountId(),
                                email = result.email,
                                accessToken = tokens.accessToken,
                                refreshToken = tokens.refreshToken,
                                expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000,
                                createdAt = System.currentTimeMillis()
                            )
                            FirefoxVpnStore.saveAccount(getApplication(), account)
                            FirefoxVpnStore.saveActiveAccountId(getApplication(), account.id)
                            FirefoxVpnAuth.registerDevice(tokens.refreshToken)
                            loadState()
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    showAddAccount = false,
                                    success = "Account added: ${result.email}"
                                )
                            }
                            AppLog.info(AppLog.Section.Other, "Firefox VPN: account added ${result.email}")
                        }
                        is FirefoxVpnAuth.TokenResult.Error -> {
                            _state.update {
                                it.copy(isLoading = false, error = tokenResult.message)
                            }
                        }
                    }
                }
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
            if (verified) {
                when (val tokenResult = FirefoxVpnAuth.exchangeForOAuth(sessionToken)) {
                    is FirefoxVpnAuth.TokenResult.Success -> {
                        val tokens = tokenResult.tokens
                        val email = _state.value.pendingEmail ?: ""
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
                        FirefoxVpnAuth.registerDevice(tokens.refreshToken)
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
                    }
                    is FirefoxVpnAuth.TokenResult.Error -> {
                        _state.update { it.copy(isLoading = false, error = tokenResult.message) }
                    }
                }
            } else {
                _state.update { it.copy(isLoading = false, error = "Invalid code") }
            }
        }
    }

    fun removeAccount(id: String) {
        FirefoxVpnStore.removeAccount(getApplication(), id)
        loadState()
    }

    fun connect() {
        val account = _state.value.accounts.firstOrNull { it.id == _state.value.activeAccountId }
            ?: return
        val countryCode = _state.value.selectedCountryCode
        viewModelScope.launch {
            _state.update { it.copy(isConnecting = true, error = null) }
            when (val result = FirefoxVpnManager.connect(getApplication(), account, countryCode)) {
                is FirefoxVpnManager.ConnectResult.Success -> {
                    val info = result.info
                    // Create a ProfileItem for the Firefox VPN proxy
                    val profile = com.v2ray.ang.dto.entities.ProfileItem(
                        configType = com.v2ray.ang.enums.EConfigType.HTTP,
                        remarks = "Firefox VPN (${info.countryName})",
                        server = info.proxyHost,
                        serverPort = info.proxyPort.toString(),
                        username = "bearer",
                        password = info.proxyPassToken,
                        network = "tcp",
                        security = "tls",
                        sni = info.proxyHost
                    )
                    val guid = java.util.UUID.randomUUID().toString()
                    com.v2ray.ang.handler.MmkvManager.encodeServerConfig(guid, profile)
                    com.v2ray.ang.handler.MmkvManager.setSelectServer(guid)

                    // Start the VPN service
                    com.v2ray.ang.core.LauncherManager.startService(
                        getApplication(), guid = guid
                    )

                    _state.update {
                        it.copy(
                            isConnecting = false,
                            connectionInfo = info,
                            success = "Connected via ${info.countryName}"
                        )
                    }
                    AppLog.info(
                        AppLog.Section.Connection,
                        "Firefox VPN connected via ${info.countryName} (${info.proxyHost})"
                    )
                }
                is FirefoxVpnManager.ConnectResult.Error -> {
                    _state.update { it.copy(isConnecting = false, error = result.message) }
                }
            }
        }
    }

    fun disconnect() {
        com.v2ray.ang.core.LauncherManager.stopService(getApplication())
        _state.update { it.copy(connectionInfo = null) }
        AppLog.info(AppLog.Section.Connection, "Firefox VPN disconnected")
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, success = null) }
    }
}

// ── Screen ────────────────────────────────────────────────────────

@Composable
private fun FirefoxVpnScreen(viewModel: FirefoxVpnViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadCountries()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { (context as? android.app.Activity)?.finish() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back_24dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.firefox_vpn_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = RxPromptFont,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Description
        Text(
            text = stringResource(R.string.firefox_vpn_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = RxPromptFont
        )

        Spacer(Modifier.height(16.dp))

        // Error / Success messages
        state.error?.let { error ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp)
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontFamily = RxPromptFont
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        state.success?.let { success ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(12.dp)
            ) {
                Text(
                    text = success,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontFamily = RxPromptFont
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Connection status
        if (state.connectionInfo != null) {
            ConnectionCard(state.connectionInfo!!)
            Spacer(Modifier.height(16.dp))
        }

        // Accounts section
        Text(
            text = stringResource(R.string.firefox_vpn_accounts),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = RxPromptFont
        )
        Spacer(Modifier.height(8.dp))

        if (state.accounts.isEmpty()) {
            Text(
                text = stringResource(R.string.firefox_vpn_no_accounts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = RxPromptFont
            )
        } else {
            state.accounts.forEach { account ->
                AccountCard(
                    account = account,
                    isActive = account.id == state.activeAccountId,
                    onSelect = { viewModel.selectAccount(account.id) },
                    onRemove = { viewModel.removeAccount(account.id) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // Add account button
        OutlinedButton(
            onClick = { viewModel.showAddAccount() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.firefox_vpn_add_account),
                fontFamily = RxPromptFont
            )
        }

        Spacer(Modifier.height(16.dp))

        // Country picker
        Text(
            text = stringResource(R.string.firefox_vpn_country),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = RxPromptFont
        )
        Spacer(Modifier.height(8.dp))

        if (state.isLoading && state.countries.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            state.countries.forEach { country ->
                val isSelected = country.code == state.selectedCountryCode
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { viewModel.selectCountry(country.code) }
                        .padding(12.dp)
                ) {
                    Text(
                        text = country.name,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = RxPromptFont
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Connect / Disconnect button
        if (state.connectionInfo != null) {
            Button(
                onClick = { viewModel.disconnect() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.firefox_vpn_disconnect),
                    fontFamily = RxPromptFont
                )
            }
        } else {
            Button(
                onClick = { viewModel.connect() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.accounts.isNotEmpty() && !state.isConnecting
            ) {
                if (state.isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text(
                    text = stringResource(R.string.firefox_vpn_connect),
                    fontFamily = RxPromptFont
                )
            }
        }

        // Quota info
        state.connectionInfo?.let { info ->
            if (info.quotaLimit > 0) {
                Spacer(Modifier.height(12.dp))
                val usedGb = (info.quotaLimit - info.quotaRemaining) / 1_000_000_000.0
                val totalGb = info.quotaLimit / 1_000_000_000.0
                Text(
                    text = stringResource(R.string.firefox_vpn_quota, usedGb, totalGb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = RxPromptFont
                )
                LinearProgressIndicator(
                    progress = { (usedGb / totalGb).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            }
        }
    }

    // Add account dialog
    if (state.showAddAccount) {
        AddAccountDialog(
            pendingSessionToken = state.pendingSessionToken,
            pendingEmail = state.pendingEmail,
            needCodeType = state.needCodeType,
            isLoading = state.isLoading,
            onDismiss = { viewModel.hideAddAccount() },
            onSubmit = { email, password -> viewModel.addAccount(email, password) },
            onSubmitCode = { code -> viewModel.submitCode(code) }
        )
    }
}

@Composable
private fun ConnectionCard(info: FirefoxVpnManager.ConnectionInfo) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = stringResource(R.string.firefox_vpn_connected),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontFamily = RxPromptFont
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = info.countryName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontFamily = RxPromptFont
            )
            Text(
                text = info.proxyHost,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                fontFamily = RxPromptFont
            )
        }
    }
}

@Composable
private fun AccountCard(
    account: FirefoxVpnStore.StoredAccount,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                1.dp,
                if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onSelect)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.email,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = RxPromptFont
                )
                if (isActive) {
                    Text(
                        text = stringResource(R.string.firefox_vpn_active),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        fontFamily = RxPromptFont
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_24dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun AddAccountDialog(
    pendingSessionToken: String?,
    pendingEmail: String?,
    needCodeType: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
    onSubmitCode: (String) -> Unit
) {
    var email by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var password by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var code by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.firefox_vpn_add_account),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = RxPromptFont
                )
                Spacer(Modifier.height(16.dp))

                if (pendingSessionToken != null) {
                    // Code entry mode
                    Text(
                        text = if (needCodeType == "totp") {
                            stringResource(R.string.firefox_vpn_enter_totp)
                        } else {
                            stringResource(R.string.firefox_vpn_enter_email_code, pendingEmail ?: "")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = RxPromptFont
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.firefox_vpn_code)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onSubmitCode(code) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && code.isNotBlank()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(stringResource(R.string.firefox_vpn_verify))
                    }
                } else {
                    // Email/password mode
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.firefox_vpn_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.firefox_vpn_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onSubmit(email, password) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(stringResource(R.string.firefox_vpn_sign_in))
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
