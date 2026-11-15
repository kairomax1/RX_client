package com.v2ray.ang.handler

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Firefox Accounts (FxA) client for the built-in Firefox VPN.
 *
 * Implements the same protocol Firefox itself uses:
 * - scrypt password derivation (N=32768, r=8, p=1)
 * - HAWK request signing for authenticated calls
 * - Session verification (email code, TOTP)
 * - OAuth token issuance with offline access
 *
 * The client ID and scope match Firefox desktop's built-in VPN so the
 * Guardian API issues a ProxyPass for the Fastly CONNECT proxies.
 */
object FirefoxVpnAuth {

    private const val FXA_BASE = "https://api.accounts.firefox.com/v1"
    private const val OAUTH_TOKEN_URL = "https://oauth.accounts.firefox.com/v1/token"
    private const val FIREFOX_CLIENT_ID = "5882386c6d801776"
    private const val OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
    private const val SERVICE = "sync"

    private val JSON = "application/json".toMediaType()
    private val FORM = "application/x-www-form-urlencoded".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Result types ──────────────────────────────────────────────

    sealed class LoginResult {
        data class Success(
            val uid: String,
            val sessionToken: String,
            val email: String,
            val verified: Boolean
        ) : LoginResult()

        data class NeedEmailCode(
            val sessionToken: String,
            val email: String
        ) : LoginResult()

        data class NeedTotp(
            val sessionToken: String,
            val email: String
        ) : LoginResult()

        data class Error(val message: String, val errno: Int = 0) : LoginResult()
    }

    data class OAuthTokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
        val scope: String
    )

    sealed class TokenResult {
        data class Success(val tokens: OAuthTokens) : TokenResult()
        data class Error(val message: String) : TokenResult()
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Starts a sign-in with email and password.
     * Returns Success when the session is verified, or a "need code" result.
     */
    suspend fun login(email: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                val authPw = deriveAuthPw(password, email)
                val body = JsonObject().apply {
                    addProperty("email", email)
                    addProperty("authPW", authPw)
                    addProperty("service", SERVICE)
                    addProperty("rememberMe", true)
                }
                val resp = post("$FXA_BASE/account/login", body.toString())
                val json: JsonObject = JsonParser.parseString(resp.body).asJsonObject

                if (json.has("sessionToken")) {
                    val sessionToken: String = json.get("sessionToken").asString
                    val uid: String = json.get("uid").asString
                    val verified: Boolean = json.get("verified").asBoolean
                    val accountEmail: String = if (json.has("email")) json.get("email").asString else email
                    if (verified) {
                        LoginResult.Success(uid, sessionToken, accountEmail, true)
                    } else {
                        LoginResult.NeedEmailCode(sessionToken, accountEmail)
                    }
                } else if (json.has("code") && json.get("code").asString == "email_code") {
                    val sessionToken: String = json.get("sessionToken").asString
                    LoginResult.NeedEmailCode(sessionToken, email)
                } else if (json.has("code") && json.get("code").asString == "totp") {
                    val sessionToken: String = json.get("sessionToken").asString
                    LoginResult.NeedTotp(sessionToken, email)
                } else {
                    val msg: String = if (json.has("message")) json.get("message").asString else "Unknown error"
                    val errno: Int = if (json.has("errno")) json.get("errno").asInt else 0
                    LoginResult.Error(msg, errno)
                }
            } catch (e: Exception) {
                LoginResult.Error(e.message ?: "Network error")
            }
        }

    /** Verifies a session with a 6-digit email code. */
    suspend fun verifyEmailCode(sessionToken: String, code: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("code", code)
                    addProperty("sessionToken", sessionToken)
                }
                val resp = post("$FXA_BASE/session/verify_code", body.toString())
                val json = JsonParser.parseString(resp.body).asJsonObject
                json.get("verified")?.asBoolean == true
            } catch (_: Exception) {
                false
            }
        }

    /** Verifies a session with a TOTP code. */
    suspend fun verifyTotp(sessionToken: String, code: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("code", code)
                    addProperty("sessionToken", sessionToken)
                }
                val resp = post("$FXA_BASE/session/verify/totp", body.toString())
                val json = JsonParser.parseString(resp.body).asJsonObject
                json.get("verified")?.asBoolean == true
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Exchanges a verified session token for OAuth tokens.
     * Uses the authorization code flow internally.
     */
    suspend fun exchangeForOAuth(sessionToken: String): TokenResult =
        withContext(Dispatchers.IO) {
            try {
                // Step 1: get authorization code using session token (HAWK)
                val codeBody = JsonObject().apply {
                    addProperty("client_id", FIREFOX_CLIENT_ID)
                    addProperty("scope", OAUTH_SCOPE)
                    addProperty("access_type", "offline")
                }
                val codeResp = postHawk(
                    "$FXA_BASE/oauth/authorization",
                    codeBody.toString(),
                    sessionToken
                )
                val codeJson = JsonParser.parseString(codeResp.body).asJsonObject
                val authCode = codeJson.get("code")?.asString
                    ?: return@withContext TokenResult.Error("No authorization code received")

                // Step 2: exchange code for tokens
                val tokenBody = "grant_type=authorization_code" +
                    "&code=$authCode" +
                    "&client_id=$FIREFOX_CLIENT_ID" +
                    "&redirect_uri=https://accounts.firefox.com/signin" +
                    "&scope=${java.net.URLEncoder.encode(OAUTH_SCOPE, "UTF-8")}"
                val tokenResp = postForm(OAUTH_TOKEN_URL, tokenBody)
                val tokenJson = JsonParser.parseString(tokenResp.body).asJsonObject

                val accessToken = tokenJson.get("access_token")?.asString
                    ?: return@withContext TokenResult.Error("No access token received")
                val refreshToken = tokenJson.get("refresh_token")?.asString ?: ""
                val expiresIn = tokenJson.get("expires_in")?.asLong ?: 3600
                val scope = tokenJson.get("scope")?.asString ?: OAUTH_SCOPE

                TokenResult.Success(
                    OAuthTokens(accessToken, refreshToken, expiresIn, scope)
                )
            } catch (e: Exception) {
                TokenResult.Error(e.message ?: "OAuth exchange failed")
            }
        }

    /** Refreshes an OAuth access token using a refresh token. */
    suspend fun refreshOAuth(refreshToken: String): TokenResult =
        withContext(Dispatchers.IO) {
            try {
                val body = "grant_type=refresh_token" +
                    "&refresh_token=$refreshToken" +
                    "&client_id=$FIREFOX_CLIENT_ID"
                val resp = postForm(OAUTH_TOKEN_URL, body)
                val json = JsonParser.parseString(resp.body).asJsonObject

                val accessToken = json.get("access_token")?.asString
                    ?: return@withContext TokenResult.Error("No access token in refresh response")
                val newRefresh = json.get("refresh_token")?.asString ?: refreshToken
                val expiresIn = json.get("expires_in")?.asLong ?: 3600
                val scope = json.get("scope")?.asString ?: OAUTH_SCOPE

                TokenResult.Success(
                    OAuthTokens(accessToken, newRefresh, expiresIn, scope)
                )
            } catch (e: Exception) {
                TokenResult.Error(e.message ?: "Token refresh failed")
            }
        }

    /** Registers a device record so the account shows this app in connected services. */
    suspend fun registerDevice(refreshToken: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("name", "Firefox's built-in VPN")
                    addProperty("type", "desktop")
                }
                // Device registration uses the refresh token as HAWK credential
                postHawk("$FXA_BASE/account/device", body.toString(), refreshToken)
            } catch (_: Exception) {
                // Non-critical; ignore failures
            }
        }
    }

    // ── Password derivation (scrypt) ──────────────────────────────

    /**
     * Derives the authPW for FxA login.
     * FxA uses: scrypt(password, salt, N=32768, r=8, p=1, dkLen=32)
     * where salt = hex(SHA256(email.lowercase()))
     */
    private fun deriveAuthPw(password: String, email: String): String {
        val emailHash = MessageDigest.getInstance("SHA-256")
            .digest(email.lowercase().toByteArray(StandardCharsets.UTF_8))
        val salt = toHex(emailHash)
        val derived = scrypt(
            password.toByteArray(StandardCharsets.UTF_8),
            salt.toByteArray(StandardCharsets.UTF_8),
            N = 32768, r = 8, p = 1, dkLen = 32
        )
        return toHex(derived)
    }

    // ── HAWK signing ──────────────────────────────────────────────

    /**
     * Makes a HAWK-signed POST request.
     * The session/refresh token is used as the HAWK credential.
     */
    private fun postHawk(url: String, jsonBody: String, token: String): HttpResponse {
        val tokenBytes = Base64.decode(token, Base64.DEFAULT)
        val hawkId = Base64.encodeToString(
            tokenBytes.copyOfRange(0, minOf(32, tokenBytes.size)),
            Base64.NO_WRAP
        )
        val hawkKey = Base64.encodeToString(
            tokenBytes.copyOfRange(minOf(32, tokenBytes.size), tokenBytes.size),
            Base64.NO_WRAP
        )

        val timestamp = System.currentTimeMillis() / 1000
        val nonce = randomNonce()
        val method = "POST"
        val resource = url.substringAfter("api.accounts.firefox.com").ifEmpty { "/" }
        val host = "api.accounts.firefox.com"
        val port = 443

        val normalized = buildString {
            append("hawk.1.header\n")
            append("$timestamp\n")
            append("$nonce\n")
            append("$method\n")
            append("$resource\n")
            append("$host\n")
            append("$port\n")
            append("\n") // hash (empty for POST with body already hashed separately)
            append("\n") // ext
            append("\n")
        }

        val mac = hmacSha256(hawkKey.toByteArray(StandardCharsets.UTF_8), normalized)
        val macB64 = Base64.encodeToString(mac, Base64.NO_WRAP)

        val hawkHeader = "Hawk id=\"$hawkId\", ts=\"$timestamp\", nonce=\"$nonce\", " +
            "mac=\"$macB64\", hash=\"\""

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON))
            .header("Authorization", hawkHeader)
            .header("Content-Type", "application/json")
            .header("User-Agent", FIREFOX_UA)
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $body")
        }
        return HttpResponse(response.code, body)
    }

    // ── HTTP helpers ──────────────────────────────────────────────

    private data class HttpResponse(val code: Int, val body: String)

    private fun post(url: String, jsonBody: String): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", FIREFOX_UA)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $body")
        }
        return HttpResponse(response.code, body)
    }

    private fun postForm(url: String, formBody: String): HttpResponse {
        val request = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody(FORM))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", FIREFOX_UA)
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $body")
        }
        return HttpResponse(response.code, body)
    }

    // ── Crypto helpers ────────────────────────────────────────────

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:149.0) Gecko/20100101 Firefox/149.0"

    // ── Scrypt (pure Kotlin, RFC 7914) ───────────────────────────

    /**
     * Scrypt key derivation function.
     * @param password the password
     * @param salt the salt
     * @param N CPU/memory cost parameter (must be power of 2)
     * @param r block size
     * @param p parallelization parameter
     * @param dkLen desired key length
     */
    internal fun scrypt(
        password: ByteArray,
        salt: ByteArray,
        N: Int,
        r: Int,
        p: Int,
        dkLen: Int
    ): ByteArray {
        val blockSize = 128 * r
        val b = pbkdf2Sha256(password, salt, 1, p * blockSize)
        val xy = ByteArray(blockSize)
        val v = ByteArray(blockSize * N)

        for (i in 0 until p) {
            roMix(b, i * blockSize, xy, v, N, r)
        }

        return pbkdf2Sha256(password, b, 1, dkLen)
    }

    private fun roMix(b: ByteArray, offset: Int, xy: ByteArray, v: ByteArray, N: Int, r: Int) {
        val blockSize = 128 * r
        System.arraycopy(b, offset, xy, 0, blockSize)

        for (i in 0 until N) {
            System.arraycopy(xy, 0, v, i * blockSize, blockSize)
            blockMix(xy, r)
        }

        for (i in 0 until N) {
            val j = integerify(xy, r) and (N - 1)
            for (k in 0 until blockSize) {
                xy[k] = (xy[k].toInt() xor v[j * blockSize + k].toInt()).toByte()
            }
            blockMix(xy, r)
        }

        System.arraycopy(xy, 0, b, offset, blockSize)
    }

    private fun blockMix(b: ByteArray, r: Int) {
        val blockSize = 128 * r
        val x = ByteArray(64)
        System.arraycopy(b, (2 * r - 1) * 64, x, 0, 64)

        for (i in 0 until 2 * r) {
            for (j in 0 until 64) {
                x[j] = (x[j].toInt() xor b[i * 64 + j].toInt()).toByte()
            }
            salsa208(x)
            System.arraycopy(x, 0, b, i * 64, 64)
        }

        // Reorder: even blocks first, then odd blocks
        val tmp = ByteArray(blockSize)
        for (i in 0 until r) {
            System.arraycopy(b, (2 * i) * 64, tmp, i * 64, 64)
        }
        for (i in 0 until r) {
            System.arraycopy(b, (2 * i + 1) * 64, tmp, (r + i) * 64, 64)
        }
        System.arraycopy(tmp, 0, b, 0, blockSize)
    }

    private fun salsa208(b: ByteArray) {
        val x = IntArray(16)
        for (i in 0 until 16) {
            x[i] = (b[i * 4].toInt() and 0xFF) or
                ((b[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((b[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((b[i * 4 + 3].toInt() and 0xFF) shl 24)
        }

        val orig = x.copyOf()
        repeat(4) {
            // Column rounds
            x[4] = x[4] xor rotl(x[0] + x[12], 7)
            x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13)
            x[0] = x[0] xor rotl(x[12] + x[8], 18)

            x[9] = x[9] xor rotl(x[5] + x[1], 7)
            x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13)
            x[5] = x[5] xor rotl(x[1] + x[13], 18)

            x[14] = x[14] xor rotl(x[10] + x[6], 7)
            x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13)
            x[10] = x[10] xor rotl(x[6] + x[2], 18)

            x[3] = x[3] xor rotl(x[15] + x[11], 7)
            x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13)
            x[15] = x[15] xor rotl(x[11] + x[7], 18)

            // Row rounds
            x[1] = x[1] xor rotl(x[0] + x[3], 7)
            x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13)
            x[0] = x[0] xor rotl(x[3] + x[2], 18)

            x[6] = x[6] xor rotl(x[5] + x[4], 7)
            x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13)
            x[5] = x[5] xor rotl(x[4] + x[7], 18)

            x[11] = x[11] xor rotl(x[10] + x[9], 7)
            x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13)
            x[10] = x[10] xor rotl(x[9] + x[8], 18)

            x[12] = x[12] xor rotl(x[15] + x[14], 7)
            x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13)
            x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }

        for (i in 0 until 16) {
            val v = orig[i] + x[i]
            b[i * 4] = (v and 0xFF).toByte()
            b[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
            b[i * 4 + 2] = ((v shr 16) and 0xFF).toByte()
            b[i * 4 + 3] = ((v shr 24) and 0xFF).toByte()
        }
    }

    private fun integerify(b: ByteArray, r: Int): Int {
        val offset = (2 * r - 1) * 64
        return (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun pbkdf2Sha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec refuses an empty key. HMAC zero-pads its key to the 64-byte block,
        // so an empty key and a single zero byte give exactly the same result.
        val keyBytes = if (password.isEmpty()) ByteArray(1) else password
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val hLen = 32
        val numBlocks = (dkLen + hLen - 1) / hLen
        val result = ByteArray(dkLen)
        var offset = 0

        for (block in 1..numBlocks) {
            mac.reset()
            mac.update(salt)
            mac.update(byteArrayOf(
                ((block shr 24) and 0xFF).toByte(),
                ((block shr 16) and 0xFF).toByte(),
                ((block shr 8) and 0xFF).toByte(),
                (block and 0xFF).toByte()
            ))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (j in t.indices) {
                    t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                }
            }
            val copyLen = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
        }
        return result
    }
}
