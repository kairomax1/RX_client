package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FirefoxVpnCountrySelectionTest {

    @Test
    fun manualSelectionRemainsExclusiveEvenWhenAnotherCountryIsFaster() {
        val countries = listOf(country("Canada", "CA"), country("Germany", "DE"))

        val candidates = FirefoxVpnManager.countryCodesForUi(
            countries = countries,
            selectedCountryCode = "CA",
            manuallySelected = true,
            measuredLatencies = mapOf("CA" to 180, "DE" to 35),
        )

        assertEquals(listOf("CA"), candidates)
    }

    @Test
    fun automaticSelectionUsesMeasuredLatencyAndLeavesUnknownCountriesLast() {
        val countries = listOf(
            country("Sweden", "SE"),
            country("Canada", "CA"),
            country("Germany", "DE"),
        )

        val candidates = FirefoxVpnManager.countryCodesForUi(
            countries = countries,
            selectedCountryCode = "CA",
            manuallySelected = false,
            measuredLatencies = mapOf("SE" to null, "CA" to 90, "DE" to 35),
        )

        assertEquals(listOf("DE", "CA", "SE"), candidates)
    }

    @Test
    fun missingManualSelectionDoesNotSilentlyEnableAutomaticFallback() {
        val countries = listOf(country("Germany", "DE"))

        val candidates = FirefoxVpnManager.countryCodesForUi(
            countries = countries,
            selectedCountryCode = null,
            manuallySelected = true,
            measuredLatencies = mapOf("DE" to 20),
        )

        assertEquals(emptyList<String>(), candidates)
    }

    @Test
    fun anEmptyCountryListPreservesManualIntentAndUsesRecommendedOnlyForAutomaticMode() {
        val manualWithoutCountry = FirefoxVpnManager.countryCodesForUi(
            countries = emptyList(),
            selectedCountryCode = null,
            manuallySelected = true,
            measuredLatencies = emptyMap(),
        )
        val manualWithCountry = FirefoxVpnManager.countryCodesForUi(
            countries = emptyList(),
            selectedCountryCode = " CA ",
            manuallySelected = true,
            measuredLatencies = emptyMap(),
        )
        val automatic = FirefoxVpnManager.countryCodesForUi(
            countries = emptyList(),
            selectedCountryCode = null,
            manuallySelected = false,
            measuredLatencies = emptyMap(),
        )

        assertEquals(emptyList<String>(), manualWithoutCountry)
        assertEquals(listOf("CA"), manualWithCountry)
        assertEquals(listOf(FirefoxVpnServers.RECOMMENDED_CODE), automatic)
    }

    @Test
    fun automaticRecoveryKeepsThePreferredCountryBeforeRankedAlternatives() {
        val preferred = country("Canada", "CA")
        val alternatives = listOf(country("Germany", "DE"), country("Sweden", "SE"))

        val order = FirefoxVpnManager.automaticFallbackOrder(
            preferredCountry = preferred,
            rankedAlternatives = alternatives,
            maxCountries = 2,
        )

        assertEquals(listOf("CA", "DE"), order.map { it.code })
    }

    @Test
    fun automaticRecoveryWithoutAPreferredCountryUsesTheMeasuredOrder() {
        val alternatives = listOf(country("Germany", "DE"), country("Sweden", "SE"))

        val order = FirefoxVpnManager.automaticFallbackOrder(
            preferredCountry = null,
            rankedAlternatives = alternatives,
            maxCountries = 4,
        )

        assertEquals(listOf("DE", "SE"), order.map { it.code })
    }

    @Test
    fun automaticCandidatesRespectTheirBoundedCount() {
        val countries = listOf(
            country("Canada", "CA"),
            country("Germany", "DE"),
            country("Sweden", "SE"),
        )

        val candidates = FirefoxVpnManager.countryCodesForUi(
            countries = countries,
            selectedCountryCode = null,
            manuallySelected = false,
            measuredLatencies = mapOf("CA" to 80, "DE" to 35, "SE" to 50),
            limit = 2,
        )

        assertEquals(listOf("DE", "SE"), candidates)
    }

    private fun country(name: String, code: String) =
        FirefoxVpnServers.Country(name = name, code = code, cities = emptyList())
}
