package com.v2ray.ang.handler

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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
 * Follows the protocol Firefox itself uses:
 * - the password is stretched with PBKDF2 over a salted email, then HKDF, into `authPW`
 * - authenticated calls are signed with HAWK using an HKDF-derived key
 * - an OAuth code grant (PKCE) with offline access yields the tokens the
 *   Guardian API needs for a ProxyPass
 *
 * The client id and scope are Firefox desktop's, so Guardian treats the
 * session as the browser's built-in VPN.
 */
object FirefoxVpnAuth {

    private const val FXA_BASE = "https://api.accounts.firefox.com/v1"
    private const val FXA_HOST = "api.accounts.firefox.com"
    private const val OAUTH_TOKEN_URL = "https://oauth.accounts.firefox.com/v1/token"
    private const val FIREFOX_CLIENT_ID = "5882386c6d801776"
    private const val OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
    private const val PICL = "identity.mozilla.com/picl/v1/"

    private const val FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"

    /** FxA asks the account's email address, and the sign-in code, to be confirmed. */
    private const val VERIFICATION_EMAIL_2FA = "email-2fa"

    private val JSON = "application/json".toMediaType()

    private val random = SecureRandom()

    /** The edge keeps a cookie after its bot check; the same jar must carry it. */
    private val cookieJar = object : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this.cookies.add(cookie)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            cookies.removeAll { it.expiresAt < now }
            return cookies.filter { it.matches(url) }
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            // Sending gzip as the only encoding is what the edge answered with
            // HTTP 406 for in the reference implementation's A/B tests, and a
            // desktop Firefox never sends it as the sole encoding either.
            val request = chain.request().newBuilder()
                .header("User-Agent", FIREFOX_UA)
                .header("Accept-Encoding", "identity")
                .build()
            chain.proceed(request)
        }
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

        /** FxA mailed a confirmation code; [sessionToken] is not usable until it is entered. */
        data class NeedEmailCode(
            val sessionToken: String,
            val email: String
        ) : LoginResult()

        /** The account has an authenticator app; its code confirms this sign-in. */
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

    private data class HttpResponse(val code: Int, val body: String)

    private class FxaException(val message2: String, val code: Int = 0, val errno: Int = 0) :
        Exception(message2)

    // ── Sign-in ───────────────────────────────────────────────────

    /**
     * Signs in with email and password.
     *
     * FxA answers a verified session with the tokens' precursor; an unverified
     * one has already been confirmed by a code it sent, so the caller is asked
     * for it.
     */
    suspend fun login(email: String, password: String): LoginResult =
        withContext(Dispatchers.IO) {
            try {
                val trimmed = email.trim()
                var response = loginAttempt(trimmed, password, VERIFICATION_EMAIL_2FA)
                // A deployment that does not know the verification method asks
                // for a plain sign-in instead; errno 107 is "invalid parameter".
                if (response.code == 400 && errnoOf(response.body) == 107) {
                    response = loginAttempt(trimmed, password, null)
                }
                if (response.code != 200) {
                    return@withContext LoginResult.Error(messageFor(response), errnoOf(response.body))
                }

                val json = JsonParser.parseString(response.body).asJsonObject
                val sessionToken = json.string("sessionToken")
                    ?: return@withContext LoginResult.Error("Firefox answered without a session token")
                val uid = json.string("uid").orEmpty()

                if (sessionVerified(sessionToken)) {
                    LoginResult.Success(uid, sessionToken, trimmed, true)
                } else if (json.string("verificationMethod") == "totp-2fa") {
                    LoginResult.NeedTotp(sessionToken, trimmed)
                } else {
                    LoginResult.NeedEmailCode(sessionToken, trimmed)
                }
            } catch (e: FxaException) {
                LoginResult.Error(e.message2, e.errno)
            } catch (e: Exception) {
                LoginResult.Error("No answer from Firefox: ${e.message ?: "network error"}")
            }
        }

    private fun loginAttempt(email: String, password: String, verificationMethod: String?): HttpResponse {
        val authPw = deriveAuthPw(email, password)
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("authPW", toHex(authPw))
            if (verificationMethod != null) addProperty("verificationMethod", verificationMethod)
        }
        return postJson("$FXA_BASE/account/login", body.toString(), null)
    }

    /** Whether the session (not just the address) is confirmed. */
    suspend fun sessionVerified(sessionToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = get("$FXA_BASE/recovery_email/status", hawkAuthorization("GET", "$FXA_BASE/recovery_email/status", sessionToken, ""))
            if (response.code != 200) return@withContext false
            JsonParser.parseString(response.body).asJsonObject.boolean("verified") ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Confirms an unverified session with the code FxA mailed at sign-in. */
    suspend fun verifyEmailCode(sessionToken: String, code: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = "$FXA_BASE/session/verify_code"
                val body = JsonObject().apply { addProperty("code", code.trim()) }.toString()
                val response = postJson(url, body, hawkAuthorization("POST", url, sessionToken, body))
                response.code == 200
            } catch (_: Exception) {
                false
            }
        }

    /** Confirms an unverified session with a code from the account's authenticator app. */
    suspend fun verifyTotp(sessionToken: String, code: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = "$FXA_BASE/session/verify/totp"
                val body = JsonObject().apply { addProperty("code", code.trim()) }.toString()
                val response = postJson(url, body, hawkAuthorization("POST", url, sessionToken, body))
                response.code == 200 &&
                    (JsonParser.parseString(response.body).asJsonObject.boolean("success") ?: false)
            } catch (_: Exception) {
                false
            }
        }

    // ── OAuth ─────────────────────────────────────────────────────

    /**
     * Turns a confirmed session into OAuth tokens.
     *
     * The code grant is the one Firefox uses for the built-in VPN: an
     * authorization code issued to a session, redeemed with the PKCE verifier.
     */
    suspend fun exchangeForOAuth(sessionToken: String): TokenResult =
        withContext(Dispatchers.IO) {
            try {
                val verifier = base64Url(randomBytes(32))
                val challenge = base64Url(sha256(verifier.toByteArray(StandardCharsets.US_ASCII)))
                val state = toHex(randomBytes(16))

                val url = "$FXA_BASE/oauth/authorization"
                val body = JsonObject().apply {
                    addProperty("client_id", FIREFOX_CLIENT_ID)
                    addProperty("response_type", "code")
                    addProperty("scope", OAUTH_SCOPE)
                    addProperty("state", state)
                    addProperty("access_type", "offline")
                    addProperty("code_challenge", challenge)
                    addProperty("code_challenge_method", "S256")
                }.toString()

                val response = postJson(url, body, hawkAuthorization("POST", url, sessionToken, body), OAUTH_ACCEPT)
                if (response.code != 200) return@withContext TokenResult.Error(messageFor(response))

                val json = JsonParser.parseString(response.body).asJsonObject
                val code = json.string("code")
                    ?: return@withContext TokenResult.Error("Firefox answered without an authorization code")
                if (json.string("state") != state) {
                    return@withContext TokenResult.Error("Firefox answered for another request")
                }

                val tokenBody = JsonObject().apply {
                    addProperty("client_id", FIREFOX_CLIENT_ID)
                    addProperty("grant_type", "authorization_code")
                    addProperty("code", code)
                    addProperty("code_verifier", verifier)
                }.toString()
                return@withContext tokenResultOf(postJson(OAUTH_TOKEN_URL, tokenBody, null, OAUTH_ACCEPT, null))
            } catch (e: FxaException) {
                TokenResult.Error(e.message2)
            } catch (e: Exception) {
                TokenResult.Error("No answer from Firefox: ${e.message ?: "network error"}")
            }
        }

    /** Renews an access token without asking for the password again. */
    suspend fun refreshOAuth(refreshToken: String): TokenResult =
        withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("client_id", FIREFOX_CLIENT_ID)
                    addProperty("grant_type", "refresh_token")
                    addProperty("refresh_token", refreshToken)
                }.toString()
                tokenResultOf(postJson(OAUTH_TOKEN_URL, body, null, OAUTH_ACCEPT, null))
            } catch (e: FxaException) {
                TokenResult.Error(e.message2)
            } catch (e: Exception) {
                TokenResult.Error("No answer from Firefox: ${e.message ?: "network error"}")
            }
        }

    private fun tokenResultOf(response: HttpResponse): TokenResult {
        if (response.code != 200) return TokenResult.Error(messageFor(response))
        val json = JsonParser.parseString(response.body).asJsonObject
        val accessToken = json.string("access_token")
            ?: return TokenResult.Error("Firefox answered without an access token")
        val refresh = json.string("refresh_token").orEmpty()
        val expiresIn = json.long("expires_in") ?: 3600L
        val scope = json.string("scope") ?: OAUTH_SCOPE
        return TokenResult.Success(OAuthTokens(accessToken, refresh, expiresIn, scope))
    }

    /**
     * Records this program in the account's connected services as Firefox's
     * built-in VPN, the way the browser shows it. Best effort: a failure here
     * does not stop the connection.
     */
    suspend fun registerDevice(refreshToken: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JsonObject().apply {
                    addProperty("name", "Firefox's built-in VPN")
                    addProperty("type", "desktop")
                }.toString()
                postJson("$FXA_BASE/account/device", body, "Bearer $refreshToken")
            } catch (_: Exception) {
                // The device list is cosmetic; the tokens stay valid.
            }
        }
    }

    /** Ends a sign-in session once its tokens exist, so it does not linger in the account. */
    suspend fun destroySession(sessionToken: String) {
        withContext(Dispatchers.IO) {
            try {
                request("DELETE", "$FXA_BASE/session/destroy", null,
                    hawkAuthorization("DELETE", "$FXA_BASE/session/destroy", sessionToken, ""))
            } catch (_: Exception) {
                // Leftover sessions expire on their own.
            }
        }
    }

    // ── Key derivation ────────────────────────────────────────────

    /**
     * FxA's password stretch: PBKDF2-SHA256 over the salted address, then
     * HKDF into the 32-byte value the sign-in request carries.
     */
    internal fun deriveAuthPw(email: String, password: String): ByteArray {
        val salt = (PICL + "quickStretch:" + email).toByteArray(StandardCharsets.UTF_8)
        val stretched = pbkdf2Sha256(password.toByteArray(StandardCharsets.UTF_8), salt, 1000, 32)
        return hkdfSha256(stretched, ByteArray(1), (PICL + "authPW").toByteArray(StandardCharsets.UTF_8), 32)
    }

    /** The HAWK id and key a session token stands for. */
    internal fun deriveHawk(sessionToken: String): Pair<String, ByteArray> {
        val tokenBytes = fromHex(sessionToken)
        val out = hkdfSha256(tokenBytes, null, (PICL + "sessionToken").toByteArray(StandardCharsets.UTF_8), 96)
        return toHex(out.copyOfRange(0, 32)) to out.copyOfRange(32, 64)
    }

    private fun hawkAuthorization(
        method: String,
        url: String,
        sessionToken: String,
        payload: String,
        contentType: String = "application/json; charset=utf-8"
    ): String {
        val (id, key) = deriveHawk(sessionToken)
        val nonce = toHex(randomBytes(6))
        val timestamp = System.currentTimeMillis() / 1000
        val payloadHash = hawkPayloadHash(payload, contentType)
        val normalized = hawkNormalized(method, url, timestamp, nonce, payloadHash)
        val mac = hmacSha256(key, normalized.toByteArray(StandardCharsets.UTF_8))
        return "Hawk id=\"$id\", ts=\"$timestamp\", nonce=\"$nonce\", " +
            "mac=\"${toHex(mac)}\"" + if (payloadHash.isNotEmpty()) ", hash=\"$payloadHash\"" else ""
    }

    /** Hawk hashes the payload together with its content type; an empty payload leaves it empty. */
    internal fun hawkPayloadHash(payload: String, contentType: String): String =
        if (payload.isEmpty()) "" else toHex(
            sha256(("hawk.1.payload\n$contentType\n$payload\n").toByteArray(StandardCharsets.UTF_8))
        )

    /** The string a Hawk mac is computed over. */
    internal fun hawkNormalized(
        method: String,
        url: String,
        timestamp: Long,
        nonce: String,
        payloadHash: String
    ): String = listOf(
        "hawk.1.header",
        timestamp.toString(),
        nonce,
        method.uppercase(),
        url.substringAfter(FXA_HOST).ifEmpty { "/" },
        FXA_HOST,
        "443",
        payloadHash,
        "",
        ""
    ).joinToString("\n")

    /** HKDF-SHA256, as FxA's derivation uses it. A null salt means zeros. */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt ?: ByteArray(32), ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    internal fun toHex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return out.toString()
    }

    private fun fromHex(text: String): ByteArray {
        val clean = text.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    // ── HTTP ──────────────────────────────────────────────────────

    private fun postJson(
        url: String,
        body: String,
        authorization: String?,
        accept: String = "application/json",
        contentType: String = "application/json; charset=utf-8"
    ): HttpResponse = request("POST", url, body, authorization, accept, contentType)

    private fun get(url: String, authorization: String?): HttpResponse =
        request("GET", url, null, authorization)

    private fun request(
        method: String,
        url: String,
        body: String?,
        authorization: String?,
        accept: String = "application/json",
        contentType: String = "application/json; charset=utf-8"
    ): HttpResponse {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("Accept-Language", "en-US, en")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
        if (authorization != null) builder.header("Authorization", authorization)

        when (method) {
            "POST" -> builder.post((body ?: "").toRequestBody(JSON))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        if (body != null) builder.header("Content-Type", contentType)

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 406) {
                throw FxaException(
                    "Mozilla's security check (Fastly) refused this request. " +
                        "Try again later or on another network."
                )
            }
            if (response.code >= 500) {
                throw FxaException("Firefox's servers answered ${response.code}. Try again later.")
            }
            return HttpResponse(response.code, text)
        }
    }

    // ── Messages ──────────────────────────────────────────────────

    private fun errnoOf(body: String): Int = try {
        val json = JsonParser.parseString(body).asJsonObject
        json.get("errno")?.takeIf { !it.isJsonNull }?.asInt ?: 0
    } catch (_: Exception) {
        0
    }

    private fun messageFor(response: HttpResponse): String {
        val errno = errnoOf(response.body)
        val known = when (errno) {
            102 -> "There is no Firefox account with this email."
            125 -> "Mozilla blocked this sign-in attempt. Wait a while and try again."
            120 -> "The email's letter case differs from the account's. Write it exactly as registered."
            170 -> "The password is wrong."
            195 -> "Too many attempts. Wait a few minutes."
            106 -> "The email is not correct."
            else -> null
        }
        if (known != null) return known
        val serverMessage = try {
            JsonParser.parseString(response.body).asJsonObject.string("message")
        } catch (_: Exception) {
            null
        }
        return serverMessage?.let { "Firefox: $it (${response.code})" }
            ?: "Firefox refused the request (HTTP ${response.code})"
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.boolean(name: String): Boolean? =
        get(name)?.takeIf { !it.isJsonNull }?.asBoolean

    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeIf { !it.isJsonNull }?.asLong

    private const val OAUTH_ACCEPT = "application/json;q=0.9,*/*;q=0.2"

    // ── PBKDF2-SHA256 / scrypt ────────────────────────────────────

    /**
     * PBKDF2-HMAC-SHA256. Written out because the platform's
     * PBKDF2WithHmacSHA256 only exists from Android 8.
     */
    internal fun pbkdf2Sha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        dkLen: Int
    ): ByteArray {
        // SecretKeySpec refuses an empty key. HMAC zero-pads its key to the
        // 64-byte block, so an empty key and one zero byte are the same key.
        val keyBytes = if (password.isEmpty()) ByteArray(1) else password
        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val result = ByteArray(dkLen)
        var offset = 0
        for (block in 1..blocks) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    ((block shr 24) and 0xFF).toByte(),
                    ((block shr 16) and 0xFF).toByte(),
                    ((block shr 8) and 0xFF).toByte(),
                    (block and 0xFF).toByte()
                )
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                val inner = Mac.getInstance("HmacSHA256")
                inner.init(SecretKeySpec(keyBytes, "HmacSHA256"))
                u = inner.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val copyLen = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
        }
        return result
    }

    /**
     * scrypt key derivation (RFC 7914). Kept for the protocol's own tests; FxA
     * itself uses PBKDF2 + HKDF above.
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

        // Even blocks first, then the odd ones.
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
}
