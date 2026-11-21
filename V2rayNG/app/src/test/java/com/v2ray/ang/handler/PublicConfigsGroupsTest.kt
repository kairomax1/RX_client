package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scratch group is where a candidate is measured, not something the owner
 * subscribed to. Drawn as a tab it appeared as "rx public pending" beside her own
 * subscriptions; pulled into a sweep it would carry a half-tested address into
 * "test all" or a backup.
 */
class PublicConfigsGroupsTest {

    @Test
    fun theMeasuringGroupsAreNotTabs() {
        assertTrue(PublicConfigs.isInternalGroup(PublicConfigs.PENDING_GROUP))
        assertTrue(PublicConfigs.isInternalGroup("rx_public_sweep"))
    }

    @Test
    fun realGroupsAreUntouched() {
        assertFalse(PublicConfigs.isInternalGroup(PublicConfigs.GROUP_ID))
        assertFalse(PublicConfigs.isInternalGroup(""))
        assertFalse(PublicConfigs.isInternalGroup(null))
        assertFalse(PublicConfigs.isInternalGroup("some-user-subscription"))
    }

    /**
     * The progress figure has to be able to move on its own.
     *
     * It was raised only when a candidate carried something, so a round that
     * opened eighty-one tunnels and had none of them carry reported the same
     * number it reported when it had run none at all — which is why the screen
     * looked as if nothing was being tested. These assert the shape of the fix at
     * its source: the counter is incremented for the attempt, and only the
     * `carried` list grows on success.
     */
    @Test
    fun theCounterCountsAttemptsAndNotOnlySuccesses() {
        val source = java.io.File("src/main/java/com/v2ray/ang/handler/PublicConfigs.kt").readText()
        val body = source.substringAfter("val speed = oneAtATime.withLock {")
            .substringBefore("onProgress(done.get(), carried.size)")
        assertTrue(
            body.indexOf("done.incrementAndGet()") < body.indexOf("if (speed > 0)"),
            "the attempt must be counted before, and independently of, the success",
        )
        assertTrue(
            source.contains("if (pingMs <= 0L)") && source.substringAfter("if (pingMs <= 0L)").substringBefore("return@withPermit").contains("done.incrementAndGet()"),
            "a failed Xray probe must count the attempt and move straight to the next candidate",
        )
    }

    @Test
    fun aCheckThatCarriesNothingDoesNotEmptyTheTab() {
        val source = java.io.File("src/main/java/com/v2ray/ang/handler/PublicConfigs.kt").readText()
        // Exactly one place clears the group, and it is reached only once a config
        // has carried something: "if (!replaced)" guards it. Clearing before the
        // rounds is what emptied the tab every time a check found nothing.
        val clears = Regex("clearServerList\\(GROUP_ID\\)").findAll(source).count()
        assertEquals(1, clears, "the group is replaced in one place only")
        val guardAt = source.indexOf("if (!replaced)")
        assertTrue(guardAt > 0 && guardAt < source.indexOf("clearServerList(GROUP_ID)"))
        assertTrue(source.contains("val previous = MmkvManager.decodeServerList(GROUP_ID)"))
    }

    /**
     * The number on the screen is the number of configs added, not the number that
     * was tried and also not the number that carried: a duplicate the store refuses
     * carried nothing into the list, and the screen used to sit at zero the whole
     * time while the tab grew.
     */
    @Test
    fun theFigureShownIsWhatWentIntoTheTab() {
        val source = java.io.File("src/main/java/com/v2ray/ang/handler/PublicConfigs.kt").readText()
        // The progress record is built from `carried`, which is bumped by what the
        // store took.
        assertTrue(source.contains("onProgress(Progress(origin, found, tried, kept.size, wanted, measured, carried, rounds, poolSize))"))
        assertTrue(
            source.contains("if (added > 0) carried += added"),
            "the figure moves by what was really stored, not by what carried",
        )
        // And the measurement callback no longer writes that figure.
        val callback = source.substringAfter(") { verified, _ ->").substringBefore("report()\n                }")
        assertFalse(callback.contains("carried = "), "the measurement must not decide how many were added")
    }

    /** A dead address must be refused quickly and parallel Xray probes must filter before the tunnel download check. */
    @Test
    fun aDeadAddressCostsAConnectAndNotATunnel() {
        val source = java.io.File("src/main/java/com/v2ray/ang/handler/PublicConfigs.kt").readText()
        assertTrue(source.contains("CONNECT_MILLIS = 1200"))
        assertTrue(source.contains("VERIFY_AT_ONCE = 16"))
        assertTrue(source.contains("VERIFY_TIMEOUT_MILLIS = 5_000L"))
        assertTrue(source.contains("CoreNativeManager.measureOutboundDelay"))
        assertTrue(source.contains("AutoConfigTest.probeFast"))
    }

    @Test
    fun disconnectStopsCollectionAndPreventsAutoConnect() {
        val handlerSource = java.io.File("src/main/java/com/v2ray/ang/handler/PublicConfigs.kt").readText()
        val activitySource = java.io.File("src/main/java/com/v2ray/ang/ui/publicconfigs/PublicConfigsActivity.kt").readText()
        assertTrue(handlerSource.contains("checkLineOrAbort"))
        assertTrue(handlerSource.contains("isNetworkAvailable"))
        assertTrue(activitySource.contains("registerDefaultNetworkCallback"))
        assertTrue(activitySource.contains("!outcome.disconnected"))
    }

    @Test
    fun theCeilingIsHighEnoughForTheNumberSheAsksFor() {
        // The screen offered up to this number and the collection quietly stopped
        // at 150 regardless: the asked-for number has to be reachable.
        assertTrue(PublicConfigs.MAX_COUNT >= 2220) { "2220 is what she was shown asking for" }
    }

    @Test
    fun aNumberBelowTheCeilingIsNeverQuietlyReduced() {
        // What went wrong was not the ceiling alone but that the number the owner
        // set was replaced by one the code preferred. Every number up to the
        // ceiling must survive as itself.
        for (n in listOf(1, 20, 50, 150, 151, 500, 2220, PublicConfigs.MAX_COUNT)) {
            assertEquals(n, PublicConfigs.acceptedCount(n), "the number asked for must be the number used")
        }
        // And something absurd is clamped rather than accepted or crashed on.
        assertEquals(PublicConfigs.MAX_COUNT, PublicConfigs.acceptedCount(999_999))
        assertEquals(1, PublicConfigs.acceptedCount(0))
    }

    @Test
    fun noListAutoSyncIsScheduledAtStartupAndDailyPassIsFlagGated() {
        val publicSrc = java.io.File("src/main/java/com/v2ray/ang/handler/PublicConfigs.kt").readText()
        val appSrc = java.io.File("src/main/java/com/v2ray/ang/AngApplication.kt").readText()
        val dailySrc = java.io.File("src/main/java/com/v2ray/ang/service/DailyMaintenanceWorker.kt").readText()
        val repoSrc = java.io.File("src/main/java/com/v2ray/ang/ui/main/MainRepository.kt").readText()
        val dnsSrc = java.io.File("src/main/java/com/v2ray/ang/handler/XboxDns.kt").readText()

        // The refresh machinery still exists for manual use from the screens.
        assertTrue(publicSrc.contains("REFRESH_MILLIS = 15 * 60 * 1000L"))
        assertTrue(publicSrc.contains("DAILY_MILLIS = 24 * 60 * 60 * 1000L"))
        assertTrue(publicSrc.contains("areGroupConfigsDead()"))
        assertTrue(publicSrc.contains("fun refreshSourcesDaily"))
        assertTrue(publicSrc.contains("SettingsChangeManager.makeSetupGroupTab()"))
        assertTrue(dnsSrc.contains("fun refreshDailyIfDue"))
        assertTrue(dnsSrc.contains("fun scheduleDaily"))
        assertTrue(dnsSrc.contains("DAILY_LIST_MILLIS = 24 * 60 * 60 * 1000L"))

        // But nothing is auto-scheduled at app start: no 15-minute public-configs worker,
        // no 6-hourly background sync, no app-start kick, no daily DNS-list worker.
        assertFalse(appSrc.contains("PublicConfigs.schedule(this)"))
        assertFalse(appSrc.contains("XboxDns.scheduleDaily(this)"))
        assertFalse(appSrc.contains("BackgroundSyncManager.schedule(this)"))
        assertFalse(appSrc.contains("BackgroundSyncManager.kickIfDue(this)"))

        // The daily maintenance pass only refreshes lists behind their feature flags
        // (which default to off), and the main screen does not re-arm the worker or
        // probe configs unless the publicConfigs flag is on.
        assertTrue(dailySrc.contains("isFeatureEnabled { it.publicConfigs }"))
        assertTrue(dailySrc.contains("isFeatureEnabled { it.dnsScanner }"))
        assertTrue(dailySrc.contains("isFeatureEnabled { it.adBlock }"))
        assertFalse(dailySrc.contains("BackgroundSyncManager.syncAll(applicationContext)"))
        assertTrue(repoSrc.contains("isFeatureEnabled { it.publicConfigs }"))
    }

    @Test
    fun publicConfigsActivityPreservesPartialAddedConfigsAndNotifiesMain() {
        val activitySrc = java.io.File("src/main/java/com/v2ray/ang/ui/publicconfigs/PublicConfigsActivity.kt").readText()
        val mainActivitySrc = java.io.File("src/main/java/com/v2ray/ang/ui/main/MainActivity.kt").readText()
        assertTrue(activitySrc.contains("EXTRA_AUTO_CONNECT"))
        assertTrue(activitySrc.contains("carried > 0 || added > 0"))
        assertTrue(mainActivitySrc.contains("RefreshGroups"))
    }

    @Test
    fun dailyRefreshDoesNotThrottleGroupRebuildAndMainObservesUpdatesImmediately() {
        val publicSrc = java.io.File("src/main/java/com/v2ray/ang/handler/PublicConfigs.kt").readText()
        val mainVmSrc = java.io.File("src/main/java/com/v2ray/ang/ui/main/MainViewModel.kt").readText()
        val mainActivitySrc = java.io.File("src/main/java/com/v2ray/ang/ui/main/MainActivity.kt").readText()

        // refreshSourcesDaily must not stamp KEY_CHECKED (which blocked refreshIfDue right after it).
        val refreshDailyBody = publicSrc.substringAfter("fun refreshSourcesDaily").substringBefore("fun refreshIfDue")
        assertFalse(refreshDailyBody.contains("KEY_CHECKED"))

        assertTrue(publicSrc.contains("val groupUpdated: StateFlow<Long>"))
        assertTrue(publicSrc.contains("notifyGroupUpdated()"))
        assertTrue(mainVmSrc.contains("PublicConfigs.groupUpdated"))
        assertTrue(mainActivitySrc.contains("override fun onResume()"))
        assertTrue(mainActivitySrc.contains("SettingsChangeManager.consumeSetupGroupTab()"))
    }
}
