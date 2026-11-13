package com.v2ray.ang.ui.onboarding

import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.Language
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager

/**
 * The app no longer asks for a language on first launch: it starts in Persian, and English or the
 * phone's own language can be picked in settings.
 *
 * The default is applied once. An install that already carries an explicit choice keeps it.
 */
internal object LanguageOnboarding {

    /** Set once the default has been settled, so a later "phone language" choice is respected. */
    const val PREF_LANGUAGE_CHOSEN = "pref_language_chosen"

    /**
     * Language code to switch to, or null to leave things alone. "auto" counts as no choice:
     * before the default is settled it only ever means that nothing was stored.
     */
    fun defaultToApply(settled: Boolean, storedCode: String?): String? = when {
        settled -> null
        storedCode.isNullOrBlank() || storedCode == Language.AUTO.code -> Language.PERSIAN.code
        else -> null
    }

    /** Call after the activity is created; switching the language recreates it. */
    fun applyDefaultOnce() {
        val settled = MmkvManager.decodeSettingsBool(PREF_LANGUAGE_CHOSEN, false)
        if (settled) return
        val code = defaultToApply(false, MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE))
        MmkvManager.encodeSettings(PREF_LANGUAGE_CHOSEN, true)
        if (code != null) AppLocaleManager.setApplicationLanguage(code)
    }
}
