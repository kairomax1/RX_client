package com.v2ray.ang.handler

import android.content.Context
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Secure storage for Firefox VPN account credentials.
 *
 * OAuth tokens are encrypted with AES-256-GCM. The encryption key is
 * derived from a random device key stored in a private file.
 * The Firefox Account password is NOT stored — only OAuth tokens.
 */
object FirefoxVpnStore {

    private const val STORE_DIR = "firefox-vpn"
    private const val KEY_FILE = "device.key"
    private const val ACCOUNTS_FILE = "accounts.json"

    data class StoredAccount(
        val id: String,
        val email: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val createdAt: Long,
        /** The OAuth client the refresh token belongs to; empty on older files. */
        val clientId: String = ""
    )

    private fun storeDir(context: Context): File {
        val dir = File(context.filesDir, STORE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getOrCreateKey(context: Context): ByteArray {
        val keyFile = File(storeDir(context), KEY_FILE)
        if (keyFile.exists()) {
            return keyFile.readBytes()
        }
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        keyFile.writeBytes(key)
        return key
    }

    private fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(data)
        return iv + encrypted
    }

    private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = data.copyOfRange(0, 12)
        val encrypted = data.copyOfRange(12, data.size)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    // ── Account CRUD ──────────────────────────────────────────────

    fun saveAccount(context: Context, account: StoredAccount) {
        val accounts = loadAccounts(context).toMutableList()
        accounts.removeAll { it.id == account.id }
        accounts.add(account)
        writeAccounts(context, accounts)
    }

    fun loadAccounts(context: Context): List<StoredAccount> {
        val file = File(storeDir(context), ACCOUNTS_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val key = getOrCreateKey(context)
            val decrypted = decrypt(file.readBytes(), key)
            val json = JsonParser.parseString(String(decrypted, StandardCharsets.UTF_8)).asJsonArray
            val result = mutableListOf<StoredAccount>()
            for (item in json) {
                val obj = item.asJsonObject
                result.add(
                    StoredAccount(
                        id = obj.get("id").asString,
                        email = obj.get("email").asString,
                        accessToken = obj.get("accessToken").asString,
                        refreshToken = obj.get("refreshToken").asString,
                        expiresAt = obj.get("expiresAt").asLong,
                        createdAt = obj.get("createdAt").asLong,
                        clientId = obj.get("clientId")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun removeAccount(context: Context, id: String) {
        val accounts = loadAccounts(context).filter { it.id != id }
        writeAccounts(context, accounts)
    }

    fun updateTokens(
        context: Context,
        id: String,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long
    ) {
        val accounts = loadAccounts(context).map { account ->
            if (account.id == id) {
                account.copy(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = System.currentTimeMillis() + expiresIn * 1000
                )
            } else {
                account
            }
        }
        writeAccounts(context, accounts)
    }

    private fun writeAccounts(context: Context, accounts: List<StoredAccount>) {
        val json = com.google.gson.JsonArray()
        for (account in accounts) {
            val obj = JsonObject().apply {
                addProperty("id", account.id)
                addProperty("email", account.email)
                addProperty("accessToken", account.accessToken)
                addProperty("refreshToken", account.refreshToken)
                addProperty("expiresAt", account.expiresAt)
                addProperty("createdAt", account.createdAt)
                addProperty("clientId", account.clientId)
            }
            json.add(obj)
        }
        val key = getOrCreateKey(context)
        val encrypted = encrypt(json.toString().toByteArray(StandardCharsets.UTF_8), key)
        File(storeDir(context), ACCOUNTS_FILE).writeBytes(encrypted)
    }

    // ── Selected country and live tunnel state ────────────────────
    //
    // The VPN core runs in :daemon while this screen normally runs in the app
    // process. SharedPreferences is cached per process and does not reliably
    // publish an update to its sibling process. MMKV's multi-process store is
    // used for the small pieces of state the recovery watcher must read and
    // write even after the screen has gone away. The old preferences are read
    // once as a migration path for existing installs.

    private const val PREFS_NAME = "firefox_vpn_prefs"
    private const val KEY_SELECTED_COUNTRY = "rx_firefox_selected_country"
    private const val KEY_ACTIVE_ACCOUNT = "rx_firefox_active_account"
    private const val KEY_TUNNEL_PORT = "rx_firefox_tunnel_port"
    private const val KEY_TUNNEL_COUNTRY = "rx_firefox_tunnel_country"
    private const val KEY_TUNNEL_NAME = "rx_firefox_tunnel_name"
    private const val UNSET_INT = Int.MIN_VALUE

    private fun legacyPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readString(context: Context, key: String, oldKey: String): String? {
        val current = MmkvManager.decodeSettingsString(key)
        if (current != null) return current.takeIf { it.isNotBlank() }
        val legacy = legacyPrefs(context).getString(oldKey, null)
        if (!legacy.isNullOrBlank()) MmkvManager.encodeSettings(key, legacy)
        return legacy?.takeIf { it.isNotBlank() }
    }

    fun saveSelectedCountry(context: Context, countryCode: String) {
        MmkvManager.encodeSettings(KEY_SELECTED_COUNTRY, countryCode)
    }

    fun loadSelectedCountry(context: Context): String? =
        readString(context, KEY_SELECTED_COUNTRY, "selected_country")

    fun saveActiveAccountId(context: Context, accountId: String?) {
        MmkvManager.encodeSettings(KEY_ACTIVE_ACCOUNT, accountId.orEmpty())
    }

    fun loadActiveAccountId(context: Context): String? =
        readString(context, KEY_ACTIVE_ACCOUNT, "active_account")

    /**
     * Records the loopback listener the Xray profile currently uses. This is
     * intentionally cross-process: the daemon may replace the relay while the
     * Firefox screen is closed, and the screen must adopt the new port rather
     * than continuing to probe the dead one.
     */
    fun saveTunnel(context: Context, localPort: Int, countryCode: String, countryName: String) {
        MmkvManager.encodeSettings(KEY_TUNNEL_PORT, localPort)
        MmkvManager.encodeSettings(KEY_TUNNEL_COUNTRY, countryCode)
        MmkvManager.encodeSettings(KEY_TUNNEL_NAME, countryName)
    }

    fun loadTunnelPort(context: Context): Int {
        val current = MmkvManager.decodeSettingsInt(KEY_TUNNEL_PORT, UNSET_INT)
        if (current != UNSET_INT) return current.coerceAtLeast(0)
        val legacy = legacyPrefs(context).getInt("tunnel_port", 0)
        if (legacy > 0) MmkvManager.encodeSettings(KEY_TUNNEL_PORT, legacy)
        return legacy
    }

    fun loadTunnelCountry(context: Context): String? =
        readString(context, KEY_TUNNEL_COUNTRY, "tunnel_country")

    fun loadTunnelName(context: Context): String? =
        readString(context, KEY_TUNNEL_NAME, "tunnel_name")

    fun clearTunnel(context: Context) {
        // Store explicit empty values so a process with an older SharedPreferences
        // cache cannot resurrect a tunnel that has already been disconnected.
        MmkvManager.encodeSettings(KEY_TUNNEL_PORT, 0)
        MmkvManager.encodeSettings(KEY_TUNNEL_COUNTRY, "")
        MmkvManager.encodeSettings(KEY_TUNNEL_NAME, "")
    }
}
