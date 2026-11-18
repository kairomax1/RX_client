package com.v2ray.ang.handler

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

    @Test
    fun theCeilingIsHighEnoughForTheNumberSheAsksFor() {
        // The screen offered up to this number and the collection quietly stopped
        // at 150 regardless: the asked-for number has to be reachable.
        assertTrue(PublicConfigs.MAX_COUNT >= 2220) { "2220 is what she was shown asking for" }
    }
}
