package com.v2ray.ang.ui.main

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
    val isShareAction: Boolean,
    val supportsComplexProfiles: Boolean,
) {
    ShareQRCode(R.string.share_method_qrcode, isShareAction = true, supportsComplexProfiles = false),
    ShareClipboard(R.string.share_method_clipboard, isShareAction = true, supportsComplexProfiles = false),
    ShareFullContent(R.string.share_method_full_content, isShareAction = true, supportsComplexProfiles = true),
    Edit(R.string.action_edit, isShareAction = false, supportsComplexProfiles = true),
    Delete(R.string.action_delete, isShareAction = false, supportsComplexProfiles = true),
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
    SelectListDialog(
        options = menuActions,
        optionText = { stringResource(it.labelRes) },
        onSelected = { action ->
            onDismiss()
            when (action) {
                ServerMenuAction.ShareQRCode -> onAction(MainAction.ShareQRCode(guid))
                ServerMenuAction.ShareClipboard -> onAction(MainAction.ShareClipboard(guid))
                ServerMenuAction.ShareFullContent -> onAction(MainAction.ShareFullContent(guid))
                ServerMenuAction.Edit -> onAction(MainAction.EditServer(guid, profile))
                ServerMenuAction.Delete -> onRemove(guid, profile.remarks)
            }
        },
        onDismiss = onDismiss
    )
}
