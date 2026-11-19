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
        val body = source.substringAfter("val speed = oneAtATime.withLock { speedOf(context, link) }")
            .substringBefore("onProgress(done.get(), carried.size)")
        assertTrue(
            body.indexOf("done.incrementAndGet()") < body.indexOf("if (speed > 0)"),
            "the attempt must be counted before, and independently of, the success",
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

    /** A dead address must be refused by a connect, not by an eighteen-second tunnel. */
    @Test
    fun aDeadAddressCostsAConnectAndNotATunnel() {
        val source = java.io.File("src/main/java/com/v2ray/ang/handler/PublicConfigs.kt").readText()
        assertTrue(source.contains("if (!answers(link))"))
        assertTrue(source.contains("QUICK_CONNECT_MILLIS = 1500"))
        assertTrue(source.contains("VERIFY_TIMEOUT_MILLIS = 9_000L"))
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
}
