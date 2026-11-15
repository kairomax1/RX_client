package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.dto.V2rayConfig
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
        val quotaLimit: Long
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

        // 3. Determine proxy host from server list (or use pass's aud)
        val countries = FirefoxVpnServers.fetchServerList()
        val country = countries.firstOrNull { it.code == countryCode }
            ?: FirefoxVpnServers.pickRecommended(countries)
        val countryName = country?.name ?: "Recommended"

        // Use the server from the list if available, otherwise the pass aud
        val serverHost = country?.let { FirefoxVpnServers.serversFor(it).firstOrNull()?.hostname }
            ?: pass.proxyHost
        val serverPort = country?.let { FirefoxVpnServers.serversFor(it).firstOrNull()?.port }
            ?: pass.proxyPort

        ConnectResult.Success(
            ConnectionInfo(
                proxyHost = serverHost,
                proxyPort = serverPort,
                proxyPassToken = pass.token,
                countryName = countryName,
                quotaRemaining = pass.quotaRemaining,
                quotaLimit = pass.quotaLimit
            )
        )
    }

    /**
     * Builds an Xray outbound for the Firefox VPN proxy.
     * Uses HTTP outbound with Bearer ProxyPass in the Proxy-Authorization header.
     */
    fun buildOutbound(info: ConnectionInfo): V2rayConfig.OutboundBean {
        return V2rayConfig.OutboundBean(
            tag = "firefox-vpn",
            protocol = "http",
            settings = V2rayConfig.OutboundBean.OutSettingsBean(
                address = info.proxyHost,
                port = info.proxyPort,
                headers = mapOf(
                    "Proxy-Authorization" to "Bearer ${info.proxyPassToken}"
                )
            ),
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(
                network = "tcp",
                security = "tls",
                tlsSettings = V2rayConfig.OutboundBean.StreamSettingsBean.TlsSettingsBean(
                    serverName = info.proxyHost,
                    alpn = listOf("h2", "http/1.1")
                )
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
