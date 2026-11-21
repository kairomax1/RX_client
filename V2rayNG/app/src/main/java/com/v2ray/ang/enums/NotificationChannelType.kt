package com.v2ray.ang.enums

import androidx.annotation.StringRes
import com.v2ray.ang.R

/**
 * Enum defining different notification channels.
 * Each channel has a unique channelId, notificationId, and display name.
 */
enum class NotificationChannelType(
    val channelId: String,
    @StringRes val channelNameRes: Int,
    val notificationId: Int
) {
    SUBSCRIPTION_UPDATE(
        channelId = "subscription_update_channel",
        channelNameRes = R.string.notification_channel_subscription_updates,
        notificationId = 13
    ),
    CORE_TEST(
        channelId = "core_test_channel",
        channelNameRes = R.string.notification_channel_connection_checks,
        notificationId = 12
    ),
    SHARING(
        channelId = "rx_sharing_channel",
        channelNameRes = R.string.notification_channel_sharing,
        notificationId = 23
    ),
    /** A device asking to come in: this one is allowed to make a sound. */
    SHARING_ASK(
        channelId = "rx_sharing_ask_channel",
        channelNameRes = R.string.notification_channel_sharing_ask,
        notificationId = 24
    )
}
