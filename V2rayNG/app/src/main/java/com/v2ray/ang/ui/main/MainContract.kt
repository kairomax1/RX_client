package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.ui.perappproxy.PerAppRoutingMode

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

/**
 * Where the connect button is in its life. [Connecting] lasts until a real request went through
 * the tunnel, so "connected" on screen means traffic actually flows, not just that the core started.
 */
enum class ConnectPhase { Idle, Connecting, Connected, Failed }

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val routingMode: PerAppRoutingMode = PerAppRoutingMode.ALL,
    val routedAppCount: Int = 0,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    val connectPhase: ConnectPhase = ConnectPhase.Idle,
    /** Last measured latency of the active connection; null = none yet, negative = test failed. */
    val pingMillis: Long? = null,
    /** Bumped on every failed connection attempt so the UI can replay its failure feedback. */
    val failureTick: Int = 0,
    /** Address and country the tunnel leaves from, as seen by the last successful test. */
    val exitIp: String? = null,
    val exitCountry: String? = null,
    /** A pasted subscription link waiting for the user to name it (or decline). */
    val subNameRequest: String? = null,
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data class UpdateGroupSubscription(val groupId: String) : MainAction
    data object ExportAll : MainAction
    data class TestGroupRealPing(val groupId: String) : MainAction
    data object PingThenRemoveInvalid : MainAction
    data class CopySelected(val guids: List<String>) : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction
    data class OpenSubscriptionLink(val url: String) : MainAction

    data class ImportBatchConfig(val configText: String, val askSubName: Boolean = false) : MainAction

    /** Answer to the subscription name question; null or blank means "no name". */
    data class AnswerSubName(val name: String?) : MainAction

    data object LocateHandled : MainAction
}
