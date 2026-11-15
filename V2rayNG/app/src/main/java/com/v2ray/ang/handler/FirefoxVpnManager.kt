package com.v2ray.ang.handler

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Orchestrates the Firefox VPN feature: account management, token lifecycle,
 * ProxyPass fetching, and Xray config generation.
 *
 * The VPN works by creating an HTTP outbound in Xray that points to the
 * Fastly proxy with a Bearer ProxyPass token. All traffic is routed through
 * Mozilla's servers.
 */
object FirefoxVpnManager {

    data class ConnectionInfo(
        val proxyHost: String,
        val proxyPort: Int,
        val proxyPassToken: String,
        val countryName: String,
        val quotaRemaining: Long,
        val quotaLimit: Long,
        /** Loopback port the core's HTTP outbound talks to; 0 before the relay starts. */
        val localPort: Int = 0
    )

    sealed class ConnectResult {
        data class Success(val info: ConnectionInfo) : ConnectResult()
        data class Error(val message: String) : ConnectResult()
    }

    /**
     * Ensures the account has a valid access token, refreshing if needed.
     * Returns the access token or null on failure.
     */
    suspend fun ensureAccessToken(
        context: Context,
        account: FirefoxVpnStore.StoredAccount
    ): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (account.expiresAt > now + 60_000) {
            return@withContext account.accessToken
        }

        // Token expired or about to expire — refresh
        if (account.refreshToken.isEmpty()) {
            return@withContext null
        }

        when (val result = FirefoxVpnAuth.refreshOAuth(account.refreshToken)) {
            is FirefoxVpnAuth.TokenResult.Success -> {
                val tokens = result.tokens
                FirefoxVpnStore.updateTokens(
                    context, account.id,
                    tokens.accessToken, tokens.refreshToken, tokens.expiresIn
                )
                tokens.accessToken
            }
            is FirefoxVpnAuth.TokenResult.Error -> null
        }
    }

    /**
     * Connects to the Firefox VPN: gets a ProxyPass and returns connection info
     * that can be used to build an Xray config.
     */
    suspend fun connect(
        context: Context,
        account: FirefoxVpnStore.StoredAccount,
        countryCode: String?
    ): ConnectResult = withContext(Dispatchers.IO) {
        // 1. Ensure valid access token
        val accessToken = ensureAccessToken(context, account)
            ?: return@withContext ConnectResult.Error("Token expired, please sign in again")

        // 2. Get ProxyPass from Guardian
        val passResult = FirefoxVpnGuardian.fetchProxyPass(accessToken)
        val pass = when (passResult) {
            is FirefoxVpnGuardian.PassResult.Success -> passResult.pass
            is FirefoxVpnGuardian.PassResult.Error -> {
                // Try activating if not entitled
                if (passResult.statusCode == 403) {
                    FirefoxVpnGuardian.activate(accessToken)
                    val retry = FirefoxVpnGuardian.fetchProxyPass(accessToken)
                    when (retry) {
                        is FirefoxVpnGuardian.PassResult.Success -> retry.pass
                        is FirefoxVpnGuardian.PassResult.Error ->
                            return@withContext ConnectResult.Error(retry.message)
                    }
                } else {
                    return@withContext ConnectResult.Error(passResult.message)
                }
            }
        }

        // 3. Find the CONNECT endpoint of the chosen location
        val countries = FirefoxVpnServers.fetchServerList()
        val country = countries.firstOrNull { it.code == countryCode }
            ?: FirefoxVpnServers.pickRecommended(countries)
        val countryName = country?.name ?: "Recommended"

        // The ProxyPass names the proxy it is valid for in its audience; a
        // different host would present a pass that is not meant for it. The
        // list's own endpoint is only the fallback.
        val endpoint = FirefoxVpnServers.connectEndpoint(country)
        val serverHost = pass.proxyHost.ifEmpty { endpoint?.connectHost.orEmpty() }
        val serverPort = if (pass.proxyHost.isNotEmpty()) pass.proxyPort else endpoint?.tunnelPort ?: 443
        if (endpoint != null && endpoint.scheme != "https") {
            return@withContext ConnectResult.Error("This location only offers ${endpoint.scheme}, which the app cannot use yet")
        }
        if (serverHost.isEmpty()) {
            return@withContext ConnectResult.Error("No proxy address was named for ${countryName.ifEmpty { "this location" }}")
        }

        // 4. The core cannot send a Bearer header itself; the relay in front of
        // it does, and the core talks plain HTTP to the loopback.
        val localPort = FirefoxVpnRelay.start(serverHost, serverPort, pass.token)

        ConnectResult.Success(
            ConnectionInfo(
                proxyHost = serverHost,
                proxyPort = serverPort,
                proxyPassToken = pass.token,
                countryName = countryName,
                quotaRemaining = pass.quotaRemaining,
                quotaLimit = pass.quotaLimit,
                localPort = localPort
            )
        )
    }

    /** Creates a new account ID. */
    fun newAccountId(): String = UUID.randomUUID().toString()

    /** Checks if a token needs refresh (expires within 5 minutes). */
    fun needsRefresh(account: FirefoxVpnStore.StoredAccount): Boolean {
        return account.expiresAt < System.currentTimeMillis() + 300_000
    }
}
