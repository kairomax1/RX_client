package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.net.URI
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Bounded probes of numeric IP targets from manual input, built-in sets, or an HTTPS list. */
object IpScanner {

    enum class Mode { TCP, UDP, NONE }

    enum class TargetSource { MANUAL, BUILT_IN, REMOTE }

    enum class RemoteListError { INVALID_URL, DOWNLOAD_FAILED, TOO_LARGE, INVALID_CONTENT }

    data class RemoteListResult(
        val targetsText: String? = null,
        val error: RemoteListError? = null,
    )

    data class BuiltInTargetSet(
        val id: String,
        val addresses: List<String>,
    )

    data class TargetInputResult(
        val addressText: String = "",
        val error: IpScanInput.ScanError? = null,
    )

    enum class Status { OPEN, RESPONSE, REACHABLE, CLOSED, NO_RESPONSE, ERROR }

    data class ProbeResult(
        val address: String,
        val mode: Mode,
        val port: Int?,
        val status: Status,
        val latencyMs: Long?,
    )

    data class AddressResult(
        val address: String,
        val probes: List<ProbeResult>,
    ) {
        val found: Boolean
            get() = probes.any { it.status in setOf(Status.OPEN, Status.RESPONSE, Status.REACHABLE, Status.CLOSED) }
    }

    data class ScanRequest(
        val addresses: List<String>,
        val mode: Mode,
        val ports: List<Int>,
    )

    data class ScanPreparation(
        val request: ScanRequest? = null,
        val error: IpScanInput.ScanError? = null,
    )

    /** Default choices; the user can toggle any of these and add more valid ports. */
    val PRESET_PORTS = listOf(443, 80, 2053, 53, 2083, 2087, 2096, 8443, 8880)
    val DEFAULT_PORTS = listOf(443, 80, 2053)

    /** Static, DNS-independent candidates. Availability depends on the user's current network. */
    val BUILT_IN_TARGETS = listOf(
        BuiltInTargetSet(
            id = "google-dns",
            addresses = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
        ),
        BuiltInTargetSet(
            id = "quad9-dns",
            addresses = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
        ),
        BuiltInTargetSet(
            id = "opendns",
            addresses = listOf("208.67.222.222", "208.67.220.220", "2620:119:35::35", "2620:119:53::53"),
        ),
        BuiltInTargetSet(
            id = "adguard-dns",
            addresses = listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
        ),
    )

    fun sanitizeCountText(value: String): String = value.filter(Char::isDigit).take(3)

    fun sanitizeCustomPortsText(value: String): String =
        value.filter { char -> char.isDigit() || char in ",; \n\t" }.take(256)

    fun togglePresetPort(selectedPorts: Collection<Int>, port: Int): List<Int> {
        if (port !in PRESET_PORTS) return selectedPorts.toList()
        val next = if (port in selectedPorts) selectedPorts - port else selectedPorts + port
        return PRESET_PORTS.filter { it in next }
    }

    fun toggleBuiltInTarget(selectedIds: Set<String>, id: String): Set<String> {
        if (BUILT_IN_TARGETS.none { it.id == id }) return selectedIds
        return if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun resolveTargetInput(
        source: TargetSource,
        manualAddressText: String,
        selectedBuiltInTargetIds: Set<String>,
        remoteTargetsText: String,
    ): TargetInputResult = when (source) {
        TargetSource.MANUAL -> TargetInputResult(addressText = manualAddressText)
        TargetSource.BUILT_IN -> {
            val selected = BUILT_IN_TARGETS
                .filter { it.id in selectedBuiltInTargetIds }
                .flatMap { it.addresses }
            if (selected.isEmpty()) {
                TargetInputResult(error = IpScanInput.ScanError.NoBuiltInTargets)
            } else {
                TargetInputResult(addressText = selected.joinToString("\n"))
            }
        }
        TargetSource.REMOTE -> if (remoteTargetsText.isBlank()) {
            TargetInputResult(error = IpScanInput.ScanError.RemoteListNotLoaded)
        } else {
            TargetInputResult(addressText = remoteTargetsText)
        }
    }

    fun prepareScan(
        addressText: String,
        countText: String,
        mode: Mode,
        selectedPorts: Collection<Int>,
        customPortsText: String,
    ): ScanPreparation {
        val count = countText.trim().toIntOrNull()
            ?: return ScanPreparation(error = IpScanInput.ScanError.InvalidCount)
        if (count !in 1..IpScanInput.MAX_ADDRESSES) {
            return ScanPreparation(error = IpScanInput.ScanError.CountOutOfRange)
        }

        val targets = IpScanInput.parseTargets(addressText, count)
        if (targets.error != null) return ScanPreparation(error = targets.error)

        val ports = if (mode == Mode.NONE) {
            emptyList()
        } else {
            val parsed = IpScanInput.parsePorts(selectedPorts, customPortsText)
            if (parsed.error != null) return ScanPreparation(error = parsed.error)
            parsed.ports
        }
        if (mode != Mode.NONE && ports.isEmpty()) {
            return ScanPreparation(error = IpScanInput.ScanError.NoPorts)
        }
        if (targets.addresses.size * (if (mode == Mode.NONE) 1 else ports.size) > IpScanInput.MAX_PROBES) {
            return ScanPreparation(error = IpScanInput.ScanError.TooManyProbes)
        }
        return ScanPreparation(
            request = ScanRequest(
                addresses = targets.addresses,
                mode = mode,
                ports = ports,
            ),
        )
    }

    /** Scans every selected address/port with bounded concurrency and reports each completed probe. */
    suspend fun scan(
        request: ScanRequest,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<AddressResult> = coroutineScope {
        require(request.addresses.isNotEmpty()) { "No IP addresses to scan" }
        require(request.addresses.size <= IpScanInput.MAX_ADDRESSES) { "IP scan limit exceeded" }
        require(timeoutMs in 100..10_000) { "Timeout is outside the supported range" }
        require(request.addresses.all(IpScanInput::isValidAddress)) { "Invalid numeric IP address" }
        if (request.mode == Mode.NONE) {
            require(request.ports.isEmpty()) { "None mode cannot include ports" }
        } else {
            require(request.ports.isNotEmpty() && request.ports.all { it in 1..65_535 }) {
                "Invalid ports"
            }
        }

        val targets = if (request.mode == Mode.NONE) {
            request.addresses.map { it to null }
        } else {
            request.addresses.flatMap { address -> request.ports.map { address to it } }
        }
        require(targets.size <= IpScanInput.MAX_PROBES) { "Too many probes in one scan" }

        val gate = Semaphore(concurrency.coerceIn(1, MAX_CONCURRENCY))
        val completed = AtomicInteger(0)
        val probes = targets.map { (address, port) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val result = probe(address, request.mode, port, timeoutMs)
                    onProgress(completed.incrementAndGet(), targets.size)
                    result
                }
            }
        }.awaitAll()

        val byAddress = probes.groupBy { it.address }
        request.addresses.distinct().map { address ->
            AddressResult(address = address, probes = byAddress[address].orEmpty())
        }.sortedWith(
            compareByDescending<AddressResult> { it.found }
                .thenBy { result ->
                    result.probes.mapNotNull { it.latencyMs }.minOrNull() ?: Long.MAX_VALUE
                }
                .thenBy { it.address },
        )
    }

    /**
     * Returns the public address when one of several independent plain-text endpoints is reachable.
     * If those providers are filtered by the current network, null is returned rather than guessing.
     */
    suspend fun lookupPublicIp(): String? = withContext(Dispatchers.IO) {
        PUBLIC_IP_ENDPOINTS.firstNotNullOfOrNull(::fetchPublicIp)
    }

    /** Downloads a small, plain-text list over HTTPS; each non-comment entry must be a numeric IP, CIDR, or range. */
    suspend fun loadRemoteTargetList(urlText: String): RemoteListResult {
        val url = withContext(Dispatchers.Default) { validatedHttpsUrl(urlText) }
            ?: return RemoteListResult(error = RemoteListError.INVALID_URL)
        val fetched = withContext(Dispatchers.IO) { fetchRemoteTargetText(url) }
        if (fetched.tooLarge) return RemoteListResult(error = RemoteListError.TOO_LARGE)
        val content = fetched.text
            ?: return RemoteListResult(error = RemoteListError.DOWNLOAD_FAILED)
        val normalized = withContext(Dispatchers.Default) { IpScanInput.normalizeRemoteList(content) }
            ?: return RemoteListResult(error = RemoteListError.INVALID_CONTENT)
        return RemoteListResult(targetsText = normalized)
    }

    private data class RemoteFetchResult(
        val text: String? = null,
        val tooLarge: Boolean = false,
    )

    fun isValidRemoteListUrl(value: String): Boolean = validatedHttpsUrl(value) != null

    private fun validatedHttpsUrl(value: String): URL? {
        if (value.length > MAX_REMOTE_URL_LENGTH) return null
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        if (uri.userInfo != null || uri.rawFragment != null || uri.port !in -1..65_535 || uri.port == 0) {
            return null
        }
        return runCatching { uri.toURL() }.getOrNull()
    }

    private fun fetchRemoteTargetText(initialUrl: URL): RemoteFetchResult {
        var url = initialUrl
        for (redirect in 0..MAX_REMOTE_REDIRECTS) {
            var connection: HttpURLConnection? = null
            try {
                connection = url.openConnection() as? HttpURLConnection ?: return RemoteFetchResult()
                connection.connectTimeout = REMOTE_LIST_TIMEOUT_MS
                connection.readTimeout = REMOTE_LIST_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "text/plain")
                connection.setRequestProperty("User-Agent", "RXVPN-IP-Scanner")
                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirect == MAX_REMOTE_REDIRECTS) return RemoteFetchResult()
                    val location = connection.getHeaderField("Location") ?: return RemoteFetchResult()
                    val next = runCatching { url.toURI().resolve(location) }.getOrNull()
                        ?: return RemoteFetchResult()
                    url = validatedHttpsUrl(next.toString()) ?: return RemoteFetchResult()
                    continue
                }
                if (status !in 200..299) {
                    LogUtil.e(AppConfig.TAG, "IP scanner target-list request failed with HTTP $status")
                    return RemoteFetchResult()
                }
                if (connection.contentLengthLong > MAX_REMOTE_TARGET_BYTES) {
                    return RemoteFetchResult(tooLarge = true)
                }

                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1_024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > MAX_REMOTE_TARGET_BYTES) {
                            return RemoteFetchResult(tooLarge = true)
                        }
                        output.write(buffer, 0, read)
                    }
                }
                return RemoteFetchResult(text = output.toString(Charsets.UTF_8.name()))
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "IP scanner target-list download failed (${error.javaClass.simpleName})")
                return RemoteFetchResult()
            } finally {
                connection?.disconnect()
            }
        }
        return RemoteFetchResult()
    }

    private fun fetchPublicIp(endpoint: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = PUBLIC_IP_TIMEOUT_MS
            connection.readTimeout = PUBLIC_IP_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "text/plain")
            connection.setRequestProperty("User-Agent", "RXVPN-IP-Scanner")
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLine() }
                ?.trim()
                .orEmpty()
            IpScanInput.normalizeAddress(text)
        } catch (error: Exception) {
            LogUtil.d(AppConfig.TAG, "IP scanner public IP lookup failed (${error.javaClass.simpleName})")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun probe(
        address: String,
        mode: Mode,
        port: Int?,
        timeoutMs: Int,
    ): ProbeResult = withContext(Dispatchers.IO) {
        val inet = IpScanInput.addressOf(address)
            ?: return@withContext ProbeResult(address, mode, port, Status.ERROR, null)
        val started = System.nanoTime()
        when (mode) {
            Mode.TCP -> probeTcp(address, inet, requireNotNull(port), timeoutMs, started)
            Mode.UDP -> probeUdp(address, inet, requireNotNull(port), timeoutMs, started)
            Mode.NONE -> probeReachability(address, inet, timeoutMs, started)
        }
    }

    private fun probeTcp(
        address: String,
        inet: InetAddress,
        port: Int,
        timeoutMs: Int,
        started: Long,
    ): ProbeResult = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(inet, port), timeoutMs)
        }
        ProbeResult(address, Mode.TCP, port, Status.OPEN, elapsedMillis(started))
    } catch (error: ConnectException) {
        val status = if (error.message?.contains("refused", ignoreCase = true) == true) {
            Status.CLOSED
        } else {
            Status.NO_RESPONSE
        }
        ProbeResult(address, Mode.TCP, port, status, elapsedMillis(started))
    } catch (_: SocketTimeoutException) {
        ProbeResult(address, Mode.TCP, port, Status.NO_RESPONSE, elapsedMillis(started))
    } catch (_: Exception) {
        ProbeResult(address, Mode.TCP, port, Status.NO_RESPONSE, elapsedMillis(started))
    }

    /** UDP has no generic handshake; a timeout is intentionally reported as inconclusive. */
    private fun probeUdp(
        address: String,
        inet: InetAddress,
        port: Int,
        timeoutMs: Int,
        started: Long,
    ): ProbeResult = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(inet, port)
            val emptyProbe = DatagramPacket(EMPTY_UDP_PROBE, EMPTY_UDP_PROBE.size, inet, port)
            socket.send(emptyProbe)
            val response = DatagramPacket(ByteArray(UDP_RESPONSE_BYTES), UDP_RESPONSE_BYTES)
            socket.receive(response)
        }
        ProbeResult(address, Mode.UDP, port, Status.RESPONSE, elapsedMillis(started))
    } catch (_: PortUnreachableException) {
        ProbeResult(address, Mode.UDP, port, Status.CLOSED, elapsedMillis(started))
    } catch (_: SocketTimeoutException) {
        ProbeResult(address, Mode.UDP, port, Status.NO_RESPONSE, elapsedMillis(started))
    } catch (_: Exception) {
        ProbeResult(address, Mode.UDP, port, Status.NO_RESPONSE, elapsedMillis(started))
    }

    /** ICMP-style reachability may be blocked even for a live host, so a miss is not proof of absence. */
    private fun probeReachability(
        address: String,
        inet: InetAddress,
        timeoutMs: Int,
        started: Long,
    ): ProbeResult = try {
        val reachable = inet.isReachable(timeoutMs)
        ProbeResult(
            address = address,
            mode = Mode.NONE,
            port = null,
            status = if (reachable) Status.REACHABLE else Status.NO_RESPONSE,
            latencyMs = elapsedMillis(started),
        )
    } catch (_: Exception) {
        ProbeResult(address, Mode.NONE, null, Status.NO_RESPONSE, elapsedMillis(started))
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(1L)

    private const val DEFAULT_TIMEOUT_MS = 1_500
    private const val DEFAULT_CONCURRENCY = 24
    private const val MAX_CONCURRENCY = 32
    private const val UDP_RESPONSE_BYTES = 1_024
    private const val PUBLIC_IP_TIMEOUT_MS = 1_400
    private const val REMOTE_LIST_TIMEOUT_MS = 5_000
    private const val MAX_REMOTE_TARGET_BYTES = 32 * 1_024
    private const val MAX_REMOTE_REDIRECTS = 3
    private const val MAX_REMOTE_URL_LENGTH = 2_048
    private val EMPTY_UDP_PROBE = ByteArray(0)
    private val PUBLIC_IP_ENDPOINTS = listOf(
        "https://api.ipify.org",
        "https://api64.ipify.org",
        "https://checkip.amazonaws.com",
        "https://ifconfig.me/ip",
    )
}

/** Input parsing kept pure so IPv4/IPv6 ranges and port choices can be tested on the JVM. */
object IpScanInput {

    enum class ScanError {
        InvalidTargets,
        InvalidCount,
        CountOutOfRange,
        NotEnoughAddresses,
        InvalidPort,
        TooManyPorts,
        NoPorts,
        TooManyProbes,
        ScanFailed,
        NoBuiltInTargets,
        RemoteListNotLoaded,
    }

    data class TargetParseResult(
        val addresses: List<String> = emptyList(),
        val error: ScanError? = null,
    )

    data class PortParseResult(
        val ports: List<Int> = emptyList(),
        val error: ScanError? = null,
    )

    private data class IpRange(
        val byteCount: Int,
        val first: BigInteger,
        val last: BigInteger,
    ) {
        val size: BigInteger get() = last.subtract(first).add(BigInteger.ONE)
    }

    const val MAX_ADDRESSES = 256
    const val MAX_PORTS = 12
    const val MAX_PROBES = 2_048

    fun parseTargets(input: String, requestedCount: Int): TargetParseResult {
        if (input.isBlank() || input.length > MAX_INPUT_LENGTH || requestedCount !in 1..MAX_ADDRESSES) {
            return TargetParseResult(error = ScanError.InvalidTargets)
        }
        val normalized = input.replace(Regex("\\s+-\\s+"), "-")
        val tokens = normalized.trim().split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > MAX_INPUT_TOKENS) {
            return TargetParseResult(error = ScanError.InvalidTargets)
        }

        val ranges = tokens.map { token -> parseRange(token) ?: return TargetParseResult(error = ScanError.InvalidTargets) }
        val merged = mergeRanges(ranges)
        val totalAvailable = merged.fold(BigInteger.ZERO) { total, range -> total.add(range.size) }
        if (totalAvailable < BigInteger.valueOf(requestedCount.toLong())) {
            return TargetParseResult(error = ScanError.NotEnoughAddresses)
        }

        val addresses = LinkedHashSet<String>(requestedCount)
        repeat(requestedCount) { index ->
            val offset = if (totalAvailable == BigInteger.valueOf(requestedCount.toLong())) {
                BigInteger.valueOf(index.toLong())
            } else {
                totalAvailable
                    .multiply(BigInteger.valueOf(index.toLong() * 2L + 1L))
                    .divide(BigInteger.valueOf(requestedCount.toLong() * 2L))
            }
            val selected = addressAt(merged, offset)
                ?: return TargetParseResult(error = ScanError.InvalidTargets)
            addresses += selected
        }
        if (addresses.size != requestedCount) {
            return TargetParseResult(error = ScanError.NotEnoughAddresses)
        }
        return TargetParseResult(addresses = addresses.toList())
    }

    /** Strips line comments, canonicalizes numeric targets, and validates a downloaded list. */
    fun normalizeRemoteList(input: String): String? {
        if (input.isBlank() || input.length > MAX_INPUT_LENGTH) return null
        val cleaned = input.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("\\s+-\\s+"), "-")
        val tokens = cleaned.split(Regex("[,;\\s]+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > MAX_INPUT_TOKENS) return null
        val normalized = tokens.map { normalizeTargetToken(it) ?: return null }
            .joinToString("\n")
        if (normalized.isBlank() || normalized.length > MAX_INPUT_LENGTH) return null
        return normalized.takeIf { parseTargets(it, 1).error == null }
    }

    private fun normalizeTargetToken(token: String): String? {
        val slash = token.indexOf('/')
        if (slash >= 0) {
            if (slash == token.lastIndex || token.indexOf('/', slash + 1) >= 0) return null
            val address = normalizeAddress(token.substring(0, slash)) ?: return null
            val prefix = token.substring(slash + 1).toIntOrNull() ?: return null
            val maxPrefix = if (':' in address) 128 else 32
            if (prefix !in 0..maxPrefix) return null
            return "$address/$prefix"
        }

        val separator = token.indexOf('-')
        if (separator >= 0) {
            if (separator == 0 || separator == token.lastIndex || token.indexOf('-', separator + 1) >= 0) return null
            val first = normalizeAddress(token.substring(0, separator)) ?: return null
            val last = normalizeAddress(token.substring(separator + 1)) ?: return null
            return "$first-$last"
        }
        return normalizeAddress(token)
    }

    fun parsePorts(selectedPorts: Collection<Int>, customPortsText: String): PortParseResult {
        if (customPortsText.length > MAX_PORT_INPUT_LENGTH) {
            return PortParseResult(error = ScanError.TooManyPorts)
        }
        val tokens = customPortsText.trim()
            .takeIf { it.isNotEmpty() }
            ?.split(Regex("[,;\\s]+"))
            .orEmpty()
        val custom = tokens.map { token ->
            token.toIntOrNull()?.takeIf { it in 1..65_535 }
                ?: return PortParseResult(error = ScanError.InvalidPort)
        }
        val ports = (selectedPorts + custom).distinct()
        if (ports.any { it !in 1..65_535 }) return PortParseResult(error = ScanError.InvalidPort)
        if (ports.size > MAX_PORTS) return PortParseResult(error = ScanError.TooManyPorts)
        return PortParseResult(ports = ports)
    }

    fun normalizeAddress(input: String): String? {
        val bytes = addressBytes(input) ?: return null
        return runCatching { InetAddress.getByAddress(bytes).hostAddress.lowercase() }.getOrNull()
    }

    fun isValidAddress(input: String): Boolean = addressBytes(input) != null

    fun addressOf(input: String): InetAddress? = addressBytes(input)?.let {
        runCatching { InetAddress.getByAddress(it) }.getOrNull()
    }

    private fun parseRange(token: String): IpRange? {
        if (token.count { it == '/' } == 1) {
            val (addressText, prefixText) = token.split('/', limit = 2)
            val bytes = addressBytes(addressText) ?: return null
            val bitCount = bytes.size * 8
            val prefix = prefixText.toIntOrNull()?.takeIf { it in 0..bitCount } ?: return null
            val hostBits = bitCount - prefix
            val address = BigInteger(1, bytes)
            val hostMask = BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)
            val network = address.and(BigInteger.ONE.shiftLeft(bitCount).subtract(BigInteger.ONE).xor(hostMask))
            val broadcast = network.add(hostMask)
            var first = network
            var last = broadcast
            if (bytes.size == IPV4_BYTES && prefix <= 30) {
                first = first.add(BigInteger.ONE)
                last = last.subtract(BigInteger.ONE)
            }
            return IpRange(bytes.size, first, last).takeIf { it.first <= it.last }
        }
        if ('/' in token) return null

        val separator = token.indexOf('-')
        if (separator >= 0) {
            if (separator == 0 || separator == token.lastIndex || token.indexOf('-', separator + 1) >= 0) return null
            val firstBytes = addressBytes(token.substring(0, separator)) ?: return null
            val lastBytes = addressBytes(token.substring(separator + 1)) ?: return null
            if (firstBytes.size != lastBytes.size) return null
            val first = BigInteger(1, firstBytes)
            val last = BigInteger(1, lastBytes)
            return IpRange(firstBytes.size, first, last).takeIf { first <= last }
        }

        val bytes = addressBytes(token) ?: return null
        val value = BigInteger(1, bytes)
        return IpRange(bytes.size, value, value)
    }

    private fun mergeRanges(input: List<IpRange>): List<IpRange> {
        val sorted = input.sortedWith(
            compareBy<IpRange> { it.byteCount }.thenBy { it.first }.thenBy { it.last },
        )
        val merged = mutableListOf<IpRange>()
        sorted.forEach { next ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.byteCount == next.byteCount &&
                next.first <= previous.last.add(BigInteger.ONE)
            ) {
                merged[merged.lastIndex] = previous.copy(last = maxOf(previous.last, next.last))
            } else {
                merged += next
            }
        }
        return merged
    }

    private fun addressAt(ranges: List<IpRange>, initialOffset: BigInteger): String? {
        var offset = initialOffset
        for (range in ranges) {
            if (offset < range.size) {
                val value = range.first.add(offset)
                val bytes = fixedLengthBytes(value, range.byteCount) ?: return null
                return normalizeAddress(InetAddress.getByAddress(bytes).hostAddress)
            }
            offset = offset.subtract(range.size)
        }
        return null
    }

    private fun fixedLengthBytes(value: BigInteger, byteCount: Int): ByteArray? {
        val raw = value.toByteArray()
        if (raw.size > byteCount + 1 || (raw.size == byteCount + 1 && raw[0] != 0.toByte())) return null
        val bytes = ByteArray(byteCount)
        val copyLength = minOf(raw.size, byteCount)
        System.arraycopy(raw, raw.size - copyLength, bytes, byteCount - copyLength, copyLength)
        return bytes
    }

    private fun addressBytes(input: String): ByteArray? {
        val value = input.trim().removeSurrounding("[", "]")
        if (value.isEmpty() || value.length > MAX_ADDRESS_LENGTH || '%' in value) return null

        if (':' in value) {
            if (!IPV6_CHARS.matches(value)) return null
            return runCatching { InetAddress.getByName(value).address }
                .getOrNull()
                ?.takeIf { it.size == IPV6_BYTES }
        }

        val parts = value.split('.')
        if (parts.size != IPV4_BYTES || parts.any { it.isEmpty() || it.length > 3 || it.any { char -> !char.isDigit() } }) {
            return null
        }
        val octets = parts.map { it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return null }
        return ByteArray(IPV4_BYTES) { index -> octets[index].toByte() }
    }

    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    private const val MAX_INPUT_LENGTH = 32_768
    private const val MAX_INPUT_TOKENS = 256
    private const val MAX_PORT_INPUT_LENGTH = 256
    private const val MAX_ADDRESS_LENGTH = 64
    private val IPV6_CHARS = Regex("[0-9a-fA-F:.]+")
}
