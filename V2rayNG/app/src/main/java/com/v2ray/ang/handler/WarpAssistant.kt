package com.v2ray.ang.handler

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.util.Curve25519
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Creates a free Cloudflare WARP account and stores it as an ordinary WireGuard profile, and builds
 * proxy chains ("tunnel in tunnel") that combine that profile with another config.
 *
 * Separate from [CloudflareAssistant] because the data differs: that one repairs the user's own
 * Cloudflare-fronted configs, this one owns a WARP account that Cloudflare issues. Both write into
 * the same local group, which [CloudflareAssistant.ensureGroup] owns.
 *
 * WARP is WireGuard over UDP towards Cloudflare. On a network that blocks that, the profile alone
 * does not connect; chained behind a config that does connect it still changes the exit address.
 */
object WarpAssistant {

    /** The parts of a registration answer a WireGuard profile needs. */
    data class Registration(
        val peerPublicKey: String,
        val endpointHost: String,
        val endpointPort: Int,
        val localAddress: String,
        val reserved: String,
    )

    /** How a chain uses WARP. */
    enum class ChainOrder {
        /** Traffic enters through the config and leaves through WARP. */
        ExitThroughWarp,

        /** Traffic enters through WARP and leaves through the config. */
        EnterThroughWarp,
    }

    fun warpGuid(): String? =
        MmkvManager.decodeSettingsString(AppConfig.PREF_WARP_PROFILE)
            ?.takeIf { it.isNotBlank() && MmkvManager.decodeServerConfig(it) != null }

    /**
     * Registers a new WARP account and saves it as the WARP profile of [groupId]; an existing WARP
     * profile is refreshed in place so chains that name it keep working.
     *
     * The direct request is tried first. The API host is often unreachable on a filtered network,
     * so when the tunnel is up the request is repeated through the local proxy.
     *
     * @return GUID of the profile, or null when the registration did not succeed.
     */
    suspend fun createProfile(context: Context, groupId: String): String? = withContext(Dispatchers.IO) {
        val privateKey = Curve25519.generatePrivateKey()
        val publicKey = Curve25519.publicKey(privateKey).toByteString().base64()
        val body = registrationBody(publicKey, Date())

        val ports = buildList {
            add(0)
            if (ServiceRuntimeState.isRunning(context)) add(SettingsManager.getHttpPort())
        }
        var answer: String? = null
        for (port in ports) {
            answer = try {
                HttpUtil.postJson(
                    url = REGISTER_URL,
                    body = body,
                    headers = REQUEST_HEADERS,
                    timeout = TIMEOUT_MS,
                    httpPort = port,
                    proxyUsername = SettingsManager.getSocksUsername(),
                    proxyPassword = SettingsManager.getSocksPassword(),
                    tls12Only = true,
                )
            } catch (e: Exception) {
                LogUtil.w(AppConfig.TAG, "WARP: registration failed (viaProxy=${port != 0})", e)
                null
            }
            if (answer != null) break
        }

        val registration = answer?.let { parseRegistration(it) }
        if (registration == null) {
            LogUtil.w(AppConfig.TAG, "WARP: no usable registration answer")
            return@withContext null
        }

        val profile = buildProfile(
            registration = registration,
            privateKey = privateKey.toByteString().base64(),
            groupId = groupId,
        )
        try {
            val guid = MmkvManager.encodeServerConfig(warpGuid().orEmpty(), profile)
            MmkvManager.encodeSettings(AppConfig.PREF_WARP_PROFILE, guid)
            SettingsChangeManager.makeSetupGroupTab()
            LogUtil.i(AppConfig.TAG, "WARP: profile saved ($guid)")
            guid
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "WARP: could not store the profile", e)
            null
        }
    }

    /**
     * Saves a proxy chain made of [otherGuid] and the WARP profile into [groupId].
     *
     * @return GUID of the chain, or null when one of the two cannot be a chain member.
     */
    fun createChain(otherGuid: String, order: ChainOrder, groupId: String): String? {
        val warpGuid = warpGuid() ?: return null
        if (otherGuid == warpGuid) return null
        val warp = MmkvManager.decodeServerConfig(warpGuid) ?: return null
        val other = MmkvManager.decodeServerConfig(otherGuid) ?: return null
        val members = chainMembers(other, warp, order) ?: return null

        // Asking twice for the same chain returns the one that exists instead of a duplicate.
        val wanted = members.joinToString(",")
        MmkvManager.decodeServerList(groupId).firstOrNull { guid ->
            val existing = MmkvManager.decodeServerConfig(guid)
            existing?.configType == EConfigType.PROXYCHAIN && existing.proxyChainProfiles == wanted
        }?.let { return it }

        val chain = ProfileItem.create(EConfigType.PROXYCHAIN).apply {
            remarks = members.joinToString(CHAIN_REMARKS_SEPARATOR)
            proxyChainProfiles = wanted
            subscriptionId = groupId
        }
        return try {
            val guid = MmkvManager.encodeServerConfig("", chain)
            SettingsChangeManager.makeSetupGroupTab()
            LogUtil.i(AppConfig.TAG, "WARP: chain saved ($guid, order=$order)")
            guid
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "WARP: could not store the chain", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Pure parts
    // -------------------------------------------------------------------------

    /**
     * Member names of the chain in traffic order, entry first, or null when it cannot be built.
     * Chains refer to their members by name and split the list on commas, so a member needs a
     * non-empty name without a comma, and the two names have to differ.
     */
    internal fun chainMembers(other: ProfileItem, warp: ProfileItem, order: ChainOrder): List<String>? {
        if (other.configType.isComplexType()) return null
        val otherName = other.remarks
        val warpName = warp.remarks
        if (otherName.isBlank() || warpName.isBlank()) return null
        if (otherName.contains(',') || warpName.contains(',')) return null
        if (otherName == warpName) return null
        return when (order) {
            ChainOrder.ExitThroughWarp -> listOf(otherName, warpName)
            ChainOrder.EnterThroughWarp -> listOf(warpName, otherName)
        }
    }

    internal fun registrationBody(publicKey: String, now: Date): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        val body = JsonObject().apply {
            addProperty("install_id", "")
            addProperty("fcm_token", "")
            addProperty("tos", format.format(now))
            addProperty("key", publicKey)
            addProperty("type", "Android")
            addProperty("model", "PC")
            addProperty("locale", "en_US")
            addProperty("warp_enabled", true)
        }
        return body.toString()
    }

    /**
     * Reads a registration answer. The peer key is required; everything else falls back to the
     * values every WARP account shares, because an answer without them is still usable.
     */
    internal fun parseRegistration(json: String): Registration? {
        val root = try {
            JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        val config = root.obj("config") ?: return null
        val peer = config.get("peers")
            ?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        val peerKey = peer.string("public_key")?.takeIf { it.isNotBlank() } ?: return null

        // "162.159.192.5:0": the address is real, the port is a placeholder.
        val endpointHost = peer.obj("endpoint")?.string("v4")
            ?.substringBefore(':')
            ?.takeIf { CloudflareAssistant.isCloudflareIpv4(it) }
            ?: DEFAULT_ENDPOINT_HOST

        val addresses = config.obj("interface")?.obj("addresses")
        val v4 = addresses?.string("v4")?.takeIf { it.isNotBlank() }?.let { "$it/32" }
            ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        val v6 = addresses?.string("v6")?.takeIf { it.isNotBlank() }?.let { "$it/128" }
        val localAddress = listOfNotNull(v4, v6).joinToString(",")

        return Registration(
            peerPublicKey = peerKey,
            endpointHost = endpointHost,
            endpointPort = DEFAULT_ENDPOINT_PORT,
            localAddress = localAddress,
            reserved = reservedFromClientId(config.string("client_id")),
        )
    }

    /** The three "reserved" bytes WARP expects are the base64 decoded client id. */
    internal fun reservedFromClientId(clientId: String?): String {
        if (clientId.isNullOrBlank()) return NO_RESERVED
        // okio instead of the platform decoders: java.util.Base64 needs API 26, android.util.Base64
        // is not available to JVM tests.
        val bytes = clientId.trim().decodeBase64()?.toByteArray() ?: return NO_RESERVED
        if (bytes.size != 3) return NO_RESERVED
        return bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }
    }

    internal fun buildProfile(registration: Registration, privateKey: String, groupId: String): ProfileItem =
        ProfileItem.create(EConfigType.WIREGUARD).apply {
            remarks = PROFILE_NAME
            subscriptionId = groupId
            server = registration.endpointHost
            serverPort = registration.endpointPort.toString()
            secretKey = privateKey
            publicKey = registration.peerPublicKey
            localAddress = registration.localAddress
            reserved = registration.reserved
            mtu = WARP_MTU
            remoteDNS = AppConfig.WIREGUARD_LOCAL_REMOTE_DNS
        }

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    /** Name of the profile; chains refer to it by this name, so it is not translated. */
    const val PROFILE_NAME = "WARP"

    private const val CHAIN_REMARKS_SEPARATOR = " → "
    private const val REGISTER_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val TIMEOUT_MS = 15_000
    private const val DEFAULT_ENDPOINT_HOST = "162.159.192.1"
    private const val DEFAULT_ENDPOINT_PORT = 2408
    private const val NO_RESERVED = "0,0,0"

    /** WARP's own MTU; the generic WireGuard default is too large for its encapsulation. */
    private const val WARP_MTU = 1280

    /** The API only answers requests that look like the official client. */
    private val REQUEST_HEADERS = mapOf(
        "User-Agent" to "okhttp/3.12.1",
        "CF-Client-Version" to "a-6.10-2158",
    )
}
