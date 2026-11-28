package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.ui.compose.RxPromptDialog
import com.v2ray.ang.ui.compose.RxPromptOption
import com.v2ray.ang.ui.compose.RxPromptSubject
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.RxFanMenuItem
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.RxMenuItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.ui.compose.AppDropdownMenuItems
import com.v2ray.ang.ui.compose.SelectListDialog

/** The plus menu: three ways to bring a config in, nothing else. */
private enum class ImportMenuAction(@StringRes val labelRes: Int, val iconRes: Int, val action: MainAction) {
    QRCode(R.string.menu_import_qr, R.drawable.ic_qu_scan_24dp, MainAction.ImportQRcode),
    Clipboard(R.string.menu_import_paste, R.drawable.ic_copy, MainAction.ImportClipboard),
    LocalFile(R.string.menu_import_file, R.drawable.ic_file_24dp, MainAction.ImportConfigLocal),
}

/** The three-dots menu, in display order. */
enum class MainMoreMenuAction(@StringRes val labelRes: Int) {
    RestartService(R.string.menu_more_reconnect),
    DeleteAll(R.string.menu_more_delete_all),
    DeleteDuplicate(R.string.menu_more_delete_duplicates),
    DeleteInvalid(R.string.menu_more_delete_failed),
    ExportAll(R.string.menu_more_copy_all),
    CopySelected(R.string.menu_more_copy_selected),
}

internal enum class ServerMenuAction(
    @StringRes val labelRes: Int,
    /** One plain line telling what the entry is for. */
    @StringRes val hintRes: Int,
    @DrawableRes val iconRes: Int,
    val isShareAction: Boolean,
    val supportsComplexProfiles: Boolean,
) {
    ShareQRCode(
        R.string.config_card_qr, R.string.config_card_qr_hint, R.drawable.ic_qr_code_24dp,
        isShareAction = true, supportsComplexProfiles = false,
    ),
    ShareClipboard(
        R.string.config_card_copy, R.string.config_card_copy_hint, R.drawable.ic_copy,
        isShareAction = true, supportsComplexProfiles = false,
    ),
    ShareFullContent(
        R.string.config_card_copy_json, R.string.config_card_copy_json_hint, R.drawable.ic_source_code_24dp,
        isShareAction = true, supportsComplexProfiles = true,
    ),
    Edit(
        R.string.action_edit, R.string.config_card_edit_hint, R.drawable.ic_edit_24dp,
        isShareAction = false, supportsComplexProfiles = true,
    ),
    Delete(
        R.string.action_delete, R.string.config_card_delete_hint, R.drawable.ic_delete_24dp,
        isShareAction = false, supportsComplexProfiles = true,
    ),
}

internal fun serverMenuActions(
    isComplexProfile: Boolean,
    includeManagementActions: Boolean,
): List<ServerMenuAction> = ServerMenuAction.entries.filter { action ->
    (includeManagementActions || action.isShareAction) && (!isComplexProfile || action.supportsComplexProfiles)
}

@Composable
fun ImportMenuContent(onAction: (MainAction) -> Unit) {
    ImportMenuAction.entries.forEachIndexed { index, item ->
        RxMenuItem(
            index = index,
            text = stringResource(item.labelRes),
            iconRes = item.iconRes,
            onClick = { onAction(item.action) },
        )
    }
}

/**
 * The three-dots menu. It carries what v2rayNG keeps behind the same button, in the order
 * that button uses: renew and order the list, take the configs out, throw away what is
 * broken, then the two screens one reaches for from here.
 */
@Composable
fun MoreMenuContent(
    onAction: (MainAction) -> Unit,
    onMoreMenuAction: (MainMoreMenuAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val rows = moreMenuRows()
    var index = 0
    rows.forEach { row ->
        when (row) {
            is MoreMenuRow.Section -> {
                AppDivider(Modifier.padding(vertical = 4.dp))
            }

            is MoreMenuRow.Entry -> {
                RxFanMenuItem(
                    index = index,
                    text = stringResource(row.labelRes),
                    iconRes = row.iconRes,
                    danger = row.danger,
                    onClick = {
                        when (val action = row.action) {
                            is MoreMenuEntry.MainRow -> onAction(action.action)
                            is MoreMenuEntry.MoreRow -> onMoreMenuAction(action.action)
                            is MoreMenuEntry.Navigate -> onNavigate(action.destination)
                        }
                    },
                )
                index++
            }
        }
    }
}

/** One line of the menu: either a divider or an entry. */
private sealed interface MoreMenuRow {
    data object Section : MoreMenuRow
    data class Entry(
        @StringRes val labelRes: Int,
        @DrawableRes val iconRes: Int,
        val danger: Boolean = false,
        val action: MoreMenuEntry,
    ) : MoreMenuRow
}

/** What an entry does when it is tapped. */
private sealed interface MoreMenuEntry {
    data class MainRow(val action: MainAction) : MoreMenuEntry
    data class MoreRow(val action: MainMoreMenuAction) : MoreMenuEntry
    data class Navigate(val destination: MainDestination) : MoreMenuEntry
}

private fun entryOf(
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int,
    action: MainAction,
    danger: Boolean = false,
) = MoreMenuRow.Entry(labelRes, iconRes, danger, MoreMenuEntry.MainRow(action))

private fun entryOf(
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int,
    action: MainMoreMenuAction,
    danger: Boolean = false,
) = MoreMenuRow.Entry(labelRes, iconRes, danger, MoreMenuEntry.MoreRow(action))

private fun entryOf(
    @StringRes labelRes: Int,
    @DrawableRes iconRes: Int,
    destination: MainDestination,
) = MoreMenuRow.Entry(labelRes, iconRes, false, MoreMenuEntry.Navigate(destination))

private fun moreMenuRows(): List<MoreMenuRow> = listOf(
    entryOf(R.string.menu_more_reconnect, R.drawable.ic_refresh_24dp, MainMoreMenuAction.RestartService),
    entryOf(R.string.title_sub_update, R.drawable.ic_cloud_download_24dp, MainAction.UpdateSubscriptions),
    entryOf(R.string.title_sort_by_test_results, R.drawable.ic_outline_filter_alt_24, MainAction.SortByTestResults),
    MoreMenuRow.Section,
    entryOf(R.string.menu_more_copy_all, R.drawable.ic_copy, MainMoreMenuAction.ExportAll),
    entryOf(R.string.menu_more_copy_selected, R.drawable.ic_select_all_24dp, MainMoreMenuAction.CopySelected),
    MoreMenuRow.Section,
    entryOf(R.string.menu_more_delete_duplicates, R.drawable.ic_delete_24dp, MainMoreMenuAction.DeleteDuplicate, danger = true),
    entryOf(R.string.menu_more_delete_failed, R.drawable.ic_delete_24dp, MainMoreMenuAction.DeleteInvalid, danger = true),
    entryOf(R.string.menu_more_delete_all, R.drawable.ic_delete_24dp, MainMoreMenuAction.DeleteAll, danger = true),
    MoreMenuRow.Section,
    entryOf(R.string.title_settings, R.drawable.ic_settings_24dp, MainDestination.Settings),
    entryOf(R.string.drawer_app_log, R.drawable.ic_logcat_24dp, MainDestination.Log),
)

@Composable
fun ShareMethodDialog(
    guid: String,
    profile: ProfileItem,
    more: Boolean,
    onDismiss: () -> Unit,
    onAction: (MainAction) -> Unit,
    onRemove: (String, String) -> Unit,
) {
    val menuActions = serverMenuActions(
        isComplexProfile = profile.configType.isComplexType(),
        includeManagementActions = more,
    )
    // Editing has its own pencil on the row, so the card does not repeat it.
    val entries = menuActions.filter { it != ServerMenuAction.Edit }
    RxPromptDialog(onDismissRequest = onDismiss, alwaysRtl = false) {
        RxPromptSubject(profile.remarks)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.forEachIndexed { index, action ->
                RxPromptOption(
                    icon = painterResource(action.iconRes),
                    title = stringResource(action.labelRes),
                    hint = stringResource(action.hintRes),
                    order = index,
                    danger = action == ServerMenuAction.Delete,
                    onClick = {
                        onDismiss()
                        when (action) {
                            ServerMenuAction.ShareQRCode -> onAction(MainAction.ShareQRCode(guid))
                            ServerMenuAction.ShareClipboard -> onAction(MainAction.ShareClipboard(guid))
                            ServerMenuAction.ShareFullContent -> onAction(MainAction.ShareFullContent(guid))
                            ServerMenuAction.Edit -> onAction(MainAction.EditServer(guid, profile))
                            ServerMenuAction.Delete -> onRemove(guid, profile.remarks)
                        }
                    },
                )
            }
        }
    }
}
