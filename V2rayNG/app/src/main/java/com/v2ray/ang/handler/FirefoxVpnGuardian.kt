package com.v2ray.ang.handler

import android.util.Base64
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Mozilla Guardian API client.
 *
 * Guardian issues the ProxyPass (JWT) that authenticates CONNECT requests
 * to the Fastly proxies. The JWT's "aud" claim contains the proxy URI.
 * Quota information comes back in response headers.
 */
object FirefoxVpnGuardian {

    private const val GUARDIAN_BASE = "https://vpn.mozilla.org"
    private const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ProxyPass(
        val token: String,
        val proxyHost: String,
        val proxyPort: Int,
        val expiresAt: Long,
        val quotaLimit: Long,
        val quotaRemaining: Long,
        val quotaReset: Long
    )

    sealed class PassResult {
        data class Success(val pass: ProxyPass) : PassResult()
        data class Error(val message: String, val statusCode: Int = 0) : PassResult()
    }

    /**
     * Fetches a ProxyPass from Guardian.
     * GET /api/v1/fpn/token with Bearer OAuth token.
     */
    suspend fun fetchProxyPass(accessToken: String): PassResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$GUARDIAN_BASE/api/v1/fpn/token")
                    .get()
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .header("Cache-Control", "max-age=0")
                    .header("User-Agent", FIREFOX_UA)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val msg = when (response.code) {
                        401 -> "Token invalid or expired"
                        403 -> "Account not entitled to VPN"
                        429 -> "Quota exceeded"
                        451 -> "Region unavailable"
                        else -> "Guardian error: HTTP ${response.code}"
                    }
                    return@withContext PassResult.Error(msg, response.code)
                }

                val json = JsonParser.parseString(body).asJsonObject()
                val token = json.get("token")?.asString
                    ?: return@withContext PassResult.Error("Empty token in Guardian response")

                // Parse JWT claims (no signature verification needed — we just read aud/exp)
                val claims = parseJwtClaims(token)
                val aud = claims["aud"]?.toString() ?: ""
                val exp = (claims["exp"] as? Number)?.toLong() ?: 0

                // aud is the proxy URI, e.g. "https://proxy.example.com:443"
                val proxyUri = java.net.URI(aud)
                val proxyHost = proxyUri.host ?: ""
                val proxyPort = if (proxyUri.port > 0) proxyUri.port else 443

                if (proxyHost.isEmpty()) {
                    return@withContext PassResult.Error("Invalid proxy address in token")
                }

                val quotaLimit = response.header("X-Quota-Limit")?.toLongOrNull() ?: 0
                val quotaRemaining = response.header("X-Quota-Remaining")?.toLongOrNull() ?: 0
                val quotaReset = response.header("X-Quota-Reset")?.toLongOrNull() ?: 0

                PassResult.Success(
                    ProxyPass(
                        token = token,
                        proxyHost = proxyHost,
                        proxyPort = proxyPort,
                        expiresAt = exp * 1000,
                        quotaLimit = quotaLimit,
                        quotaRemaining = quotaRemaining,
                        quotaReset = quotaReset
                    )
                )
            } catch (e: Exception) {
                PassResult.Error(e.message ?: "Network error")
            }
        }

    /** Checks quota without minting a new pass (HEAD request). */
    suspend fun checkQuota(accessToken: String): Pair<Long, Long>? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$GUARDIAN_BASE/api/v1/fpn/token")
                    .head()
                    .header("Authorization", "Bearer $accessToken")
                    .header("Cache-Control", "max-age=0")
                    .header("User-Agent", FIREFOX_UA)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val limit = response.header("X-Quota-Limit")?.toLongOrNull() ?: 0
                val remaining = response.header("X-Quota-Remaining")?.toLongOrNull() ?: 0
                limit to remaining
            } catch (_: Exception) {
                null
            }
        }

    /** Activates the VPN entitlement for an account. */
    suspend fun activate(accessToken: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$GUARDIAN_BASE/api/v1/fpn/activate")
                    .post("".toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $accessToken")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", FIREFOX_UA)
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (_: Exception) {
                false
            }
        }

    // ── JWT parsing ───────────────────────────────────────────────

    private fun parseJwtClaims(token: String): Map<String, Any?> {
        val parts = token.split(".")
        if (parts.size < 2) return emptyMap()
        val payload = Base64.decode(parts[1], Base64.URL_SAFE)
        val json = JsonParser.parseString(String(payload, StandardCharsets.UTF_8)).asJsonObject()
        val map = mutableMapOf<String, Any?>()
        for ((key, value) in json.entrySet()) {
            map[key] = when {
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber ->
                    value.asJsonPrimitive.asLong
                value.isJsonPrimitive -> value.asJsonPrimitive.asString
                value.isJsonArray -> value.asJsonArray.map { it.asString }
                else -> value.toString()
            }
        }
        return map
    }
}
