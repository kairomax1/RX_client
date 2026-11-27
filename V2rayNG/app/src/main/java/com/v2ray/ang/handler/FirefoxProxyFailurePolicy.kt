package com.v2ray.ang.handler

/**
 * Separates a destination-level rejection from an unhealthy Mozilla proxy.
 *
 * Fastly can answer CONNECT with `502 Destination IP Prohibited` when that one
 * destination is disallowed. The authenticated proxy may still be healthy, so
 * this response must not quarantine the endpoint or trigger a relay fallback.
 */
internal object FirefoxProxyFailurePolicy {

    private val httpStatus = Regex(
        "HTTP/1\\.[01] (\\d{3})(?:\\s+(.*))?",
        RegexOption.IGNORE_CASE,
    )

    fun isTargetPolicyRejection(reason: String): Boolean {
        val match = httpStatus.find(reason) ?: return false
        if (match.groupValues.getOrNull(1)?.toIntOrNull() != 502) return false

        val detail = match.groupValues.getOrNull(2).orEmpty().lowercase()
        return detail.contains("destination") && listOf(
            "prohibit",
            "forbid",
            "not allowed",
            "not permitted",
        ).any(detail::contains)
    }

    fun isEndpointFailure(reason: String): Boolean {
        if (isTargetPolicyRejection(reason)) return false

        val status = httpStatus.find(reason)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (status != null) return status == 421 || status in 500..599

        val lower = reason.lowercase()
        return listOf(
            "cannot be reached", "no answer", "did not answer", "timed out", "timeout",
            "connection reset", "connection refused", "broken pipe", "unreachable", "closed",
            "handshake",
        ).any(lower::contains)
    }

    fun shouldTryWorker(reason: String): Boolean {
        if (isTargetPolicyRejection(reason)) return false

        val status = httpStatus.find(reason)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (status != null) return status in 500..599

        val lower = reason.lowercase()
        return listOf(
            "cannot be reached", "no answer", "did not answer", "timed out", "timeout",
            "connection reset", "connection refused", "broken pipe", "unreachable", "closed",
            "handshake",
        ).any(lower::contains)
    }
}
