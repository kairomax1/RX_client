package com.v2ray.ang.handler

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches Mozilla's VPN server list from Remote Settings.
 *
 * The list contains countries, cities, and proxy servers with their
 * hostnames and ports. Firefox uses this to let users pick a location.
 */
object FirefoxVpnServers {

    private const val REMOTE_SETTINGS_URL =
        "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"
    private const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
    private const val PREF_SERVERLIST_JSON = "pref_rx_firefox_serverlist_json"
    private const val PREF_SERVERLIST_AT = "pref_rx_firefox_serverlist_at"
    private const val DAILY_REFRESH_MILLIS = 24 * 60 * 60 * 1000L

    /** The reserved code of the recommended (anycast) location. */
    const val RECOMMENDED_CODE = "REC"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * One proxy endpoint. [host] and [port] are the CONNECT endpoint the record
     * names in its protocols; that is what a client connects to, while
     * [hostname] is only the record's own name for the server.
     */
    data class Server(
        val hostname: String,
        val port: Int = 443,
        val quarantined: Boolean = false,
        val host: String = "",
        val connectPort: Int = 0,
        val scheme: String = "https"
    ) {
        /** The endpoint to open a TLS CONNECT tunnel to. */
        val connectHost: String get() = host.ifEmpty { hostname }
        val tunnelPort: Int get() = if (connectPort > 0) connectPort else port
    }

    data class City(
        val name: String,
        val code: String,
        val servers: List<Server>
    )

    data class Country(
        val name: String,
        val code: String,
        val cities: List<City>,
        val locked: Boolean = false
    )

    /** Fetches and parses the server list, falling back to the last cached copy if offline. */
    suspend fun fetchServerList(): List<Country> = withContext(Dispatchers.IO) {
        val url = RemoteControl.current().mozilla.serverListUrl
            ?.trim()?.takeIf { it.isNotEmpty() } ?: REMOTE_SETTINGS_URL
        val ua = RemoteControl.mozillaUserAgentOr(FIREFOX_UA)
        val liveBody = runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", ua)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()

        if (!liveBody.isNullOrBlank()) {
            val parsed = parseServerList(liveBody)
            if (parsed.isNotEmpty()) {
                runCatching {
                    MmkvManager.encodeSettings(PREF_SERVERLIST_JSON, liveBody)
                    MmkvManager.encodeSettings(PREF_SERVERLIST_AT, System.currentTimeMillis())
                }
                return@withContext appendRemoteEndpoints(parsed)
            }
        }

        val cached = runCatching { MmkvManager.decodeSettingsString(PREF_SERVERLIST_JSON) }.getOrNull()
        val fallback = if (!cached.isNullOrBlank()) parseServerList(cached) else emptyList()
        appendRemoteEndpoints(fallback)
    }

    /** Silently refreshes Mozilla's server list once per day in the background. */
    suspend fun refreshDailyIfDue(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = runCatching { MmkvManager.decodeSettingsLong(PREF_SERVERLIST_AT, 0L) }.getOrDefault(0L)
        if (last > 0L && now - last < DAILY_REFRESH_MILLIS) return@withContext false
        fetchServerList().isNotEmpty()
    }

    private fun parseServerList(body: String): List<Country> {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val data = json.getAsJsonArray("data") ?: return emptyList()

            val countries = mutableListOf<Country>()
            for (record in data) {
                val obj = record.asJsonObject
                val name = obj.get("name")?.asString ?: continue
                val code = obj.get("code")?.asString ?: continue
                val locked = obj.get("locked")?.asBoolean ?: false

                val cities = mutableListOf<City>()
                val citiesArray = obj.getAsJsonArray("cities")
                if (citiesArray != null) {
                    for (cityObj in citiesArray) {
                        val city = cityObj.asJsonObject
                        val cityName = city.get("name")?.asString ?: continue
                        val cityCode = city.get("code")?.asString ?: continue

                        val servers = mutableListOf<Server>()
                        val serversArray = city.getAsJsonArray("servers")
                        if (serversArray != null) {
                            for (serverObj in serversArray) {
                                val server = serverObj.asJsonObject
                                val hostname = server.get("hostname")?.asString ?: continue
                                val port = server.get("port")?.asInt?.takeIf { it > 0 } ?: 443
                                val quarantined = server.get("quarantined")?.asBoolean ?: false
                                // The record names its real endpoint in protocols:
                                // name "connect" is the HTTPS proxy Firefox uses.
                                var connectHost = ""
                                var connectPort = 0
                                var scheme = "https"
                                server.getAsJsonArray("protocols")?.forEach { protoElement ->
                                    val proto = protoElement.asJsonObject
                                    if (proto.string("name") == "connect" && connectHost.isEmpty()) {
                                        connectHost = proto.string("host").orEmpty()
                                        connectPort = proto.get("port")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                        scheme = proto.string("scheme").orEmpty().ifEmpty { "https" }
                                    }
                                }
                                servers.add(Server(hostname, port, quarantined, connectHost, connectPort, scheme))
                            }
                        }
                        if (servers.isNotEmpty()) {
                            cities.add(City(cityName, cityCode, servers))
                        }
                    }
                }

                if (cities.isNotEmpty()) {
                    countries.add(Country(name, code, cities, locked))
                }
            }

            countries
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appendRemoteEndpoints(countries: List<Country>): List<Country> {
        val extras = RemoteControl.current().mozilla.extraEndpoints
            .filter { it.host.isNotBlank() }
        if (extras.isEmpty()) return countries
        val extraServers = extras.map { ep ->
            Server(
                hostname = ep.name.ifBlank { ep.host },
                port = ep.port.takeIf { it > 0 } ?: 443,
                quarantined = false,
                host = ep.host.trim(),
                connectPort = ep.port.takeIf { it > 0 } ?: 443,
                scheme = "https",
            )
        }
        val extraCountry = Country(
            name = "RX Edge",
            code = "RXE",
            cities = listOf(City("Remote", "rxe", extraServers)),
            locked = false,
        )
        return listOf(extraCountry) + countries
    }

    /** Returns the "Recommended" (anycast) entry, or the first country. */
    fun pickRecommended(countries: List<Country>): Country? {
        return countries.firstOrNull { it.code == "REC" }
            ?: countries.firstOrNull()
    }

    /** Gets all non-quarantined servers for a country. */
    fun serversFor(country: Country): List<Server> {
        return country.cities.flatMap { it.servers }.filter { !it.quarantined }
    }

    /**
     * How long a real page takes through this country, in milliseconds: the
     * handshake to Mozilla's proxy, the CONNECT, the handshake to the page and
     * its own answer. It is measured through the proxy and not to it, so the
     * number moves when this phone's own connection moves.
     *
     * Null when any part of that chain stays silent. Nothing is made up: a
     * country that does not carry a page has no number at all.
     */
    suspend fun ping(country: Country, token: String, timeoutMillis: Int = 9_000): Int? =
        withContext(Dispatchers.IO) {
            val endpoint = connectEndpoint(country) ?: return@withContext null
            FirefoxVpnRelay.measure(
                FirefoxVpnRelay.Endpoint(endpoint.connectHost, endpoint.tunnelPort, country.name),
                token,
                timeoutMillis
            )
        }

    /**
     * Puts [endpoints] in the order they answer this phone, the quickest first.
     *
     * A location names several endpoints and nothing says which of them is near,
     * so the one that happens to be first in the list is the one that was used,
     * and a phone in Iran could be handed a server on the other side of the
     * world while a closer one sat two lines below it. Measuring is cheaper than
     * the tunnel being slow for as long as it is up.
     *
     * Nothing is added and nothing is left out: an endpoint that stays silent
     * keeps its place at the end, so the tunnel can still fall back to it.
     */
    suspend fun nearestFirst(
        endpoints: List<FirefoxVpnRelay.Endpoint>,
        timeoutMillis: Int = 2_500,
    ): List<FirefoxVpnRelay.Endpoint> = withContext(Dispatchers.IO) {
        if (endpoints.size < 2) return@withContext endpoints
        coroutineScope {
            endpoints
                .map { endpoint -> async { endpoint to reachMillis(endpoint, timeoutMillis) } }
                .awaitAll()
                .sortedBy { (_, millis) -> millis }
                .map { (endpoint, _) -> endpoint }
        }
    }

    /** How long a connection to [endpoint] takes to be made; [Long.MAX_VALUE] when it never is. */
    private fun reachMillis(endpoint: FirefoxVpnRelay.Endpoint, timeoutMillis: Int): Long {
        val millis = SpeedtestManager.socketConnectTime(endpoint.host, endpoint.port, timeoutMillis)
        return if (millis < 0L) Long.MAX_VALUE else millis
    }

    /** The first usable CONNECT endpoint of a country. */
    fun connectEndpoint(country: Country?): Server? =
        country?.let {
            serversFor(it).firstOrNull { server ->
                server.connectHost.isNotEmpty() && server.scheme == "https"
            }
        }

    private fun com.google.gson.JsonObject.string(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString
}
