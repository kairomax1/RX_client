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
        val createdAt: Long
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
                        createdAt = obj.get("createdAt").asLong
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
            }
            json.add(obj)
        }
        val key = getOrCreateKey(context)
        val encrypted = encrypt(json.toString().toByteArray(StandardCharsets.UTF_8), key)
        File(storeDir(context), ACCOUNTS_FILE).writeBytes(encrypted)
    }

    // ── Selected country ──────────────────────────────────────────

    private const val PREFS_NAME = "firefox_vpn_prefs"

    fun saveSelectedCountry(context: Context, countryCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("selected_country", countryCode).apply()
    }

    fun loadSelectedCountry(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("selected_country", null)
    }

    fun saveActiveAccountId(context: Context, accountId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("active_account", accountId).apply()
    }

    fun loadActiveAccountId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("active_account", null)
    }
}
