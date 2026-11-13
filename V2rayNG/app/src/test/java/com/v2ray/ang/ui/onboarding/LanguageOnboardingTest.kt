package com.v2ray.ang.ui.onboarding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LanguageOnboardingTest {

    @Test
    fun freshInstallStartsInPersian() {
        assertEquals("fa", LanguageOnboarding.defaultToApply(settled = false, storedCode = null))
        assertEquals("fa", LanguageOnboarding.defaultToApply(settled = false, storedCode = ""))
    }

    @Test
    fun unsettledAutoCountsAsNoChoice() {
        assertEquals("fa", LanguageOnboarding.defaultToApply(settled = false, storedCode = "auto"))
    }

    @Test
    fun explicitChoiceIsKept() {
        assertNull(LanguageOnboarding.defaultToApply(settled = false, storedCode = "en"))
        assertNull(LanguageOnboarding.defaultToApply(settled = false, storedCode = "ru"))
    }

    @Test
    fun phoneLanguagePickedLaterIsRespected() {
        assertNull(LanguageOnboarding.defaultToApply(settled = true, storedCode = "auto"))
        assertNull(LanguageOnboarding.defaultToApply(settled = true, storedCode = null))
    }
}
