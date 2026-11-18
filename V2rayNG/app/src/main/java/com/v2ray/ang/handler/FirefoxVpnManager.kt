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

        when (val result = FirefoxVpnAuth.refreshOAuth(account.refreshToken, account.clientId)) {
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
     * Fetches the pass Mozilla issues for this account. The pass is what the
     * proxy is asked with, and every location takes the same one.
     */
    suspend fun fetchPass(
        context: Context,
        account: FirefoxVpnStore.StoredAccount
    ): FirefoxVpnGuardian.ProxyPass? = withContext(Dispatchers.IO) {
        val accessToken = ensureAccessToken(context, account) ?: return@withContext null
        val passResult = FirefoxVpnGuardian.fetchProxyPass(accessToken)
        when (passResult) {
            is FirefoxVpnGuardian.PassResult.Success -> passResult.pass
            is FirefoxVpnGuardian.PassResult.Error -> {
                if (passResult.statusCode != 403) return@withContext null
                FirefoxVpnGuardian.activate(accessToken)
                val retry = FirefoxVpnGuardian.fetchProxyPass(accessToken)
                when (retry) {
                    is FirefoxVpnGuardian.PassResult.Success -> retry.pass
                    is FirefoxVpnGuardian.PassResult.Error -> null
                }
            }
        }
    }

    suspend fun connect(
        context: Context,
        account: FirefoxVpnStore.StoredAccount,
        countryCode: String?
    ): ConnectResult = withContext(Dispatchers.IO) {
        val pass = fetchPass(context, account)
            ?: return@withContext ConnectResult.Error("no-pass")
        connectWithPass(context, account, pass, countryCode)
    }

    /**
     * Opens the tunnel with a pass that was already fetched.
     *
     * Which address to dial is not written in one place: the server list names
     * one endpoint per location and the pass names another (Guardian's own
     * address, which is only a name in the token). Every one of them is tried
     * once, and the one that takes a tunnel is the one that is used.
     */
    suspend fun connectWithPass(
        context: Context,
        account: FirefoxVpnStore.StoredAccount,
        pass: FirefoxVpnGuardian.ProxyPass,
        countryCode: String?
    ): ConnectResult = withContext(Dispatchers.IO) {
        val countries = FirefoxVpnServers.fetchServerList()
        // A location that was chosen is the location that is used. The list is
        // fetched again here, and the code that was saved a moment ago used to be
        // matched against it loosely enough that a missing entry fell back to the
        // recommended one — so choosing a country could land anywhere, which is
        // exactly what was reported. A code that is not in the list is an error to
        // be said out loud, not a reason to pick another one.
        val country = countries.firstOrNull { it.code == countryCode }
        if (countryCode != null && countryCode != FirefoxVpnServers.RECOMMENDED_CODE && country == null) {
            return@withContext ConnectResult.Error("no-such-country:$countryCode")
        }
        val chosenCountry = country ?: FirefoxVpnServers.pickRecommended(countries)
        val countryName = chosenCountry?.name ?: "Recommended"

        // Nearest first: the list names several addresses for one location and
        // does not say which of them is close, so the tunnel used to be given
        // whichever came first. The relay still falls back down the list.
        val endpoints = FirefoxVpnServers.nearestFirst(endpointsOf(countries, chosenCountry, pass))
        if (endpoints.isEmpty()) {
            return@withContext ConnectResult.Error("no-address")
        }

        // The core cannot send a Bearer header itself; the relay in front of
        // it does, and the core talks plain HTTP to the loopback. When the
        // proxy cannot be reached from this phone, the pipe is carried by the
        // user's own Cloudflare Worker instead.
        FirefoxVpnRelay.attachContext(context)
        val relayAddress = FirefoxVpnCloudflare.relayAddressOrNull(context)
        val localPort = FirefoxVpnRelay.start(endpoints, pass.token, relayAddress)
        val chosen = FirefoxVpnRelay.endpoint() ?: endpoints.first()

        ConnectResult.Success(
            ConnectionInfo(
                proxyHost = chosen.host,
                proxyPort = chosen.port,
                proxyPassToken = pass.token,
                countryName = countryName,
                quotaRemaining = pass.quotaRemaining,
                quotaLimit = pass.quotaLimit,
                localPort = localPort
            )
        )
    }

    /**
     * The account's real standing with the service, read from the service itself.
     *
     * The figure that comes with a pass is what was left when that pass was
     * minted — and the app mints one per connection, so an account that has been
     * used for days still showed a full allowance, because the pass it showed was
     * a new one. That is the "reads the quota from zero" the owner reported. This
     * asks the service directly, with the account's own token, and returns what it
     * says is left now.
     *
     * Null when there is nothing to read from: no account, no token, or a service
     * that did not answer. A stale figure is worse than none, so the caller is
     * told to keep what it has rather than draw a full bar.
     */
    suspend fun liveQuota(context: Context): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val account = FirefoxVpnStore.loadAccounts(context)
            .firstOrNull { it.id == FirefoxVpnStore.loadActiveAccountId(context) }
            ?: FirefoxVpnStore.loadAccounts(context).firstOrNull()
            ?: return@withContext null
        // The stored token may have run out: an expired one answers 401, and the
        // log is full of them. Going through the same path a connection uses means
        // the token is refreshed when it needs to be, instead of the allowance
        // silently reading as unknown because the request was refused.
        val token = ensureAccessToken(context, account)?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        FirefoxVpnGuardian.checkQuota(token)
    }

    /**
     * Every address worth dialling for [country]: its own servers first, then
     * the recommended location's, and last the address named in the pass.
     */
    private fun endpointsOf(
        countries: List<FirefoxVpnServers.Country>,
        chosenCountry: FirefoxVpnServers.Country?,
        pass: FirefoxVpnGuardian.ProxyPass
    ): List<FirefoxVpnRelay.Endpoint> {
        val list = mutableListOf<FirefoxVpnRelay.Endpoint>()
        fun add(host: String, port: Int, label: String) {
            if (host.isEmpty() || port <= 0) return
            if (list.any { it.host == host && it.port == port }) return
            list.add(FirefoxVpnRelay.Endpoint(host, port, label))
        }
        fun addCountry(item: FirefoxVpnServers.Country?, limit: Int) {
            if (item == null) return
            FirefoxVpnServers.serversFor(item)
                .filter { server -> server.connectHost.isNotEmpty() && server.scheme == "https" }
                .take(limit)
                .forEach { server -> add(server.connectHost, server.tunnelPort, item.name) }
        }
        // The address the pass was minted for comes first, and nothing before it.
        // A pass is signed for one door: its token says which proxy it may be
        // presented to, and the door it does not name answers 421 Misdirected
        // Request. Dialling the country's other servers first meant every
        // connection began by having a handful of tunnels refused — the run of
        // 401 and 421 lines in the log — and only then reached the one address
        // that had been waiting to work all along.
        add(pass.proxyHost, pass.proxyPort, "the pass")
        // Then the chosen country's own addresses, to survive that door being
        // busy or gone. A recommended location is added only when there was no
        // choice at all — otherwise "choose Germany" could be answered by a
        // tunnel that leaves somewhere else, which is what made the choice look
        // random.
        addCountry(chosenCountry, 4)
        if (chosenCountry == null) {
            val recommended = FirefoxVpnServers.pickRecommended(countries)
            if (recommended != null) addCountry(recommended, 2)
        }
        return list.take(5)
    }

    /** Creates a new account ID. */
    fun newAccountId(): String = UUID.randomUUID().toString()

    /** Checks if a token needs refresh (expires within 5 minutes). */
    fun needsRefresh(account: FirefoxVpnStore.StoredAccount): Boolean {
        return account.expiresAt < System.currentTimeMillis() + 300_000
    }
}
