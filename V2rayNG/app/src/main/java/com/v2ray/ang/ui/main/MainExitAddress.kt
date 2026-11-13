package com.v2ray.ang.ui.main

/**
 * Timing and text rules for the exit address typed into the status line.
 *
 * Kept free of Android and Compose so the pacing and the splitting can be tested on the JVM.
 */
internal object MainExitAddress {

    /** Time one character takes to appear. Slow enough to be read as typing, not as a flicker. */
    const val CHAR_MS = 95

    /** Pause between the last digit of the address and the first character of the country. */
    const val COUNTRY_DELAY_MS = 1000L

    /** Regional-indicator flag for a two-letter country code; empty for anything else. */
    fun flag(code: String): String {
        val upper = code.trim().uppercase()
        if (upper.length != 2 || upper.any { it !in 'A'..'Z' }) return ""
        val builder = StringBuilder()
        upper.forEach { builder.appendCodePoint(0x1F1E6 + (it - 'A')) }
        return builder.toString()
    }

    /** "🇩🇪 Germany" from a code and its localized name; the raw value when it is not a code. */
    fun countryLabel(code: String?, displayName: (String) -> String): String {
        val value = code?.trim().orEmpty()
        if (value.isEmpty()) return ""
        val flag = flag(value)
        if (flag.isEmpty()) return value
        val name = displayName(value.uppercase()).ifBlank { value.uppercase() }
        return "$flag $name"
    }

    /**
     * Splits text into the pieces that are typed one at a time. A flag is four UTF-16 units that
     * only make sense together, so it is one piece; so is any other surrogate pair.
     */
    fun typingUnits(text: String): List<String> {
        val units = ArrayList<String>(text.length)
        var index = 0
        while (index < text.length) {
            val first = text.codePointAt(index)
            var end = index + Character.charCount(first)
            if (isRegionalIndicator(first) && end < text.length) {
                val second = text.codePointAt(end)
                if (isRegionalIndicator(second)) end += Character.charCount(second)
            }
            units.add(text.substring(index, end))
            index = end
        }
        return units
    }

    /** Milliseconds needed to type [unitCount] pieces. */
    fun typingMillis(unitCount: Int): Int = unitCount.coerceAtLeast(0) * CHAR_MS

    private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF
}
