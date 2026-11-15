package com.v2ray.ang.handler

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Server(
        val hostname: String,
        val port: Int = 443,
        val quarantined: Boolean = false
    )

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

    /** Fetches and parses the server list. Returns empty list on failure. */
    suspend fun fetchServerList(): List<Country> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(REMOTE_SETTINGS_URL)
                .get()
                .header("User-Agent", FIREFOX_UA)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JsonParser.parseString(body).asJsonObject
            val data = json.getAsJsonArray("data") ?: return@withContext emptyList()

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
                                val port = server.get("port")?.asInt ?: 443
                                val quarantined = server.get("quarantined")?.asBoolean ?: false
                                servers.add(Server(hostname, port, quarantined))
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

    /** Returns the "Recommended" (anycast) entry, or the first country. */
    fun pickRecommended(countries: List<Country>): Country? {
        return countries.firstOrNull { it.code == "REC" }
            ?: countries.firstOrNull()
    }

    /** Gets all non-quarantined servers for a country. */
    fun serversFor(country: Country): List<Server> {
        return country.cities.flatMap { it.servers }.filter { !it.quarantined }
    }
}
