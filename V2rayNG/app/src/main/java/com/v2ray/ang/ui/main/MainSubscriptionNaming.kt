package com.v2ray.ang.ui.main

/**
 * The pasted text when it is exactly one subscription link and nothing else; null otherwise.
 * Only then is the user asked for a name: a mixed paste has no single thing to name.
 */
internal fun singleSubscriptionLink(text: String?, isSubscriptionUrl: (String) -> Boolean): String? {
    val lines = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val only = lines.singleOrNull() ?: return null
    return only.takeIf(isSubscriptionUrl)
}

/**
 * Name for a subscription the user did not name: [base] the first time, then "[base] ۲",
 * "[base] ۳" and so on, skipping names already taken.
 */
internal fun nextSubscriptionName(base: String, existing: Collection<String>): String {
    val taken = existing.mapTo(HashSet()) { it.trim() }
    if (base !in taken) return base
    var number = 2
    while (true) {
        val candidate = "$base ${persianDigits(number)}"
        if (candidate !in taken) return candidate
        number++
    }
}

internal fun persianDigits(number: Int): String =
    number.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

/** The name the user typed, or the automatic one when they declined or left it empty. */
internal fun resolveSubscriptionName(typed: String?, base: String, existing: Collection<String>): String =
    typed?.trim()?.takeIf { it.isNotEmpty() } ?: nextSubscriptionName(base, existing)
