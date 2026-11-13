package com.v2ray.ang.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

@Composable
fun MoreMenuContent(onSelected: (MainMoreMenuAction) -> Unit) {
    MainMoreMenuAction.entries.forEachIndexed { index, item ->
        RxMenuItem(
            index = index,
            text = stringResource(item.labelRes),
            onClick = { onSelected(item) },
        )
    }
}

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
