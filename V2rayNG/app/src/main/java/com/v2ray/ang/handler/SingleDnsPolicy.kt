package com.v2ray.ang.handler

/**
 * Decides which address the single DNS mode uses. Kept apart from [SettingsManager], which reads
 * the stored values, so the rule can be tested on the JVM without storage or Android classes.
 */
object SingleDnsPolicy {

    /**
     * The one server of the single DNS mode, or null when the mode does not apply.
     *
     * An empty setting means [default]. A value that names several servers or that [isSupported]
     * rejects yields null on purpose: the caller then keeps the separate DNS lists instead of
     * starting the core with a resolver it cannot use.
     */
    fun pick(
        enabled: Boolean,
        value: String?,
        default: String,
        isSupported: (String) -> Boolean,
    ): String? {
        if (!enabled) return null
        val address = value?.trim().orEmpty().ifEmpty { default }
        if (address.contains(',') || address.any { it.isWhitespace() }) return null
        return address.takeIf(isSupported)
    }
}
