package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AetherEngine
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Direct IP probes and the small, pinned Cloudflare speed sample used by both the IP scanner and
 * Cloudflare optimizer. The existing [ConfigBenchmark] remains the owner of real config pings and
 * traffic-through-the-running-tunnel measurements; these direct edge measurements are never
 * presented as those config measurements.
 */
object IpScanner {

    data class ProbeResult(
        val address: String,
        val port: Int?,
        /** Time for the TCP connect only; this is not a config ping. */
        val tcpConnectMs: Long? = null,
        /** Time for the TLS handshake after TCP has connected, when SNI was supplied. */
        val tlsHandshakeMs: Long? = null,
        /** OS reachability probe used only when the user chose not to test a port. */
        val reachabilityMs: Long? = null,
        /** null means no TLS test was requested; false means the handshake did not complete. */
        val tlsSucceeded: Boolean? = null,
        val downloadMbps: Double? = null,
        val uploadMbps: Double? = null,
    )

    data class SpeedMeasurement(
        val downloadMbps: Double? = null,
        val uploadMbps: Double? = null,
    )

    /** A candidate measured against the same Cloudflare speed endpoint and edge. */
    data class EdgeCandidate(
        val address: String,
        val edgeResponseMs: Long,
        val downloadMbps: Double? = null,
        val uploadMbps: Double? = null,
    )

    data class ConfigMatch(
        val sourceGuid: String,
        val targetGuid: String? = null,
        /** Ping measured for the route that should be selected. */
        val pingMs: Long,
        /** Direct IP-profile ping, used if a tested chain cannot be saved. */
        val entryPingMs: Long = pingMs,
    )

    private data class TemporaryProfile(
        val guid: String,
        val profile: ProfileItem,
    )

    private data class TemporaryChainTest(
        val guid: String,
        val sourceGuid: String,
        val targetGuid: String,
        val entryPingMs: Long,
    )

    private val connectSearchMutex = Mutex()
    private val tlsSocketFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SSLContext.getDefault().socketFactory
    }

    /** Returns the stable, bounded sample already used by the Cloudflare assistant. */
    fun cloudflareCandidates(): List<String> = CloudflareAssistant.edgeAddresses()

    /** Ordinary saved profiles that can safely have only their endpoint address replaced. */
    fun connectableConfigGuids(preferredGuid: String? = null): List<String> {
        val candidates = MmkvManager.decodeAllServerList().distinct().mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            if (!isConnectableProfile(guid, profile)) return@mapNotNull null
            guid to (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L)
        }
        return candidates.sortedWith(
            compareBy<Pair<String, Long>> { if (it.first == preferredGuid) 0 else 1 }
                .thenBy { (_, ping) -> if (ping > 0L) 0 else if (ping == 0L) 1 else 2 }
                .thenBy { (_, ping) -> ping.takeIf { it > 0L } ?: Long.MAX_VALUE }
                .thenBy { it.first },
        ).take(MAX_CONNECT_CONFIGS).map { it.first }
    }

    /** Removes hidden probe groups left behind by process death during an earlier search. */
    suspend fun cleanupStaleTemporaryProfiles() {
        connectSearchMutex.withLock { runCatching { clearTemporaryConfigProfiles() } }
    }

    /**
     * Tests saved proxy profiles at this IP, then tests the best working profile as an entry hop
     * to the currently selected profile. Scratch configs are hidden and removed on every exit.
     */
    suspend fun findBestConfigAtAddress(
        context: Context,
        address: String,
        port: Int?,
        preferredGuid: String?,
        preferredSni: String?,
        applySniToAll: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ConfigMatch? = connectSearchMutex.withLock {
        if (!IpScanInput.isValidIpv4(address) || port?.let { it !in 1..65_535 } == true) {
            return@withLock null
        }
        val normalizedPreferredSni = preferredSni?.takeIf { it.isNotBlank() }
            ?.let(IpScanInput::normalizeSni)
            ?: if (preferredSni.isNullOrBlank()) null else return@withLock null

        val selectedBeforeProbe = MmkvManager.getSelectServer()
        val selectionToRestore = selectedBeforeProbe
            ?.takeIf { guid ->
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@takeIf false
                profile.subscriptionId != TEMP_CONFIG_GROUP_ID
            }
            .orEmpty()
        val temporaryGuidToSource = linkedMapOf<String, String>()
        val temporaryGroupId = TEMP_CONFIG_GROUP_ID
        try {
            clearTemporaryConfigProfiles()
            MmkvManager.setSelectServer(selectionToRestore)

            val temporaryProfiles = linkedMapOf<String, TemporaryProfile>()
            connectableConfigGuids(preferredGuid).forEach { sourceGuid ->
                val source = MmkvManager.decodeServerConfig(sourceGuid) ?: return@forEach
                if (!isConnectableProfile(sourceGuid, source)) return@forEach
                val copy = copyProfileForAddress(
                    source = source,
                    address = address,
                    port = port,
                    sni = normalizedPreferredSni.takeIf { applySniToAll || sourceGuid == preferredGuid },
                    groupId = temporaryGroupId,
                    copySuffix = TEMP_CONFIG_SUFFIX,
                ) ?: return@forEach
                val temporaryGuid = Utils.getUuid()
                val temporaryProfile = copy.copy(remarks = "__rx_ip_probe_$temporaryGuid")
                MmkvManager.encodeServerConfig(temporaryGuid, temporaryProfile)
                temporaryProfiles[sourceGuid] = TemporaryProfile(temporaryGuid, temporaryProfile)
                temporaryGuidToSource[temporaryGuid] = sourceGuid
                // encodeServerConfig selects a profile when no selection exists.
                MmkvManager.setSelectServer(selectionToRestore)
            }

            val temporaryGuids = temporaryProfiles.values.map { it.guid }
            if (temporaryGuids.isEmpty()) {
                onProgress(0, 0)
                return@withLock null
            }
            onProgress(0, temporaryGuids.size)
            val directPings = ConfigBenchmark.pingConfigs(
                context = context,
                guids = temporaryGuids,
                concurrency = CONNECT_TEST_CONCURRENCY,
                onProgress = onProgress,
            )
            val workingDirectProfiles = directPings.entries
                .filter { it.value > 0L }
                .mapNotNull { (guid, ping) ->
                    temporaryGuidToSource[guid]?.let { sourceGuid -> Triple(sourceGuid, guid, ping) }
                }
                .sortedWith(compareBy<Triple<String, String, Long>> { it.third }.thenBy { it.first })
            val directBest = workingDirectProfiles.firstOrNull()?.let { (sourceGuid, _, ping) ->
                ConfigMatch(sourceGuid = sourceGuid, pingMs = ping)
            }

            val targetProfiles = preferredGuid?.let(::resolveTargetRouteProfiles).orEmpty()
            val chainTests = if (targetProfiles.isEmpty()) emptyList() else {
                workingDirectProfiles.asSequence()
                    .filter { (sourceGuid, _, _) -> sourceGuid != preferredGuid }
                    .filter { (sourceGuid, _, _) ->
                        val source = MmkvManager.decodeServerConfig(sourceGuid) ?: return@filter false
                        targetProfiles.none { it.duplicateIdentity() == source.duplicateIdentity() }
                    }
                    .take(MAX_CHAIN_UPSTREAMS)
                    .mapNotNull { (sourceGuid, _, entryPingMs) ->
                        val targetGuid = preferredGuid ?: return@mapNotNull null
                        val entry = temporaryProfiles[sourceGuid]?.profile ?: return@mapNotNull null
                        if (targetProfiles.any { !hasUniqueProfileRemarks(it) } ||
                            !hasUniqueProfileRemarks(entry, temporaryGroupId)
                        ) return@mapNotNull null
                        val chainSpec = buildProxyChainSpec(targetProfiles, entry) ?: return@mapNotNull null
                        val chainGuid = Utils.getUuid()
                        val chainProfile = ProfileItem.create(EConfigType.PROXYCHAIN).apply {
                            remarks = "__rx_ip_probe_chain_$chainGuid"
                            subscriptionId = temporaryGroupId
                            proxyChainProfiles = chainSpec
                        }
                        MmkvManager.encodeServerConfig(chainGuid, chainProfile)
                        MmkvManager.setSelectServer(selectionToRestore)
                        TemporaryChainTest(chainGuid, sourceGuid, targetGuid, entryPingMs)
                    }
                    .toList()
            }

            if (chainTests.isNotEmpty()) {
                val chainGuids = chainTests.map { it.guid }
                val chainPings = ConfigBenchmark.pingConfigs(
                    context = context,
                    guids = chainGuids,
                    concurrency = CONNECT_TEST_CONCURRENCY,
                    onProgress = { done, total ->
                        onProgress(temporaryGuids.size + done, temporaryGuids.size + total)
                    },
                )
                val fastestChain = chainPings.entries
                    .filter { it.value > 0L }
                    .minWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                if (fastestChain != null) {
                    val chain = chainTests.firstOrNull { it.guid == fastestChain.key }
                    if (chain != null) {
                        return@withLock ConfigMatch(
                            sourceGuid = chain.sourceGuid,
                            targetGuid = chain.targetGuid,
                            pingMs = fastestChain.value,
                            entryPingMs = chain.entryPingMs,
                        )
                    }
                }
            }
            directBest
        } finally {
            runCatching { MmkvManager.setSelectServer(selectionToRestore) }
            runCatching { MmkvManager.removeServerViaSubid(temporaryGroupId) }
            runCatching { MmkvManager.setSelectServer(selectionToRestore) }
        }
    }

    private fun isConnectableProfile(guid: String, profile: ProfileItem): Boolean =
        guid != AppConfig.FIREFOX_VPN_PROFILE_ID && isStandardProxyProfile(profile)

    private fun isStandardProxyProfile(profile: ProfileItem): Boolean =
        !AetherEngine.isAetherProfile(profile) &&
            !profile.server.isNullOrBlank() &&
            profile.configType in CONNECTABLE_PROFILE_TYPES

    private fun isValidChainMember(profile: ProfileItem): Boolean =
        isStandardProxyProfile(profile) && profile.server?.let {
            Utils.isPureIpAddress(it) || Utils.isValidUrl(it)
        } == true

    /** Returns the same egress-to-entry chain order used by CoreConfigContextBuilder. */
    private fun resolveTargetRouteProfiles(targetGuid: String): List<ProfileItem> {
        val target = MmkvManager.decodeServerConfig(targetGuid) ?: return emptyList()
        if (target.configType != EConfigType.PROXYCHAIN && !isValidChainMember(target)) return emptyList()
        return when (target.configType) {
            EConfigType.PROXYCHAIN -> {
                target.proxyChainProfiles.orEmpty()
                    .split(",")
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { SettingsManager.getServerViaRemarks(it) }
                    .filter(::isValidChainMember)
                    .toList()
                    .asReversed()
            }

            EConfigType.POLICYGROUP, EConfigType.CUSTOM -> emptyList()
            else -> {
                val subscription = target.subscriptionId
                    .takeIf { it.isNotBlank() }
                    ?.let { MmkvManager.decodeSubscription(it) }
                listOfNotNull(
                    subscription?.nextProfile?.let { SettingsManager.getServerViaRemarks(it) },
                    target,
                    subscription?.prevProfile?.let { SettingsManager.getServerViaRemarks(it) },
                ).filter(::isValidChainMember)
            }
        }
    }

    /**
     * The core reverses ProxyChain profile remarks when resolving them. Pass the reverse of the
     * desired egress-to-entry sequence so an IP profile becomes the actual entry hop.
     */
    internal fun buildProxyChainSpec(
        targetRouteEgressToEntry: List<ProfileItem>,
        entryProfile: ProfileItem,
    ): String? {
        val members = targetRouteEgressToEntry + entryProfile
        if (members.size < 2 || members.any { it.remarks.isBlank() || ',' in it.remarks }) return null
        return members.asReversed().joinToString(",") { it.remarks }
    }

    private fun hasUniqueProfileRemarks(profile: ProfileItem, additionalGroupId: String? = null): Boolean {
        val candidateGuids = MmkvManager.decodeAllServerList() +
            additionalGroupId?.let { MmkvManager.decodeServerList(it) }.orEmpty()
        val matches = candidateGuids.distinct().filter { guid ->
            MmkvManager.decodeServerConfig(guid)?.remarks == profile.remarks
        }
        return matches.size == 1
    }

    /** Scans one numeric IPv4, CIDR, or dash-separated IPv4 range. */
    suspend fun scan(
        addresses: List<String>,
        port: Int?,
        sni: String?,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        concurrency: Int = DEFAULT_CONCURRENCY,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ProbeResult> = coroutineScope {
        require(addresses.isNotEmpty()) { "No IP addresses to scan" }
        require(addresses.size <= IpScanInput.MAX_ADDRESSES) { "IP scan limit exceeded" }
        require(port == null || port in 1..65_535) { "Port is outside 1..65535" }
        require(timeoutMs > 0)
        val normalizedSni = sni?.let { IpScanInput.normalizeSni(it) }
        require(sni.isNullOrBlank() || normalizedSni != null) { "Invalid SNI" }
        require(port != null || normalizedSni == null) { "SNI requires a port" }

        val gate = Semaphore(concurrency.coerceIn(1, MAX_CONCURRENCY))
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val uniqueAddresses = addresses.distinct()
        uniqueAddresses.map { address ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val result = probe(address, port, normalizedSni, timeoutMs)
                    onProgress(done.incrementAndGet(), uniqueAddresses.size)
                    result
                }
            }
        }.awaitAll()
            .filterNotNull()
            .sortedWith(
                compareBy<ProbeResult> {
                    it.tlsHandshakeMs ?: it.tcpConnectMs ?: it.reachabilityMs ?: Long.MAX_VALUE
                }.thenBy { it.address }
            )
    }

    /** Re-tests the exact selected address with a new SNI. */
    suspend fun checkSni(
        address: String,
        port: Int,
        sni: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): ProbeResult? {
        val normalizedSni = IpScanInput.normalizeSni(sni) ?: return null
        if (!IpScanInput.isValidIpv4(address) || port !in 1..65_535) return null
        return probe(address, port, normalizedSni, timeoutMs)
    }

    private suspend fun probe(
        address: String,
        port: Int?,
        sni: String?,
        timeoutMs: Int,
    ): ProbeResult? = withContext(Dispatchers.IO) {
        if (!IpScanInput.isValidIpv4(address)) return@withContext null
        if (port == null) {
            try {
                val started = System.nanoTime()
                val ipBytes = IpScanInput.ipv4Bytes(address) ?: return@withContext null
                val reachable = InetAddress.getByAddress(ipBytes).isReachable(timeoutMs)
                if (!reachable) return@withContext null
                return@withContext ProbeResult(
                    address = address,
                    port = null,
                    reachabilityMs = elapsedMillis(started),
                )
            } catch (_: Exception) {
                return@withContext null
            }
        }

        try {
            Socket().use { raw ->
                val connectStarted = System.nanoTime()
                raw.connect(InetSocketAddress(address, port), timeoutMs)
                val connectMillis = elapsedMillis(connectStarted)
                if (sni.isNullOrBlank()) {
                    return@withContext ProbeResult(
                        address = address,
                        port = port,
                        tcpConnectMs = connectMillis,
                    )
                }

                raw.soTimeout = timeoutMs
                val tlsStarted = System.nanoTime()
                val tlsMillis = try {
                    val tls = tlsSocketFactory.createSocket(raw, sni, port, true) as SSLSocket
                    tls.use {
                        it.soTimeout = timeoutMs
                        it.sslParameters = it.sslParameters.apply {
                            serverNames = listOf(SNIHostName(sni))
                        }
                        it.startHandshake()
                        elapsedMillis(tlsStarted)
                    }
                } catch (_: Exception) {
                    null
                }
                ProbeResult(
                    address = address,
                    port = port,
                    tcpConnectMs = connectMillis,
                    tlsHandshakeMs = tlsMillis,
                    tlsSucceeded = tlsMillis != null,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads and uploads a small sample to speed.cloudflare.com while pinning that hostname to
     * [address]. TLS still verifies the speed-test service's certificate. The SNI used here is
     * speed.cloudflare.com, not the user-entered SNI in the scanner form.
     */
    suspend fun measureSpeed(
        address: String,
        port: Int = 443,
    ): SpeedMeasurement = withContext(Dispatchers.IO) {
        if (port != 443 || !IpScanInput.isValidIpv4(address) ||
            !CloudflareAssistant.isCloudflareIpv4(address)
        ) {
            return@withContext SpeedMeasurement()
        }
        SpeedMeasurement(
            downloadMbps = downloadMbps(address),
            uploadMbps = uploadMbps(address),
        )
    }

    /** Best balanced measured throughput, with edge response time as the tie-breaker. */
    fun bestEdgeCandidate(candidates: Collection<EdgeCandidate>): EdgeCandidate? =
        candidates.minWithOrNull(
            compareByDescending<EdgeCandidate> { throughputScore(it) ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.edgeResponseMs }
                .thenBy { it.address }
        )

    private fun throughputScore(candidate: EdgeCandidate): Double? = when {
        candidate.downloadMbps != null && candidate.uploadMbps != null ->
            kotlin.math.sqrt(candidate.downloadMbps * candidate.uploadMbps)
        candidate.downloadMbps != null -> candidate.downloadMbps
        else -> candidate.uploadMbps
    }

    /** Creates or refreshes a local copy of a config with the scanned IP. */
    fun saveConfigCopy(
        sourceGuid: String,
        address: String,
        port: Int?,
        sni: String?,
        groupName: String,
        copySuffix: String,
    ): String? {
        if (!IpScanInput.isValidIpv4(address)) return null
        if (port != null && port !in 1..65_535) return null
        val source = MmkvManager.decodeServerConfig(sourceGuid) ?: return null
        if (sourceGuid == AppConfig.FIREFOX_VPN_PROFILE_ID || AetherEngine.isAetherProfile(source)) {
            return null
        }
        val normalizedSni = sni?.takeIf { it.isNotBlank() }?.let(IpScanInput::normalizeSni)
            ?: if (sni.isNullOrBlank()) null else return null
        val originalHost = source.server?.trim().orEmpty()
        if (originalHost.isEmpty()) return null

        val groupId = ensureGroup(groupName)
        // The group may also contain user-imported profiles; only refresh entries marked as one
        // of this tool's copies. A regular source in the destination group still gets its own copy.
        val isOwnCopy = source.subscriptionId == groupId &&
            source.remarks.trim().endsWith(copySuffix)
        val previousCopy = if (isOwnCopy) null else {
            MmkvManager.decodeSettingsString(AppConfig.PREF_IP_SCANNER_COPY_PREFIX + sourceGuid)
                ?.takeIf { candidateGuid ->
                    if (candidateGuid.isBlank() || candidateGuid == sourceGuid) return@takeIf false
                    val candidate = MmkvManager.decodeServerConfig(candidateGuid) ?: return@takeIf false
                    candidate.subscriptionId == groupId &&
                        candidate.remarks.trim().endsWith(copySuffix)
                }
        }
        val targetGuid = if (isOwnCopy) sourceGuid else previousCopy.orEmpty()
        val copy = copyProfileForAddress(
            source = source,
            address = address,
            port = port,
            sni = normalizedSni,
            groupId = groupId,
            copySuffix = copySuffix,
            isOwnCopy = isOwnCopy,
        ) ?: return null

        return try {
            val saved = MmkvManager.encodeServerConfig(targetGuid, copy)
            if (!isOwnCopy) {
                MmkvManager.encodeSettings(AppConfig.PREF_IP_SCANNER_COPY_PREFIX + sourceGuid, saved)
            }
            SettingsChangeManager.makeRestartService()
            SettingsChangeManager.makeSetupGroupTab()
            saved
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "IP scanner: could not save the config copy", e)
            null
        }
    }

    /** Saves a ProxyChain copy that places this IP-backed profile before the current route. */
    fun saveProxyChainCopy(
        entryGuid: String,
        targetGuid: String,
        groupName: String,
        copySuffix: String,
    ): String? {
        val entry = MmkvManager.decodeServerConfig(entryGuid) ?: return null
        val target = MmkvManager.decodeServerConfig(targetGuid) ?: return null
        if (!isStandardProxyProfile(entry)) return null
        val targetRoute = resolveTargetRouteProfiles(targetGuid)
        if (targetRoute.isEmpty() || targetRoute.any {
                ',' in it.remarks || it.remarks.isBlank() || !hasUniqueProfileRemarks(it)
            } || !hasUniqueProfileRemarks(entry)
        ) return null
        if (targetRoute.any { it.duplicateIdentity() == entry.duplicateIdentity() }) return null
        val chainSpec = buildProxyChainSpec(targetRoute, entry) ?: return null
        val groupId = ensureGroup(groupName)
        val key = AppConfig.PREF_IP_SCANNER_CHAIN_PREFIX + entryGuid + "_" + targetGuid
        val previousCopy = MmkvManager.decodeSettingsString(key)
            ?.takeIf { candidateGuid ->
                val candidate = MmkvManager.decodeServerConfig(candidateGuid) ?: return@takeIf false
                candidate.subscriptionId == groupId && candidate.configType == EConfigType.PROXYCHAIN
            }
        val address = entry.server?.trim().orEmpty()
        val chainProfile = ProfileItem.create(EConfigType.PROXYCHAIN).apply {
            remarks = "${target.remarks} via $address $copySuffix".trim()
            description = (targetRoute + entry).joinToString(" → ") { it.remarks }
            subscriptionId = groupId
            proxyChainProfiles = chainSpec
        }

        return try {
            val saved = MmkvManager.encodeServerConfig(previousCopy.orEmpty(), chainProfile)
            MmkvManager.encodeSettings(key, saved)
            SettingsChangeManager.makeRestartService()
            SettingsChangeManager.makeSetupGroupTab()
            saved
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "IP scanner: could not save the proxy-chain copy", e)
            null
        }
    }

    /** Pure profile transformation used by [saveConfigCopy] and its regression tests. */
    internal fun copyProfileForAddress(
        source: ProfileItem,
        address: String,
        port: Int?,
        sni: String?,
        groupId: String,
        copySuffix: String,
        isOwnCopy: Boolean = false,
    ): ProfileItem? {
        if (!IpScanInput.isValidIpv4(address) || port?.let { it !in 1..65_535 } == true ||
            groupId.isBlank() || source.server.isNullOrBlank()
        ) return null
        val normalizedSni = sni?.takeIf { it.isNotBlank() }?.let(IpScanInput::normalizeSni)
            ?: if (sni.isNullOrBlank()) null else return null
        val originalHost = source.server?.trim().orEmpty()
        // Replacing a hostname with a numeric address must not silently replace the implicit
        // TLS SNI/WebSocket Host too. Preserve an explicitly configured host first, otherwise
        // carry the source hostname forward when it is a valid DNS name.
        val inferredHostname = IpScanInput.normalizeSni(originalHost)
        val preservedSni = normalizedSni
            ?: source.sni?.takeIf { it.isNotBlank() }
            ?: inferredHostname
        val preservedHost = source.host?.takeIf { it.isNotBlank() }
            ?: normalizedSni
            ?: source.sni?.takeIf { it.isNotBlank() }
            ?: inferredHostname
            ?: source.host
        val originalRemarks = source.remarks.trim().ifBlank { originalHost }
        val remarks = when {
            isOwnCopy -> originalRemarks
            originalRemarks.endsWith(copySuffix) -> originalRemarks
            else -> "$originalRemarks $copySuffix".trim()
        }
        return source.copy(
            remarks = remarks,
            server = address,
            serverPort = port?.toString() ?: source.serverPort,
            sni = preservedSni,
            host = preservedHost,
            subscriptionId = groupId,
        )
    }

    private fun clearTemporaryConfigProfiles() {
        MmkvManager.removeServerViaSubid(TEMP_CONFIG_GROUP_ID)
    }

    private fun ensureGroup(groupName: String): String {
        MmkvManager.decodeSettingsString(AppConfig.PREF_IP_SCANNER_GROUP)
            ?.takeIf { it.isNotBlank() && MmkvManager.decodeSubscription(it) != null }
            ?.let { return it }

        MmkvManager.decodeSubscriptions()
            .firstOrNull { it.subscription.url.isBlank() && it.subscription.remarks == groupName }
            ?.let { existing ->
                MmkvManager.encodeSettings(AppConfig.PREF_IP_SCANNER_GROUP, existing.guid)
                return existing.guid
            }

        val guid = Utils.getUuid()
        MmkvManager.encodeSubscription(
            guid,
            SubscriptionItem(remarks = groupName, url = "", enabled = true),
        )
        MmkvManager.encodeSettings(AppConfig.PREF_IP_SCANNER_GROUP, guid)
        return guid
    }

    private fun downloadMbps(address: String): Double? = try {
        val request = Request.Builder()
            .url("$SPEED_URL/__down?bytes=$DOWNLOAD_BYTES")
            .build()
        val started = System.nanoTime()
        var received = 0L
        pinnedClient(address).newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            body.byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    received += read
                }
            }
        }
        if (received != DOWNLOAD_BYTES.toLong()) null else mbps(received, started)
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "IP scanner: Cloudflare download probe failed for $address", e)
        null
    }

    private fun uploadMbps(address: String): Double? = try {
        val payload = ByteArray(UPLOAD_BYTES)
        val request = Request.Builder()
            .url("$SPEED_URL/__up")
            .post(payload.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val started = System.nanoTime()
        val successful = pinnedClient(address).newCall(request).execute().use { it.isSuccessful }
        if (!successful) null else mbps(UPLOAD_BYTES.toLong(), started)
    } catch (e: Exception) {
        LogUtil.w(AppConfig.TAG, "IP scanner: Cloudflare upload probe failed for $address", e)
        null
    }

    private fun pinnedClient(address: String): OkHttpClient {
        val pinnedBytes = IpScanInput.ipv4Bytes(address)
            ?: throw IllegalArgumentException("Not an IPv4 address")
        return OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns { hostname ->
                if (hostname.equals(SPEED_TEST_HOST, ignoreCase = true)) {
                    listOf(InetAddress.getByAddress(hostname, pinnedBytes))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
            .connectTimeout(SPEED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SPEED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(SPEED_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun mbps(bytes: Long, startedNanos: Long): Double? {
        val seconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0
        if (seconds < MIN_MEASURED_SECONDS || bytes <= 0L) return null
        return bytes * 8.0 / seconds / 1_000_000.0
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(1L)

    private const val MAX_CONNECT_CONFIGS = 32
    private const val MAX_CHAIN_UPSTREAMS = 3
    private val CONNECTABLE_PROFILE_TYPES = setOf(
        EConfigType.VMESS,
        EConfigType.SHADOWSOCKS,
        EConfigType.SOCKS,
        EConfigType.VLESS,
        EConfigType.TROJAN,
        EConfigType.WIREGUARD,
        EConfigType.HYSTERIA2,
        EConfigType.HYSTERIA,
        EConfigType.HTTP,
    )
    private const val TEMP_CONFIG_GROUP_ID = "__rx_ip_scanner_probe__"
    private const val TEMP_CONFIG_SUFFIX = "(temporary IP probe)"
    private const val CONNECT_TEST_CONCURRENCY = 4
    private const val DEFAULT_TIMEOUT_MS = 1_800
    private const val DEFAULT_CONCURRENCY = 12
    private const val MAX_CONCURRENCY = 16
    private const val SPEED_TEST_HOST = "speed.cloudflare.com"
    private const val SPEED_URL = "https://$SPEED_TEST_HOST"
    private const val DOWNLOAD_BYTES = 1 * 1024 * 1024
    private const val UPLOAD_BYTES = 256 * 1024
    private const val SPEED_TIMEOUT_SECONDS = 8L
    private const val MIN_MEASURED_SECONDS = 0.08
}

/** Pure input validation kept separate so ranges and SNI can be tested without Android networking. */
internal object IpScanInput {

    enum class RangeError { Invalid, TooMany }
    enum class ScanError { InvalidRange, TooManyAddresses, InvalidPort, InvalidSni }

    data class ScanRequest(
        val addresses: List<String>,
        val port: Int?,
        val sni: String?,
    )

    data class ScanPreparation(
        val request: ScanRequest? = null,
        val error: ScanError? = null,
    )

    data class RangeResult(
        val addresses: List<String> = emptyList(),
        val error: RangeError? = null,
    )

    const val MAX_ADDRESSES = 256

    fun prepareScan(
        cloudflarePool: Boolean,
        customRange: String,
        portEnabled: Boolean,
        portText: String,
        sniText: String,
        cloudflareAddresses: List<String>,
    ): ScanPreparation {
        val addresses = if (cloudflarePool) {
            cloudflareAddresses.distinct().takeIf {
                it.isNotEmpty() && it.size <= MAX_ADDRESSES && it.all(::isValidIpv4)
            } ?: return ScanPreparation(error = ScanError.InvalidRange)
        } else {
            val parsed = parseIpv4Range(customRange)
            when (parsed.error) {
                RangeError.Invalid -> return ScanPreparation(error = ScanError.InvalidRange)
                RangeError.TooMany -> return ScanPreparation(error = ScanError.TooManyAddresses)
                null -> parsed.addresses
            }
        }
        val port = if (portEnabled) {
            portText.toIntOrNull()?.takeIf { it in 1..65_535 }
                ?: return ScanPreparation(error = ScanError.InvalidPort)
        } else {
            null
        }
        val sni = if (port == null || sniText.isBlank()) {
            null
        } else {
            normalizeSni(sniText) ?: return ScanPreparation(error = ScanError.InvalidSni)
        }
        return ScanPreparation(request = ScanRequest(addresses, port, sni))
    }

    fun parseIpv4Range(input: String, maxAddresses: Int = MAX_ADDRESSES): RangeResult {
        val value = input.trim()
        if (value.isEmpty() || maxAddresses < 1) return RangeResult(error = RangeError.Invalid)
        val bounds = when {
            value.count { it == '/' } == 1 -> {
                val (addressText, prefixText) = value.split('/', limit = 2)
                val address = ipv4ToLong(addressText) ?: return RangeResult(error = RangeError.Invalid)
                val prefix = prefixText.toIntOrNull()?.takeIf { it in 0..32 }
                    ?: return RangeResult(error = RangeError.Invalid)
                val count = 1L shl (32 - prefix)
                val mask = if (prefix == 0) 0L else (IPV4_MAX shl (32 - prefix)) and IPV4_MAX
                val start = address and mask
                start to (start + count - 1L)
            }
            value.contains('/') -> return RangeResult(error = RangeError.Invalid)
            value.count { it == '-' } == 1 -> {
                val (firstText, lastText) = value.split('-', limit = 2)
                val first = ipv4ToLong(firstText) ?: return RangeResult(error = RangeError.Invalid)
                val last = ipv4ToLong(lastText) ?: return RangeResult(error = RangeError.Invalid)
                if (last < first) return RangeResult(error = RangeError.Invalid)
                first to last
            }
            value.contains('-') -> return RangeResult(error = RangeError.Invalid)
            else -> {
                val address = ipv4ToLong(value) ?: return RangeResult(error = RangeError.Invalid)
                address to address
            }
        }
        val count = bounds.second - bounds.first + 1L
        if (count <= 0L) return RangeResult(error = RangeError.Invalid)
        if (count > maxAddresses) return RangeResult(error = RangeError.TooMany)
        return RangeResult(
            addresses = (bounds.first..bounds.second).map(::longToIpv4),
        )
    }

    fun normalizeSni(input: String): String? {
        val value = input.trim().trimEnd('.')
        if (value.isEmpty() || value.length > 253 || value.contains(':')) return null
        return try {
            val ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            if (ascii.isBlank() || ascii.length > 253 || isValidIpv4(ascii) ||
                ascii.split('.').any { it.isEmpty() || it.length > 63 }
            ) {
                null
            } else {
                SNIHostName(ascii)
                ascii
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun isValidIpv4(value: String): Boolean = ipv4ToLong(value) != null

    fun ipv4Bytes(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull()?.takeIf { byte -> byte in 0..255 } ?: return null }
        return ByteArray(4) { index -> octets[index].toByte() }
    }

    private fun ipv4ToLong(value: String): Long? {
        val parts = value.trim().split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            result = (result shl 8) or octet.toLong()
        }
        return result
    }

    private fun longToIpv4(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { shift ->
            ((value shr shift) and 0xff).toString()
        }

    private const val IPV4_MAX = 0xffff_ffffL
}
